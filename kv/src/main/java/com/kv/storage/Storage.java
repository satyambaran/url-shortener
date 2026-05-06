package com.kv.storage;

import com.kv.types.VersionedValue;

import java.util.Map;
import java.util.Optional;

/**
 * Pluggable durability layer. All methods must be thread-safe.
 * <p>
 * Implementations:
 * <ul>
 * <li>{@link InMemoryStorage} -- pure RAM. Tests + hot cache.</li>
 * <li>{@link WalSnapshotStorage} -- crash-safe (WAL + snapshot).</li>
 * </ul>
 * Future: a RocksDB / LMDB backed implementation would slot in here.
 */
public interface Storage extends AutoCloseable {
    /** Return the stored value (including tombstones) or empty if absent. */
    Optional<VersionedValue> get(String key);

    /**
     * Persist {@code vv} and return its assigned LSN (monotonically increasing).
     * Implementations must apply LWW: an older value than the current must not
     * overwrite, but the LSN counter still advances and (for durable backends) the
     * WAL still records the attempt so replays remain deterministic.
     */
    long put(String key, VersionedValue vv);

    /** Snapshot of (key, vv) pairs. Caller must not mutate the result. */
    Map<String, VersionedValue> items();

    /** The current monotonically-increasing log sequence number. */
    long currentLsn();

    @Override
    void close();
}
