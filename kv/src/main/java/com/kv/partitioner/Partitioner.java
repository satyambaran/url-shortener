package com.kv.partitioner;

import java.util.List;

/**
 * Pluggable strategy for "which nodes own which key".
 *
 * <p>
 * Implementations:
 * <ul>
 * <li>{@link FullReplicationPartitioner} -- every node holds every key.</li>
 * <li>{@link ConsistentHashPartitioner} -- hash ring with virtual nodes.</li>
 * </ul>
 */
public interface Partitioner {
    /**
     * Ordered list of node IDs that should hold {@code key}. The first element is
     * the "preferred coordinator" (not enforced -- any replica may coordinate).
     */
    List<String> replicasFor(String key);

    List<String> allNodes();
}
