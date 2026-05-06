"""Storage layer.

Two implementations behind a common interface:

  - InMemoryStorage:    dict only. Used for tests and as the building block for
                        the WAL+snapshot store's hot state.
  - WalSnapshotStorage: durable. Append-only WAL fsync'd on every write +
                        periodic full snapshots. Replay snapshot then WAL on
                        startup. Truncate WAL after each snapshot (compaction).

Why this design:
  - WAL gives us crash safety with one fsync per write (the cheapest durable op).
  - Snapshots bound recovery time: without them, restart cost grows forever.
  - Snapshot + WAL is ALSO what the live backup leans on (see backup.py): the
    backup just copies the snapshot file plus the WAL up to a frozen LSN, while
    new writes keep appending past it. No write lock needed.
"""
from __future__ import annotations

import json
import os
import threading
from typing import Iterable, Optional, Protocol, Tuple

from .types import VersionedValue


# --------------------------------------------------------------------------- #
# Interface
# --------------------------------------------------------------------------- #
class Storage(Protocol):
    """Pluggable durability layer. All methods must be thread-safe."""

    def get(self, key: str) -> Optional[VersionedValue]: ...

    def put(self, key: str, vv: VersionedValue) -> int:
        """Persist `vv` and return its assigned LSN (monotonically increasing)."""
        ...

    def items(self) -> Iterable[Tuple[str, VersionedValue]]: ...
    def current_lsn(self) -> int: ...
    def close(self) -> None: ...


# --------------------------------------------------------------------------- #
# In-memory
# --------------------------------------------------------------------------- #
class InMemoryStorage:
    """Pure RAM. No durability. Perfect for unit tests and as the hot cache
    inside WalSnapshotStorage."""

    def __init__(self) -> None:
        self._data: dict[str, VersionedValue] = {}
        self._lock = threading.RLock()
        self._lsn = 0

    def get(self, key: str) -> Optional[VersionedValue]:
        with self._lock:
            vv = self._data.get(key)
            # Tombstones are returned to upper layers so replication can
            # propagate "delete-wins-over-old-write" correctly. The KVStore
            # facade hides them from end users.
            return vv

    def put(self, key: str, vv: VersionedValue) -> int:
        with self._lock:
            existing = self._data.get(key)
            # Idempotent + LWW: only accept newer versions. This makes replays
            # (WAL replay, replication retries) safe.
            if vv.is_newer_than(existing):
                self._data[key] = vv
            self._lsn += 1
            return self._lsn

    def items(self) -> Iterable[Tuple[str, VersionedValue]]:
        with self._lock:
            return list(self._data.items())

    def current_lsn(self) -> int:
        with self._lock:
            return self._lsn

    def close(self) -> None:
        return None


