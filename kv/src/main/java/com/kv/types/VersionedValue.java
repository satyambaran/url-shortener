package com.kv.types;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A value plus the metadata needed to resolve concurrent writes.
 * <p>
 * {@code timestamp} is a monotonic-ish wall clock in nanoseconds.
 * {@code nodeId} is the tiebreaker so two writes that land in the same
 * nanosecond are still deterministically ordered across the cluster
 * (last-write-wins).
 * <p>
 * {@code tombstone} lets us represent deletes as a write (so deletes also
 * replicate and survive a stale node coming back online with a "resurrected"
 * value).
 * <p>
 * {@code gcGraceMillis} is the per-tombstone grace period: a tombstone may only
 * be physically dropped (compacted) by a future GC pass once
 * {@code now > timestamp + gcGraceMillis}. The grace period must be at least as
 * long as the worst-case "stale replica recovery" window; dropping a tombstone
 * before every replica has seen it lets a stale replica resurrect the deleted
 * key on the next read-repair (the classic Cassandra "zombie data" failure).
 * Compaction is not implemented in this code; the field is recorded so a future
 * compactor can use it correctly.
 * <p>
 * Implemented as a Java record: immutable, value-equal, free toString. The
 * pluggable conflict resolver (see {@code conflict} package) is the actual
 * decision-maker for "should this overwrite the existing value"; the
 * {@code isNewerThan} method on this type is just a helper used by the default
 * LWW resolver, not the system-wide pluggability point.
 */
public record VersionedValue(Object value, long timestamp, String nodeId, boolean tombstone, long gcGraceMillis) {
    /**
     * Default tombstone grace period: 7 days (matches Cassandra's default
     * {@code gc_grace_seconds=864000} → 10 days, conservatively shorter here). Long
     * enough for a node to be down + recover via WAL/snapshot before its missed
     * deletes are physically purged from the cluster.
     */
    public static final long DEFAULT_TOMBSTONE_GRACE_MILLIS = 7L * 24 * 3600 * 1000;

    public VersionedValue {
        Objects.requireNonNull(nodeId, "nodeId");
    }

    public static VersionedValue of(Object value, long timestamp, String nodeId) {
        return new VersionedValue(value, timestamp, nodeId, false, 0L);
    }

    public static VersionedValue tombstone(long timestamp, String nodeId) {
        return tombstone(timestamp, nodeId, DEFAULT_TOMBSTONE_GRACE_MILLIS);
    }

    public static VersionedValue tombstone(long timestamp, String nodeId, long gcGraceMillis) {
        return new VersionedValue(null, timestamp, nodeId, true, gcGraceMillis);
    }

    /**
     * True if this is a tombstone whose grace period has elapsed and may be safely
     * garbage-collected. Non-tombstones return false. Caller supplies
     * {@code nowMillis} so tests can use a deterministic clock.
     */
    public boolean isExpiredTombstone(long nowMillis) {
        if (!tombstone)
            return false;
        long createdMillis = timestamp / 1_000_000L;
        return nowMillis > createdMillis + gcGraceMillis;
    }

    /**
     * Last-write-wins comparison helper. {@code other == null} means "we are
     * newer".
     * <p>
     * NOTE: this is the LWW-specific comparison used by
     * {@link com.kv.conflict.LastWriteWinsResolver}. Other layers (storage,
     * replicator) MUST go through the pluggable
     * {@link com.kv.conflict.ConflictResolver#shouldReplace} instead so that
     * swapping in a non-LWW resolver (vector clocks, CRDTs, Raft) actually changes
     * cluster-wide behavior.
     */
    public boolean isNewerThan(VersionedValue other) {
        if (other == null)
            return true;
        if (this.timestamp != other.timestamp) {
            return this.timestamp > other.timestamp;
        }
        // Deterministic tiebreaker: lexicographically larger nodeId wins.
        return this.nodeId.compareTo(other.nodeId) > 0;
    }

    /**
     * JSON-friendly map for transport / WAL serialization. We use LinkedHashMap so
     * the on-disk JSON has a stable key order (helps when diffing logs).
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("timestamp", timestamp);
        m.put("node_id", nodeId);
        m.put("tombstone", tombstone);
        m.put("gc_grace_millis", gcGraceMillis);
        return m;
    }

    /**
     * Inverse of {@link #toMap()}. Returns null on a null input (mirrors Python).
     * Older payloads without {@code gc_grace_millis} get the default grace period
     * if they're tombstones, 0 otherwise.
     */
    @SuppressWarnings("unchecked")
    public static VersionedValue fromMap(Object raw) {
        if (raw == null)
            return null;
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("VersionedValue.fromMap expected Map, got " + raw.getClass());
        }
        Map<String, Object> m = (Map<String, Object>) rawMap;
        Object v = m.get("value");
        long ts = ((Number) m.get("timestamp")).longValue();
        String nid = (String) m.get("node_id");
        boolean tomb = Boolean.TRUE.equals(m.get("tombstone"));
        Number grace = (Number) m.get("gc_grace_millis");
        long gcMillis = grace != null ? grace.longValue() : (tomb ? DEFAULT_TOMBSTONE_GRACE_MILLIS : 0L);
        return new VersionedValue(v, ts, nid, tomb, gcMillis);
    }
}
