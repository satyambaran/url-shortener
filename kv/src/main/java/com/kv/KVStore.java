package com.kv;

import com.kv.backup.BackupStrategy;
import com.kv.backup.WalCheckpointBackup;
import com.kv.conflict.ConflictResolver;
import com.kv.conflict.LastWriteWinsResolver;
import com.kv.partitioner.ConsistentHashPartitioner;
import com.kv.partitioner.Partitioner;
import com.kv.replication.QuorumReplicator;
import com.kv.replication.Replicator;
import com.kv.storage.Storage;
import com.kv.storage.WalSnapshotStorage;
import com.kv.transport.InProcessTransport;
import com.kv.transport.Transport;
import com.kv.types.MessageActions;
import com.kv.types.VersionedValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Distributed key-value store node. Direct port of
 * {@code python/kvstore/kvstore.py::KVStore}.
 * <p>
 * Wires together the pluggable layers:
 * <ul>
 * <li>{@link Storage} -- where data lives on this node (default:
 * WAL+snapshot)</li>
 * <li>{@link Partitioner} -- which nodes own which keys</li>
 * <li>{@link Transport} -- how nodes talk to each other</li>
 * <li>{@link Replicator} -- how a write/read fans out (default: quorum)</li>
 * <li>{@link BackupStrategy} -- how to take a live snapshot (default: WAL
 * checkpoint)</li>
 * </ul>
 *
 * <p>
 * Use {@link Builder} to override any of the above; defaults give you a
 * sensible production-shaped node.
 */
public class KVStore implements AutoCloseable {
    private final String nodeId;
    private final List<String> peerNodes;
    private final Transport transport;
    private final Storage storage;
    private final Partitioner partitioner;
    private final ConflictResolver resolver;
    private final Replicator replicator;
    private final BackupStrategy backupStrategy;
    private final ReentrantLock tsLock = new ReentrantLock();
    private long lastTs = 0;

    /**
     * Convenience: 3-node, in-process defaults using WAL+snapshot under
     * {@code ./.kv_data/<nodeId>}.
     */
    public KVStore(String nodeId, List<String> peerNodes, Transport transport) {
        this(builder(nodeId, peerNodes).transport(transport));
    }

    private KVStore(Builder b) {
        this.nodeId = b.nodeId;
        this.peerNodes = List.copyOf(b.peerNodes);
        List<String> allNodes = new ArrayList<>();
        allNodes.add(nodeId);
        allNodes.addAll(peerNodes);
        // ---- transport ----
        this.transport = b.transport != null ? b.transport : new InProcessTransport();
        this.transport.register(nodeId, this::onMessage);
        // ---- conflict resolver (built BEFORE storage so default storage can
        // share it; that's what makes the resolver pluggability actually
        // affect the storage-level accept/reject guard, not just the
        // read-time pick) ----
        this.resolver = b.resolver != null ? b.resolver : new LastWriteWinsResolver();
        // ---- storage ----
        if (b.storage != null) {
            this.storage = b.storage;
        } else {
            String dir = b.dataDir != null ? b.dataDir : Path.of(".kv_data", nodeId).toString();
            this.storage = new WalSnapshotStorage(dir, resolver);
        }
        // ---- partitioner ----
        this.partitioner = b.partitioner != null ? b.partitioner
                : new ConsistentHashPartitioner(allNodes, Math.min(b.replicationFactor, allNodes.size()));
        // ---- replicator ----
        this.replicator = b.replicator != null ? b.replicator
                : new QuorumReplicator(nodeId, partitioner, transport, this::localApply, storage::get, b.writeQuorum,
                        b.readQuorum, b.timeoutMillis, resolver);
        // ---- backup ----
        if (b.backupStrategy != null) {
            this.backupStrategy = b.backupStrategy;
        } else if (this.storage instanceof WalSnapshotStorage wal) {
            this.backupStrategy = new WalCheckpointBackup(wal);
        } else {
            this.backupStrategy = null;
        }
    }
    // ====================================================================== //
    // Public API (matches the take-home skeleton)
    // ====================================================================== //

    public Optional<Object> get(String key) {
        VersionedValue vv = replicator.read(key);
        if (vv == null || vv.tombstone())
            return Optional.empty();
        return Optional.ofNullable(vv.value());
    }

    public boolean put(String key, Object value) {
        VersionedValue vv = VersionedValue.of(value, nextTs(), nodeId);
        return replicator.write(key, vv);
    }

    /**
     * Tombstone-based delete. Replicates exactly like a put so a stale replica
     * coming back online cannot resurrect deleted keys (classic Dynamo pitfall).
     */
    public boolean delete(String key) {
        VersionedValue vv = VersionedValue.tombstone(nextTs(), nodeId);
        return replicator.write(key, vv);
    }

    /** Take-home contract method. Delegates to the pluggable transport. */
    public Map<String, Object> sendToNode(String targetNode, Map<String, Object> message) {
        return transport.send(targetNode, message, nodeId);
    }

