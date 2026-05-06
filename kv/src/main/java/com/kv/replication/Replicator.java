package com.kv.replication;

import com.kv.types.VersionedValue;

/**
 * Pluggable replication coordinator. Swap implementations to change consistency
 * model (quorum, leader-based, primary-backup, Raft, ...).
 */
public interface Replicator {
    /**
     * Coordinate a write to all replicas. Returns true once the write quorum is
     * met.
     */
    boolean write(String key, VersionedValue vv);

    /** Coordinate a read from replicas. Returns the resolved value or null. */
    VersionedValue read(String key);

    /** Free background resources (thread pools, etc.). */
    default void shutdown() {}
}
