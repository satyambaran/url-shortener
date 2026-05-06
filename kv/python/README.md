# Distributed Key-Value Store

Take-home implementation. Three nodes, quorum replication, crash-safe persistence,
live backups. Designed so every layer is **pluggable** behind a small interface.

---

## Run it

```bash
python3 -m unittest tests.test_kvstore -v   # 10 tests
python3 demo.py                             # end-to-end demo
```

The demo spins up 3 in-process nodes, kills one, restarts it from disk, watches
read-repair fix it, then takes a live backup while a writer hammers the cluster.

---

## Layout

```
kvstore/
  types.py          # VersionedValue + wire actions
  storage.py        # Storage interface + InMemoryStorage + WalSnapshotStorage
  transport.py      # Transport interface + InProcessTransport (with fault injection)
  partitioner.py    # Partitioner interface + ConsistentHashPartitioner + FullReplication
  conflict.py       # ConflictResolver interface + LastWriteWinsResolver
  replication.py    # Replicator interface + QuorumReplicator (N=3, W=2, R=2)
  backup.py         # BackupStrategy interface + WalCheckpointBackup
  kvstore.py        # KVStore facade -- composes everything above
demo.py
tests/test_kvstore.py
```

The `KVStore` class matches the take-home skeleton's signature
(`__init__(node_id, peer_nodes)`, `send_to_node`, `on_message`, `get`, `put`).
Every other knob is an optional kwarg with a sensible default.

---

## Key design decisions

### 1. Quorum replication (Dynamo-style: N=3, W=2, R=2)
- **Why:** No leader election. Tolerates 1 node down for both reads and writes.
  W+R > N gives strong-ish single-key consistency without consensus overhead.
- **Cost:** Concurrent writes resolve by last-write-wins; the loser is silently
  dropped. Documented and intentional. For "I cannot lose any write" semantics
  you'd swap in a Raft-based replicator (the `Replicator` interface stays the same).
- **Failure model:** writes return `True` only after `W` replicas have acked.
  Stragglers continue in the background; if they never ack, eventual consistency
  is restored on the next read via **read-repair**.

### 2. Consistent hashing for partitioning
- With 3 nodes and RF=3 this degenerates to full replication. The abstraction
  is still worth having: scale to 5 nodes tomorrow and only ~RF/N keys move.
- Virtual nodes (128/physical) smooth out hotspots from skewed key distributions.
- Pluggable: the `FullReplicationPartitioner` is also included for clarity.

### 3. WAL + periodic snapshots for persistence
- **Write path:** in-memory dict update + append to WAL + `fsync`. One disk
  sync per write, the cheapest durable primitive.
- **Recovery:** load snapshot, replay WAL entries with `lsn > snapshot.lsn`.
  Bounded recovery time because snapshots compact old WAL.
- **Crash safety:** torn writes at WAL tail are detected (JSON parse failure)
  and treated as the recovery boundary. Snapshot file uses `write -> fsync ->
  atomic rename` so it's all-or-nothing.

### 4. Live, lock-free backup via WAL checkpoint
This is the one I'd most want to talk through.

```
backup(dest):
    frozen_lsn = storage.snapshot()       # 1. fast: dict copy under lock,
                                          #    then slow disk I/O outside lock
    copy snapshot.json -> dest            # 2. snapshot is immutable until next
                                          #    snapshot() call -- safe to copy
    copy WAL entries lsn > frozen_lsn     # 3. writers keep appending; we just
                                          #    stop at first torn line / EOF
```

**Concurrent writes during the backup don't block and aren't lost** — they're
either in the snapshot (lsn ≤ frozen_lsn), or in the WAL tail we copied
(frozen_lsn < lsn ≤ max_lsn_in_backup), or in the next backup (lsn >
max_lsn_in_backup). Demo proves it: writes-counter goes from 961 → 971 across
the backup call, and the manifest shows `frozen_lsn: 966, wal_tail_entries: 7`.
Reading a strictly-append-only file while it grows is well-defined: we never
read past the torn-write boundary.

### 5. Last-write-wins on (timestamp, node_id)
- Each write stamped with `time.time_ns()` plus the originating `node_id` as
  tiebreaker. Cluster-wide deterministic ordering even when clocks tie to the ns.
