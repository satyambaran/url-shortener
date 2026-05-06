# Distributed Key-Value Store

Take-home implementation of a 3-node distributed KV store with quorum
replication, crash-safe persistence, and live backups. **Same design,
implemented in two languages**:

- [java/](src/main/java/com/kv) — Java 17 + Gradle + Jackson + JUnit 5  *(this folder)*
- [python/](python) — Python 3.11+ stdlib only

The Java implementation is a 1:1 translation of the Python original; pick
whichever you'd rather walk through in the interview.

---

## Run (Java)

```bash
gradle test           # 10 JUnit tests
gradle run            # end-to-end demo
```

## Run (Python)

```bash
cd python
python3 -m unittest tests.test_kvstore -v   # 10 tests
python3 demo.py                             # end-to-end demo
```

Both demos do the same thing: spin up 3 in-process nodes, kill one, restart it
from disk, watch read-repair fix it, then take a live backup while a writer
hammers the cluster.

---

## Java layout

```
src/main/java/com/kv/
  types/           VersionedValue (record), MessageActions
  storage/         Storage, InMemoryStorage, WalSnapshotStorage
  transport/       Transport, MessageHandler, InProcessTransport
  partitioner/     Partitioner, FullReplicationPartitioner, ConsistentHashPartitioner
  conflict/        ConflictResolver, LastWriteWinsResolver
  replication/     Replicator, QuorumReplicator
  backup/          BackupStrategy, WalCheckpointBackup
  KVStore.java     facade that wires it all together (with Builder)
  Demo.java        end-to-end demo
src/test/java/com/kv/
  KVStoreTest.java
build.gradle
settings.gradle
```

The Python layout under [python/](python) mirrors this module-for-module.

---

## Key design decisions (same in both impls)

### 0. Consistency model

**Per-key eventual consistency with last-write-wins conflict resolution.**
Quorum intersection (W=2, R=2, N=3 ⇒ W+R > N) ensures any successful read
overlaps with any prior successful write on at least one replica, so a read
is guaranteed to *see some version* from the latest acknowledged quorum
write. This is **NOT linearizability**:

- There is no single coordinator per key and no fencing token, so two
  concurrent coordinators can each succeed at writing different values; LWW
  picks one and the other is silently dropped.
- A read that hits the same R replicas as a prior write will see that write,
  but a read that hits a different overlapping subset (legal under sloppy
  quorum) may see an older value briefly until read-repair propagates the
  winner.
- Reads are not monotonic across coordinators: client A reading from node1
  then node2 can observe a value, then a strictly older value, in a narrow
  window before convergence.
- Writes are durable to W replicas before acknowledgement (W=2 of 3 here),
  so a single replica failure cannot lose an acked write.

The model is what production Dynamo-class systems (Cassandra, Riak) ship by
default. For linearizability you'd swap `QuorumReplicator` for a
Raft/Paxos-based `Replicator`; the rest of the system stays put.

### 1. Quorum replication (Dynamo-style: N=3, W=2, R=2)
- **Why:** No leader election. Tolerates 1 node down for both reads and writes.
  W+R > N gives the per-key consistency property described in §0 above
  without consensus overhead.
- **Cost:** Concurrent writes resolve by last-write-wins; the loser is silently
  dropped. Documented and intentional. For "I cannot lose any write" semantics
  you'd swap in a Raft-based replicator (the `Replicator` interface stays the same).
- **Failure model:** writes return `true` only after `W` replicas have acked.
  Stragglers continue in the background; if they never ack, eventual consistency
  is restored on the next read via **read-repair**.
- **Backpressure:** the replicator's executor is a 16-thread pool fronted by a
  bounded 128-slot queue with `CallerRunsPolicy`. When all workers are blocked
  on slow replicas and the queue fills, `submit()` runs the task synchronously
  on the calling (coordinator) thread. Coordinator throughput degrades instead
  of the heap blowing up — without this, a single slow node can cascade into
  OOM on the coordinator.

### 2. Consistent hashing for partitioning
- With 3 nodes and RF=3 this degenerates to full replication. The abstraction
  is still worth having: scale to 5 nodes tomorrow and only ~RF/N keys move.
- 128 virtual nodes / physical to smooth out hotspots from skewed key distributions.
- A `FullReplicationPartitioner` is also included.

### 3. WAL + periodic snapshots for persistence
- **Write path:** in-memory map update + append CRC32-framed entry to WAL +
  `fsync`. One disk sync per write, the cheapest durable primitive.
- **WAL framing:** every line is `<8-hex-CRC32> <JSON-payload>\n`. JSON-only
  framing is not enough: a torn write at the WAL tail can produce JSON that
  parses (truncated after a closing brace, missing fields silently null,
  partial overwrite of an old longer record). The CRC catches all such
  silent corruption; recovery stops at the first failed-CRC line. Same
  approach as etcd, PostgreSQL, RocksDB, Kafka.
- **Recovery:** load snapshot, replay WAL entries with `lsn > snapshot.lsn`.
  Bounded recovery time because snapshots compact old WAL.
- **Crash safety:** torn writes at WAL tail are detected by CRC mismatch (and
  treated as the recovery boundary). Snapshot file uses
  `write -> fsync -> atomic rename` so it's all-or-nothing.
- Java uses `RandomAccessFile("rwd")` for sync-on-write; Python uses `os.fsync`.

### 4. Live, lock-free backup via WAL checkpoint
This is the one I'd most want to walk through.

```
backup(dest):
    frozenLsn = storage.snapshot()         // 1. fast: map copy under lock,
                                           //    then slow disk I/O outside lock
    copy snapshot.json -> dest             // 2. snapshot is immutable until
                                           //    the next snapshot() call
    copy WAL entries lsn > frozenLsn       // 3. writers keep appending; we
                                           //    stop at first torn line / EOF
```

