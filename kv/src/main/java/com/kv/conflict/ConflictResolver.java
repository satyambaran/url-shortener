package com.kv.conflict;

import com.kv.types.VersionedValue;

import java.util.List;

/**
 * Pluggable conflict resolution. Today: last-write-wins on (timestamp, nodeId).
 * Tomorrow could be vector clocks or CRDT merges -- the rest of the system
 * doesn't care because it only depends on this interface.
 *
 * <p>
 * Two entry points, both pluggable:
 * <ul>
 * <li>{@link #winner} is used at <em>read</em> time by the replicator to pick
 * across replica responses.</li>
 * <li>{@link #shouldReplace} is used at <em>write</em> time by storage to
 * decide whether to overwrite the existing value. It has a sensible default
 * built on {@code winner}, so implementations only need to override
 * {@code winner}.</li>
 * </ul>
 * Routing both reads and writes through the same resolver is what makes
 * conflict semantics actually pluggable end-to-end -- swapping in a vector
 * clock resolver changes both the storage LWW guard and the read-time pick.
 */
public interface ConflictResolver {
    /**
     * Pick the value the cluster should converge on. {@code null} entries are
     * skipped. Implementations MUST return one of the candidates by reference (no
     * synthesized values) so {@link #shouldReplace} can use identity comparison.
     */
    VersionedValue winner(Iterable<VersionedValue> candidates);

    /**
     * Should {@code incoming} overwrite {@code existing} in storage? Default
     * implementation defers to {@link #winner}: replace iff the resolver picks
     * {@code incoming} over {@code existing}. Override only if you want a fast path
     * (e.g. byte-equal short-circuit).
     */
    default boolean shouldReplace(VersionedValue existing, VersionedValue incoming) {
        if (incoming == null)
            return false;
        if (existing == null)
            return true;
        return winner(List.of(existing, incoming)) == incoming;
    }
}
