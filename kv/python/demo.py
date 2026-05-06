"""End-to-end demo of the distributed KV store.

What this exercises:
  1. Three nodes start fresh, share an in-process Transport, all writes/reads
     succeed and replicate.
  2. We kill node3 mid-flight. Writes still succeed (W=2 of 3).
  3. We restart node3 from disk. WAL replay restores its previous data.
  4. Read repair pushes the missed updates to it on the next read.
  5. We launch a writer thread that hammers puts in the background, then take
     a live backup. Then we restore the backup into a brand-new directory and
     verify it is consistent.
"""
from __future__ import annotations

import os
import shutil
import threading
import time

from kvstore import KVStore
from kvstore.storage import WalSnapshotStorage
from kvstore.transport import InProcessTransport


DATA_ROOT = ".kv_data_demo"
BACKUP_DIR = ".kv_backup_demo"


def _fresh_dirs() -> None:
    for p in (DATA_ROOT, BACKUP_DIR):
        if os.path.exists(p):
            shutil.rmtree(p)
        os.makedirs(p, exist_ok=True)


def _make_node(node_id: str, peers: list[str], transport: InProcessTransport) -> KVStore:
    storage = WalSnapshotStorage(os.path.join(DATA_ROOT, node_id))
    return KVStore(node_id, peers, transport=transport, storage=storage)


def header(s: str) -> None:
    print("\n" + "=" * 70)
    print(s)
    print("=" * 70)


def main() -> None:
    _fresh_dirs()

    header("1. Bootstrap a 3-node cluster sharing one in-process transport")
    transport = InProcessTransport()
    node1 = _make_node("node1", ["node2", "node3"], transport)
    node2 = _make_node("node2", ["node1", "node3"], transport)
    node3 = _make_node("node3", ["node1", "node2"], transport)

    node1.put("user:1", {"name": "Alice", "email": "alice@example.com"})
    node1.put("user:2", {"name": "Bob"})
    print("node1 GET user:1 ->", node1.get("user:1"))
    # served via quorum from node2's POV
    print("node2 GET user:1 ->", node2.get("user:1"))
    print("node3 GET user:2 ->", node3.get("user:2"))

    header("2. Kill node3, keep writing -- W=2 of 3 still meets quorum")
    transport.mark_down("node3")
    ok = node1.put("user:1", {"name": "Alice v2"})
    print("put while node3 down:", ok)
    print("node2 GET user:1 ->", node2.get("user:1"))
    # node3 is still down; reads served from node1 + node2 = R=2.

    header("3. Restart node3 from disk (WAL replay) -- old data survives")
    node3.close()
    transport.unregister("node3")
    node3_restarted = _make_node("node3", ["node1", "node2"], transport)
    transport.mark_up("node3")
    # Direct local read (bypass quorum) shows what survived recovery:
    direct = node3_restarted._storage.get("user:1")  # noqa: SLF001
    print("node3 local view of user:1 right after restart:",
          direct.value if direct else None)
    print("(may be the OLD value -- node3 missed the write while it was down)")

    header("4. A coordinated read triggers read-repair on the stale replica")
    print("node1 GET user:1 (this also repairs node3) ->", node1.get("user:1"))
    # Give the fire-and-forget repair a moment.
    time.sleep(0.05)
    direct = node3_restarted._storage.get("user:1")  # noqa: SLF001
    print("node3 local view of user:1 after read-repair:",
          direct.value if direct else None)

    header("5. Live backup while writes keep coming")
    stop = threading.Event()
    write_count = {"n": 0}

    def writer() -> None:
        i = 0
        while not stop.is_set():
            node1.put(f"bg:{i % 50}", {"i": i, "ts": time.time()})
            write_count["n"] += 1
            i += 1
            # No sleep -- hammer it.

    t = threading.Thread(target=writer, daemon=True)
    t.start()
    time.sleep(0.1)  # let the writer spin up

    backup_dest = os.path.join(BACKUP_DIR, "node1")
    print(f"writes-so-far before backup: {write_count['n']}")
    manifest = node1.backup(backup_dest)
    print(f"writes-so-far after  backup: {write_count['n']}")
    print("backup manifest:", manifest)

    stop.set()
    t.join()

    header("6. Restore the backup into a fresh storage and verify")
    restore_dir = os.path.join(BACKUP_DIR, "restored")
    os.makedirs(restore_dir, exist_ok=True)
    # A "restore" is just dropping the backup files into a data dir and opening
    # WalSnapshotStorage on it -- recovery does the rest.
    shutil.copyfile(os.path.join(backup_dest, "snapshot.json"),
                    os.path.join(restore_dir, "snapshot.json"))
    shutil.copyfile(os.path.join(backup_dest, "wal.log"),
                    os.path.join(restore_dir, "wal.log"))
    restored = WalSnapshotStorage(restore_dir)
    sample = restored.get("user:1")
    print("restored user:1 ->", sample.value if sample else None)
    bg_keys = [k for k, _ in restored.items() if k.startswith("bg:")]
    print(f"restored backup contains {len(bg_keys)} bg:* keys "
          f"(out of ~50 distinct possible)")
    restored.close()

    header("Cleanup")
    node1.close()
    node2.close()
    node3_restarted.close()
    print("done.")


if __name__ == "__main__":
    main()