- Per-node monotonic clamp (`_next_ts`) defends against NTP step-backs and the
  same-ns case for sequential local writes.
- Pluggable: `ConflictResolver` interface can be swapped for vector clocks +
  sibling resolution if you need true conflict detection.

### 6. Tombstone-based delete
- Deletes go through the same code path as puts (a `VersionedValue` with
  `tombstone=True`). This stops a stale-then-recovered replica from
  "resurrecting" deleted keys, which is the classic Dynamo pitfall.

### 7. Pluggable transport
- The take-home contract `send_to_node(target, message) -> Optional[dict]` is
  preserved. Behind it lives a `Transport` interface with a single
  `InProcessTransport` impl (so the demo runs in one process). HTTP / gRPC
  versions implement the same interface and the rest of the system doesn't change.
- `InProcessTransport` includes fault-injection knobs (`mark_down`,
  `set_latency_ms`, `set_drop_rate`) used by the demo and tests.

---

## What this code is honest about NOT solving

These are the things I'd raise in the interview rather than pretend they're handled:

- **No hinted handoff.** A write that misses node3 (down) and succeeds via W=2
  on {node1, node2} doesn't queue a "deliver to node3 when up" hint. We rely
  on read-repair instead. Cheap to add: queue the message in the coordinator's
  storage, retry on heartbeat success.
- **Membership is static.** `peer_nodes` is fixed at construction. Adding a
  node would require a config push + ring rebuild. No SWIM/gossip.
- **Concurrent writes silently drop one value.** LWW, by design. Vector
  clocks would expose siblings to the application.
- **No client-visible read-your-writes guarantee** unless you read from the
  coordinator that wrote (and W+R > N gives you single-key linearizability for
  non-concurrent writes).
- **Backup is per-node.** A cluster-wide consistent backup would need a
  coordinated snapshot (Chandy-Lamport). For most disaster recovery purposes
  per-node is fine because every key has RF replicas; you can restore any one.
- **`InProcessTransport.send` runs the receiver inline on the caller's
  thread.** Real network transports would be async; the `Replicator` already
  uses a thread pool so the call shape is correct, but timing characteristics
  differ.
- **Snapshot trigger is manual** (called from `backup()`). Production would add
  a background thread that snapshots on size/time thresholds.

---

## Failure scenarios walked

| Scenario | What happens |
|---|---|
| 1 node down | Writes (W=2) and reads (R=2) still succeed via the other 2. |
| 2 nodes down | `put` returns `False` (cannot meet quorum). `get` returns `None`. |
| Slow node | `request_timeout_s` (default 2s) kicks in; write succeeds if other 2 ack first. |
| Node crashes mid-write | WAL has the entry up to the last `fsync`. Anything past is torn → discarded on recovery. |
| Node restart | Replay snapshot + WAL. Coordinated read triggers read-repair for any updates missed while down. |
| Two clients write same key concurrently | Higher (timestamp, node_id) wins. Other write is dropped. |
| Backup during heavy writes | Snapshot taken under microsecond lock; WAL tail captured up to first torn line. No completed write is lost. |

---

## Pluggability summary

| Axis | Interface | Default impl | Easy to add |
|---|---|---|---|
| Storage | `Storage` | `WalSnapshotStorage` | RocksDB, LMDB, plain SQLite |
| Transport | `Transport` | `InProcessTransport` | HTTP, gRPC |
| Partitioner | `Partitioner` | `ConsistentHashPartitioner(rf=3)` | Range-based, jump hash |
| Replicator | `Replicator` | `QuorumReplicator(N=3,W=2,R=2)` | Raft, primary-backup |
| Conflict resolver | `ConflictResolver` | `LastWriteWinsResolver` | Vector clocks, CRDTs |
| Backup | `BackupStrategy` | `WalCheckpointBackup` | Fork-style, copy-on-write |

Each is injected as a kwarg into `KVStore(...)`. None of the layers reach
across — e.g. the `Replicator` doesn't know whether storage is in-memory or
WAL-backed, the `Backup` doesn't know about replication.