# --------------------------------------------------------------------------- #
# WAL + snapshot
# --------------------------------------------------------------------------- #
class WalSnapshotStorage:
    """Crash-safe storage.

    On-disk layout under `data_dir`:
        snapshot.json     <- last full snapshot {lsn, data: {key: vv_dict}}
        wal.log           <- newline-delimited JSON entries: {lsn, key, vv}

    Write path (put):
        1. Acquire lock, assign next LSN.
        2. Update in-memory dict (LWW guarded).
        3. Append entry to WAL, flush + fsync.  <-- durability boundary
        4. Return LSN.

    Read path (get):
        Pure in-memory dict lookup.

    Recovery:
        1. Load snapshot if present (sets in-memory dict and lsn = snapshot.lsn).
        2. Stream wal.log; apply each entry whose lsn > current lsn.
        3. Continue assigning LSNs from the max seen.

    Snapshotting (called by backup or compaction):
        1. Under lock, copy in-memory dict + remember `frozen_lsn`.
        2. Outside the lock, write snapshot.tmp, fsync, atomic-rename to snapshot.json.
        3. Truncate wal.log entries with lsn <= frozen_lsn.
       The lock is held only for a dict copy (microseconds) -- writes are NOT
       blocked during the slow disk work.
    """

    SNAPSHOT_FILE = "snapshot.json"
    WAL_FILE = "wal.log"

    def __init__(self, data_dir: str, fsync_on_write: bool = True) -> None:
        self.data_dir = data_dir
        self.fsync_on_write = fsync_on_write
        os.makedirs(data_dir, exist_ok=True)

        self._mem = InMemoryStorage()
        self._lock = threading.RLock()
        self._lsn = 0
        self._wal_path = os.path.join(data_dir, self.WAL_FILE)
        self._snap_path = os.path.join(data_dir, self.SNAPSHOT_FILE)

        self._recover()

        # Open WAL in append mode after recovery so writes go to the end.
        self._wal_fp = open(self._wal_path, "a", buffering=1)

    # ----- public API ------------------------------------------------------ #
    def get(self, key: str) -> Optional[VersionedValue]:
        return self._mem.get(key)

    def put(self, key: str, vv: VersionedValue) -> int:
        with self._lock:
            self._lsn += 1
            lsn = self._lsn
            # Apply in-memory first (LWW guarded inside InMemoryStorage).
            self._mem.put(key, vv)
            # Always log the attempt -- even if LWW rejected it -- because
            # the log is the system of record. On replay we re-apply LWW.
            entry = {"lsn": lsn, "key": key, "vv": vv.to_dict()}
            self._wal_fp.write(json.dumps(entry) + "\n")
            self._wal_fp.flush()
            if self.fsync_on_write:
                os.fsync(self._wal_fp.fileno())
            return lsn

    def items(self) -> Iterable[Tuple[str, VersionedValue]]:
        return self._mem.items()

    def current_lsn(self) -> int:
        with self._lock:
            return self._lsn

    def close(self) -> None:
        with self._lock:
            try:
                self._wal_fp.flush()
                if self.fsync_on_write:
                    os.fsync(self._wal_fp.fileno())
            finally:
                self._wal_fp.close()

    # ----- snapshot / compaction (also used by backup.py) ------------------ #
    def snapshot(self) -> int:
        """Take a consistent snapshot of in-memory state and truncate the WAL.

        Returns the frozen LSN.

        Concurrency: the only critical section is the dict copy + LSN read.
        Disk I/O happens outside the lock so concurrent puts are not blocked.
        """
        with self._lock:
            frozen_lsn = self._lsn
            # Shallow copy is fine: VersionedValue is frozen/immutable.
            snapshot_data = {k: v.to_dict() for k, v in self._mem.items()}

        tmp_path = self._snap_path + ".tmp"
        with open(tmp_path, "w") as f:
            json.dump({"lsn": frozen_lsn, "data": snapshot_data}, f)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, self._snap_path)  # atomic on POSIX

        self._truncate_wal_through(frozen_lsn)
        return frozen_lsn

    def wal_path(self) -> str:
        return self._wal_path

    def snapshot_path(self) -> str:
        return self._snap_path

    # ----- internals ------------------------------------------------------- #
    def _recover(self) -> None:
        if os.path.exists(self._snap_path):
            with open(self._snap_path) as f:
                snap = json.load(f)
            self._lsn = int(snap.get("lsn", 0))
            for k, vv_dict in snap.get("data", {}).items():
                vv = VersionedValue.from_dict(vv_dict)
                if vv is not None:
                    # Bypass mem.put's LSN counter -- we set it explicitly above.
                    self._mem._data[k] = vv  # noqa: SLF001
            self._mem._lsn = self._lsn  # noqa: SLF001

        if os.path.exists(self._wal_path):
            with open(self._wal_path) as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        entry = json.loads(line)
                    except json.JSONDecodeError:
                        # Torn write at EOF -- common after a crash. Stop here;
                        # everything past this point is unrecoverable garbage.
                        break
                    lsn = int(entry["lsn"])
                    if lsn <= self._lsn:
                        continue
                    vv = VersionedValue.from_dict(entry["vv"])
                    if vv is not None:
                        self._mem.put(entry["key"], vv)
                    self._lsn = lsn
                    self._mem._lsn = lsn  # noqa: SLF001

    def _truncate_wal_through(self, through_lsn: int) -> None:
        """Drop WAL entries whose lsn <= through_lsn. Keep the rest."""
        with self._lock:
            self._wal_fp.flush()
            if self.fsync_on_write:
                os.fsync(self._wal_fp.fileno())
            # Re-read what's currently on disk and rewrite only the tail.
            survivors: list[str] = []
            if os.path.exists(self._wal_path):
                with open(self._wal_path) as f:
                    for line in f:
                        s = line.strip()
                        if not s:
                            continue
                        try:
                            entry = json.loads(s)
                        except json.JSONDecodeError:
                            break
                        if int(entry["lsn"]) > through_lsn:
                            survivors.append(s)
            self._wal_fp.close()
            tmp = self._wal_path + ".tmp"
            with open(tmp, "w") as f:
                for s in survivors:
                    f.write(s + "\n")
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self._wal_path)
            self._wal_fp = open(self._wal_path, "a", buffering=1)
