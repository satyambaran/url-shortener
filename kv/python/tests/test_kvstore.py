"""Tests for the distributed KV store.

Focused on the design's correctness claims:
  - basic put/get, multi-node replication
  - quorum tolerates 1 node down
  - persistence across restart
  - LWW resolves concurrent writes deterministically
  - read repair fixes stale replicas
  - live backup is consistent (no torn entries, no lost completed writes)
"""
from __future__ import annotations

import os
import shutil
import threading
import time
import unittest

from kvstore import KVStore
from kvstore.storage import InMemoryStorage, WalSnapshotStorage
from kvstore.transport import InProcessTransport
from kvstore.types import VersionedValue


TMP_ROOT = ".kv_test_data"


def _wipe() -> None:
    if os.path.exists(TMP_ROOT):
        shutil.rmtree(TMP_ROOT)
    os.makedirs(TMP_ROOT, exist_ok=True)


def _make_cluster(use_disk: bool = False) -> tuple[InProcessTransport, list[KVStore]]:
    transport = InProcessTransport()
    nodes = []
    ids = ["node1", "node2", "node3"]
    for nid in ids:
        peers = [p for p in ids if p != nid]
        if use_disk:
            storage = WalSnapshotStorage(os.path.join(TMP_ROOT, nid))
        else:
            storage = InMemoryStorage()
        nodes.append(KVStore(nid, peers, transport=transport, storage=storage))
    return transport, nodes


class TestBasic(unittest.TestCase):
    def setUp(self) -> None:
        _wipe()

    def test_put_get_roundtrip(self) -> None:
        _, nodes = _make_cluster()
        n1, n2, n3 = nodes
        self.assertTrue(n1.put("k", "v"))
        self.assertEqual(n1.get("k"), "v")
        self.assertEqual(n2.get("k"), "v")
        self.assertEqual(n3.get("k"), "v")
        for n in nodes:
            n.close()

    def test_get_missing_returns_none(self) -> None:
        _, nodes = _make_cluster()
        self.assertIsNone(nodes[0].get("nope"))
        for n in nodes:
            n.close()

    def test_delete_tombstone(self) -> None:
        _, nodes = _make_cluster()
        n1 = nodes[0]
        n1.put("k", "v")
        self.assertEqual(n1.get("k"), "v")
        n1.delete("k")
        for n in nodes:
            self.assertIsNone(n.get("k"))
        for n in nodes:
            n.close()


class TestFaultTolerance(unittest.TestCase):
    def setUp(self) -> None:
        _wipe()

    def test_one_node_down_writes_still_succeed(self) -> None:
        transport, nodes = _make_cluster()
        transport.mark_down("node3")
        self.assertTrue(nodes[0].put("k", "v"))
        self.assertEqual(nodes[1].get("k"), "v")
        for n in nodes:
            n.close()

    def test_two_nodes_down_writes_fail(self) -> None:
        transport, nodes = _make_cluster()
        transport.mark_down("node2")
        transport.mark_down("node3")
        # W=2 needed but only 1 reachable replica (self) -> fail.
        self.assertFalse(nodes[0].put("k", "v"))
        for n in nodes:
            n.close()

    def test_persistence_across_restart(self) -> None:
        transport, nodes = _make_cluster(use_disk=True)
        nodes[0].put("k", "v1")
        # Cleanly stop all nodes (flush + fsync).
        for n in nodes:
            n.close()
        # Bring them back up with the SAME data dirs and a fresh transport.
        transport2 = InProcessTransport()
        ids = ["node1", "node2", "node3"]
        nodes2 = []
        for nid in ids:
            peers = [p for p in ids if p != nid]
            storage = WalSnapshotStorage(os.path.join(TMP_ROOT, nid))
            nodes2.append(
                KVStore(nid, peers, transport=transport2, storage=storage))
        self.assertEqual(nodes2[0].get("k"), "v1")
        for n in nodes2:
            n.close()


class TestConflictAndRepair(unittest.TestCase):
    def setUp(self) -> None:
        _wipe()

    def test_lww_higher_timestamp_wins(self) -> None:
        v_old = VersionedValue("old", timestamp=10, node_id="n1")
        v_new = VersionedValue("new", timestamp=20, node_id="n1")
        self.assertTrue(v_new.is_newer_than(v_old))
        self.assertFalse(v_old.is_newer_than(v_new))

    def test_lww_node_id_breaks_tie(self) -> None:
        a = VersionedValue("a", timestamp=10, node_id="node1")
        b = VersionedValue("b", timestamp=10, node_id="node2")
        self.assertTrue(b.is_newer_than(a))
        self.assertFalse(a.is_newer_than(b))

    def test_read_repair_fixes_stale_replica(self) -> None:
        transport, nodes = _make_cluster()
        n1, n2, n3 = nodes
        # Take node3 down, do a write that misses it.
        transport.mark_down("node3")
        self.assertTrue(n1.put("k", "v_new"))
        transport.mark_up("node3")
        # node3 is stale right now.
        self.assertIsNone(n3._storage.get("k"))  # noqa: SLF001
        # Coordinated read from any node should repair node3.
        self.assertEqual(n1.get("k"), "v_new")
        time.sleep(0.05)  # repair is fire-and-forget
        repaired = n3._storage.get("k")  # noqa: SLF001
        self.assertIsNotNone(repaired)
        self.assertEqual(repaired.value, "v_new")
        for n in nodes:
            n.close()


class TestBackup(unittest.TestCase):
    def setUp(self) -> None:
        _wipe()

    def test_live_backup_no_lost_completed_writes(self) -> None:
        """All writes that returned True BEFORE the backup() call must be in
        the restored snapshot. Writes after may or may not be."""
        transport, nodes = _make_cluster(use_disk=True)
        n1 = nodes[0]
        for i in range(100):
            self.assertTrue(n1.put(f"k{i}", i))

        stop = threading.Event()

        def hammer() -> None:
            i = 100
            while not stop.is_set():
                n1.put(f"k{i}", i)
                i += 1

        t = threading.Thread(target=hammer, daemon=True)
        t.start()
        time.sleep(0.05)
        backup_dir = os.path.join(TMP_ROOT, "backup_n1")
        manifest = n1.backup(backup_dir)
        stop.set()
        t.join()

        self.assertGreater(manifest["frozen_lsn"], 0)

        # Restore the backup as if recovering on a fresh disk.
        restore_dir = os.path.join(TMP_ROOT, "restored")
        os.makedirs(restore_dir, exist_ok=True)
        shutil.copyfile(os.path.join(backup_dir, "snapshot.json"),
                        os.path.join(restore_dir, "snapshot.json"))
        shutil.copyfile(os.path.join(backup_dir, "wal.log"),
                        os.path.join(restore_dir, "wal.log"))
        restored = WalSnapshotStorage(restore_dir)
        for i in range(100):
            vv = restored.get(f"k{i}")
            self.assertIsNotNone(vv, f"k{i} missing from backup")
            self.assertEqual(vv.value, i)
        restored.close()
        for n in nodes:
            n.close()


if __name__ == "__main__":
    unittest.main()