    /**
     * Inbound dispatcher. Transport calls this when another node sends us a
     * message. Keep small + side-effect-only-via-storage so it stays correct under
     * concurrent requests.
     */
    public Map<String, Object> onMessage(String fromNode, Map<String, Object> message) {
        Object actionObj = message.get("action");
        String action = actionObj == null ? null : actionObj.toString();
        Map<String, Object> resp = new LinkedHashMap<>();
        if (MessageActions.PUT_REPLICA.equals(action)) {
            VersionedValue vv = VersionedValue.fromMap(message.get("vv"));
            if (vv == null) {
                resp.put("ok", false);
                resp.put("error", "bad payload");
                return resp;
            }
            localApply((String) message.get("key"), vv);
            resp.put("ok", true);
            return resp;
        }
        if (MessageActions.GET_REPLICA.equals(action)) {
            Optional<VersionedValue> vv = storage.get((String) message.get("key"));
            resp.put("ok", true);
            resp.put("vv", vv.map(VersionedValue::toMap).orElse(null));
            return resp;
        }
        if (MessageActions.READ_REPAIR.equals(action)) {
            VersionedValue vv = VersionedValue.fromMap(message.get("vv"));
            if (vv != null)
                localApply((String) message.get("key"), vv);
            resp.put("ok", true);
            return resp;
        }
        if (MessageActions.PING.equals(action)) {
            resp.put("ok", true);
            resp.put("node_id", nodeId);
            return resp;
        }
        resp.put("ok", false);
        resp.put("error", "unknown action " + action);
        return resp;
    }
    // ====================================================================== //
    // Operational
    // ====================================================================== //

    public Map<String, Object> backup(String destDir) {
        if (backupStrategy == null) {
            throw new IllegalStateException("No backup strategy configured for this storage");
        }
        return backupStrategy.backup(destDir);
    }

    @Override
    public void close() {
        try {
            replicator.shutdown();
        } finally {
            storage.close();
        }
    }

    // exposed for tests so they can inspect a node's local-only view
    public Storage storage() { return storage; }
    // ====================================================================== //
    // Internals
    // ====================================================================== //

    private void localApply(String key, VersionedValue vv) {
        // LWW guard happens inside InMemoryStorage.put; the WAL still records
        // the attempt so replays remain deterministic.
        storage.put(key, vv);
    }

    /**
     * Monotonic per-node timestamp in nanoseconds.
     * <p>
     * {@code Instant.now()} can stall (or even step backwards across NTP
     * corrections), so we clamp it to be strictly &gt; the previous value.
     * Cross-node ties are broken by nodeId in {@link VersionedValue}.
     */
    private long nextTs() {
        tsLock.lock();
        try {
            var now = java.time.Instant.now();
            long t = now.getEpochSecond() * 1_000_000_000L + now.getNano();
            if (t <= lastTs)
                t = lastTs + 1;
            lastTs = t;
            return t;
        } finally {
            tsLock.unlock();
        }
    }
    // ====================================================================== //
    // Builder
    // ====================================================================== //

    public static Builder builder(String nodeId, List<String> peerNodes) { return new Builder(nodeId, peerNodes); }

    public static final class Builder {
        private final String nodeId;
        private final List<String> peerNodes;
        private Transport transport;
        private Storage storage;
        private Partitioner partitioner;
        private Replicator replicator;
        private BackupStrategy backupStrategy;
        private ConflictResolver resolver;
        private String dataDir;
        private int writeQuorum = 2;
        private int readQuorum = 2;
        private int replicationFactor = 3;
        private long timeoutMillis = 2000;

        private Builder(String nodeId, List<String> peerNodes) {
            this.nodeId = nodeId;
            this.peerNodes = peerNodes;
        }

        public Builder transport(Transport t) {
            this.transport = t;
            return this;
        }

        public Builder storage(Storage s) {
            this.storage = s;
            return this;
        }

        public Builder partitioner(Partitioner p) {
            this.partitioner = p;
            return this;
        }

        public Builder replicator(Replicator r) {
            this.replicator = r;
            return this;
        }

        public Builder backupStrategy(BackupStrategy b) {
            this.backupStrategy = b;
            return this;
        }

        public Builder resolver(ConflictResolver r) {
            this.resolver = r;
            return this;
        }

        public Builder dataDir(String d) {
            this.dataDir = d;
            return this;
        }

        public Builder writeQuorum(int w) {
            this.writeQuorum = w;
            return this;
        }

        public Builder readQuorum(int r) {
            this.readQuorum = r;
            return this;
        }

        public Builder replicationFactor(int rf) {
            this.replicationFactor = rf;
            return this;
        }

        public Builder timeoutMillis(long t) {
            this.timeoutMillis = t;
            return this;
        }

        public KVStore build() { return new KVStore(this); }
    }
}
