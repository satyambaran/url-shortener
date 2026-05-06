package com.kv.conflict;

import com.kv.types.VersionedValue;

/**
 * Pick the value with the highest (timestamp, nodeId).
 *
 * <p>
 * Pros: trivially simple, no per-key metadata beyond a timestamp.
 * <p>
 * Cons: depends on roughly-synchronized clocks; concurrent writes silently drop
 * one of the values. For a take-home this is the right tradeoff; in production
 * you'd reach for vector clocks + sibling resolution (Dynamo) or a consensus
 * log (Raft).
 */
public class LastWriteWinsResolver implements ConflictResolver {
    @Override
    public VersionedValue winner(Iterable<VersionedValue> candidates) {
        VersionedValue best = null;
        for (VersionedValue c : candidates) {
            if (c == null)
                continue;
            if (best == null || c.isNewerThan(best)) {
                best = c;
            }
        }
        return best;
    }
}
