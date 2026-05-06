"""Backup strategies.

The take-home asks for a *live* backup with no lost writes. We implement the
"WAL checkpoint" approach used (in spirit) by Postgres, MySQL, and Redis:

  1. Force the storage to write a fresh snapshot on disk. Snapshotting itself
     blocks writers only for the time it takes to copy a Python dict (microseconds).
     The slow disk I/O happens outside the lock.
  2. Remember the LSN at the moment the snapshot was frozen (call it `frozen_lsn`).
  3. Copy the snapshot file to the backup directory.
  4. Copy WAL entries with lsn > frozen_lsn (any writes that landed *after*
     snapshot freeze but *before* this backup completes) into the backup.

Concurrent writes during steps 3-4 keep appending to the same WAL file. They
just won't be in this backup -- they'll be in the next one. Because the WAL is
strictly append-only and entries are immutable, reading the prefix while the
tail keeps growing is safe.

Recovery from backup = recover from snapshot then replay the saved WAL tail.

This module is pluggable: a CopyOnWriteSnapshotBackup or ForkBackup could be
slotted in without changing the KVStore facade.
"""
from __future__ import annotations

import json
import os
import shutil
import time
from typing import Protocol

from .storage import WalSnapshotStorage


class BackupStrategy(Protocol):
    def backup(self, dest_dir: str) -> dict:
        """Take a live, consistent backup. Returns a manifest dict."""
        ...


class WalCheckpointBackup:
    """Snapshot + WAL-tail copy. Works only with WalSnapshotStorage (or anything
    that exposes the same `snapshot()` + WAL file contract)."""

    def __init__(self, storage: WalSnapshotStorage) -> None:
        self.storage = storage

    def backup(self, dest_dir: str) -> dict:
        os.makedirs(dest_dir, exist_ok=True)

        # Step 1+2: force snapshot, get the LSN at which it was frozen.
        # This is the consistency boundary. Writes >= frozen_lsn+1 are NOT in
        # this backup (they're in the WAL tail we'll grab next or in the next
        # backup). Writes <= frozen_lsn ARE in the snapshot. No write is lost,
        # no write is double-counted.
        frozen_lsn = self.storage.snapshot()

        # Step 3: copy the snapshot. After snapshot() returns, snapshot.json
        # contains exactly the state at frozen_lsn -- it's immutable until the
        # next snapshot() call, so a plain file copy is safe.
        snap_src = self.storage.snapshot_path()
        snap_dst = os.path.join(dest_dir, "snapshot.json")
        shutil.copyfile(snap_src, snap_dst)

        # Step 4: copy WAL entries with lsn > frozen_lsn. The WAL file may be
        # being appended to RIGHT NOW. That's fine: we just stop reading at
        # the first torn line or EOF.
        wal_src = self.storage.wal_path()
        wal_dst = os.path.join(dest_dir, "wal.log")
        copied = 0
        max_lsn = frozen_lsn
        with open(wal_dst, "w") as out:
            if os.path.exists(wal_src):
                with open(wal_src) as f:
                    for line in f:
                        s = line.strip()
                        if not s:
                            continue
                        try:
                            entry = json.loads(s)
                        except json.JSONDecodeError:
                            # Torn write at the tail -- writer is mid-flush.
                            # Stop here; everything past this is incomplete.
                            break
                        lsn = int(entry["lsn"])
                        # snapshot() already truncated the WAL through
                        # frozen_lsn, so in practice everything we read is
                        # newer. Defensive check anyway.
                        if lsn <= frozen_lsn:
                            continue
                        out.write(s + "\n")
                        copied += 1
                        if lsn > max_lsn:
                            max_lsn = lsn
            out.flush()
            os.fsync(out.fileno())

        manifest = {
            "frozen_lsn": frozen_lsn,
            "wal_tail_entries": copied,
            "max_lsn_in_backup": max_lsn,
            "taken_at_unix": time.time(),
        }
        manifest_path = os.path.join(dest_dir, "manifest.json")
        with open(manifest_path, "w") as f:
            json.dump(manifest, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        return manifest
