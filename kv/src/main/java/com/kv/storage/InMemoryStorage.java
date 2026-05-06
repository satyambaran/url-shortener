package com.kv.storage;

import com.kv.conflict.ConflictResolver;
import com.kv.conflict.LastWriteWinsResolver;
import com.kv.types.VersionedValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure RAM storage. No durability. Used by tests directly and serves as the hot
 * in-memory state inside {@link WalSnapshotStorage}.
 *
 * <p>
 * The "should I overwrite?" decision is delegated to the injected
 * {@link ConflictResolver} (default: LWW), so swapping the resolver at the
 * {@code KVStore} level changes the storage's accept/reject behavior too.
 */
public class InMemoryStorage implements Storage {
    private final Map<String, VersionedValue> data = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ConflictResolver resolver;
    private long lsn = 0;

    public InMemoryStorage() { this(new LastWriteWinsResolver()); }

    public InMemoryStorage(ConflictResolver resolver) {
        this.resolver = resolver != null ? resolver : new LastWriteWinsResolver();
    }

    @Override
    public Optional<VersionedValue> get(String key) {
        lock.lock();
        try {
            return Optional.ofNullable(data.get(key));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long put(String key, VersionedValue vv) {
        lock.lock();
        try {
            VersionedValue existing = data.get(key);
            // Idempotent + pluggable conflict guard: only accept if the
            // resolver picks the incoming value. Makes replays (WAL replay,
            // replication retries) safe AND swappable to non-LWW semantics.
            if (resolver.shouldReplace(existing, vv)) {
                data.put(key, vv);
            }
            lsn += 1;
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<String, VersionedValue> items() {
        lock.lock();
        try {
            return new LinkedHashMap<>(data);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long currentLsn() {
        lock.lock();
        try {
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        // no-op
    }

    // --- package-private helpers used by WalSnapshotStorage during recovery ---
    void putRaw(String key, VersionedValue vv) {
        lock.lock();
        try {
            data.put(key, vv);
        } finally {
            lock.unlock();
        }
    }

    void setLsn(long n) {
        lock.lock();
        try {
            lsn = n;
        } finally {
            lock.unlock();
        }
    }
}