**Concurrent writes during the backup don't block and aren't lost** — every
write is either in the snapshot (lsn ≤ frozenLsn), in the copied WAL tail
(frozenLsn < lsn ≤ maxLsnInBackup), or in the next backup
(lsn > maxLsnInBackup). Demo confirms it: in the Java run the
write counter went 1067 → 1147 across the call while the manifest reported
`frozen_lsn=1074, wal_tail_entries=55, max_lsn_in_backup=1129`.

### 5. Last-write-wins on (timestamp, nodeId)
- Each write stamped with `Instant.now()` in nanos plus the originating
  `nodeId` as tiebreaker. Cluster-wide deterministic ordering even when
  clocks tie to the ns.
- Per-node monotonic clamp defends against NTP step-backs.
- Pluggable: the `ConflictResolver` interface drives BOTH the read-time
  pick (`winner`) AND the storage-level accept/reject guard
  (`shouldReplace`, default-implemented in terms of `winner`). Swapping in
  a vector-clock or CRDT resolver changes both call sites — storage no
  longer hardcodes `vv.isNewerThan(existing)`.

### 6. Tombstone-based delete with bounded GC grace
- Deletes are stamped puts with `tombstone=true`, replicated identically.
  This stops a stale-then-recovered replica from "resurrecting" deleted keys
  (the classic Dynamo pitfall).
- Each tombstone carries `gc_grace_millis` (default 7 days). A future
  compactor MAY physically purge a tombstone only after
  `now > timestamp + gc_grace_millis`. The tradeoff is sharp:
  - **GC before all replicas have seen the tombstone** → a stale replica
    coming back online still has the live value, no tombstone exists to
    suppress it, read-repair pushes the resurrected value cluster-wide.
  - **GC after the grace period** → safe, because the grace period must be
    longer than the worst-case replica downtime + recovery window.
- Compaction itself is not implemented in this code; the field is recorded
  on every tombstone so a future compactor uses it correctly.

### 7. Pluggable transport
- Take-home contract `sendToNode(target, message) → Map`/`null` is preserved.
  Behind it lives a `Transport` interface with `InProcessTransport` so tests
  and demo run in one JVM. HTTP/gRPC versions implement the same interface.
- `InProcessTransport` includes fault injection (`markDown`, `setLatencyMs`,
  `setDropRate`) used by the demo and tests.

---

## What this code is honest about NOT solving

- **No hinted handoff.** A write that misses a down replica is not queued for
  retry; we rely on read-repair instead. Cheap to add later.
- **Membership is static.** Peer list is fixed at construction.
- **Concurrent writes silently drop one value** (LWW, by design).
- **Not linearizable** (see §0 above for the precise model).
- **No client-visible read-your-writes guarantee** unless you read from the
  coordinator that wrote.
- **Backup is per-node.** Cluster-wide consistent backup would need
  Chandy-Lamport. Per-node is acceptable because every key has RF replicas.
- **No tombstone compaction.** The `gc_grace_millis` field is stored but
  there is no background pass that physically purges expired tombstones.
  Storage grows with delete count until you implement one.
- **InProcessTransport runs the receiver inline.** Real network transports
  are async; the `Replicator` already uses an `ExecutorService` so the call
  shape is correct, but timing differs.
- **Snapshots are only triggered manually** (from `backup()`). Production
  would add a background compactor on size/time thresholds.

---

## Failure scenarios

| Scenario | What happens |
|---|---|
| 1 node down | Writes (W=2) and reads (R=2) still succeed via the other 2. |
| 2 nodes down | `put` returns `false`. `get` returns empty. |
| Slow node | `timeoutMillis` (default 2000) kicks in; write succeeds if other 2 ack first. |
| Many slow nodes saturate executor | Bounded queue + caller-runs policy applies backpressure on the coordinator thread instead of OOMing. |
| Node crashes mid-write | WAL has framed entries up to the last `fsync`. Anything past is torn → CRC mismatch → discarded on recovery. |
| Silently corrupt WAL bytes | CRC32 mismatch stops replay at the corruption boundary; everything before is intact. |
| Node restart | Replay snapshot + WAL. Coordinated read triggers read-repair for any updates missed while down. |
| Two clients write same key concurrently | Higher (timestamp, nodeId) wins under default LWW. Other write is dropped. |
| Delete then stale replica recovers | Tombstone replicates like a put; LWW ensures the deletion wins. Tombstone retained for `gc_grace_millis`. |
| Backup during heavy writes | Snapshot taken under microsecond lock; WAL tail captured up to first torn line. No completed write is lost. |

---

## Pluggability summary

| Axis | Interface | Default impl | Easy to add |
|---|---|---|---|
| Storage | `Storage` | `WalSnapshotStorage` | RocksDB, LMDB |
| Transport | `Transport` | `InProcessTransport` | HTTP, gRPC |
| Partitioner | `Partitioner` | `ConsistentHashPartitioner(rf=3)` | Range-based, jump hash |
| Replicator | `Replicator` | `QuorumReplicator(W=2,R=2)` | Raft, primary-backup |
| Conflict resolver | `ConflictResolver` | `LastWriteWinsResolver` | Vector clocks, CRDTs |
| Backup | `BackupStrategy` | `WalCheckpointBackup` | Fork-style, copy-on-write |

Each is a constructor / `KVStore.builder().xxx(...)` injection. Layers don't
reach across — e.g. the `Replicator` doesn't know whether storage is in-memory
or WAL-backed; the backup doesn't know about replication.
