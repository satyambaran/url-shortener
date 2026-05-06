package com.kv.partitioner;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standard hash ring with virtual nodes.
 *
 * <p>
 * Trade-offs vs. naive {@code hash%N}:
 * <ul>
 * <li>+ Adding/removing a node only moves ~1/N of keys (vs. ~all of them).</li>
 * <li>+ Virtual nodes smooth out hotspots from skewed key distributions.</li>
 * <li>- Slightly more complex; ring rebuild is O(N * vnodes).</li>
 * </ul>
 */
public class ConsistentHashPartitioner implements Partitioner {
    private final List<String> nodes;
    private final int rf;
    private final int vnodes;
    private final List<BigInteger> ring = new ArrayList<>(); // sorted hash positions
    private final Map<BigInteger, String> owner = new HashMap<>(); // hash position -> node_id

    public ConsistentHashPartitioner(List<String> nodes, int replicationFactor) { this(nodes, replicationFactor, 128); }

    public ConsistentHashPartitioner(List<String> nodes, int replicationFactor, int virtualNodes) {
        if (replicationFactor > nodes.size()) {
            throw new IllegalArgumentException("replicationFactor cannot exceed number of nodes");
        }
        this.nodes = new ArrayList<>(nodes);
        this.rf = replicationFactor;
        this.vnodes = virtualNodes;
        buildRing();
    }

    private static BigInteger hash(String s) {
        // MD5 is fine for partitioning (not security). 128 bits is plenty.
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new BigInteger(1, digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void buildRing() {
        for (String node : nodes) {
            for (int v = 0; v < vnodes; v++) {
                BigInteger h = hash(node + "#" + v);
                ring.add(h);
                owner.put(h, node);
            }
        }
        Collections.sort(ring);
    }

    @Override
    public List<String> replicasFor(String key) {
        if (ring.isEmpty())
            return List.of();
        BigInteger h = hash(key);
        // Find the first ring position > h (bisect_right semantics), wrap around.
        int idx = Collections.binarySearch(ring, h);
        if (idx >= 0) {
            // Exact match: bisect_right returns idx+1.
            idx = (idx + 1) % ring.size();
        } else {
            idx = (-idx - 1) % ring.size();
        }
        List<String> result = new ArrayList<>(rf);
        Set<String> seen = new HashSet<>();
        // Walk the ring until we have RF *distinct physical* nodes.
        for (int i = 0; i < ring.size() && result.size() < rf; i++) {
            BigInteger pos = ring.get((idx + i) % ring.size());
            String node = owner.get(pos);
            if (seen.add(node)) {
                result.add(node);
            }
        }
        return result;
    }

    @Override
    public List<String> allNodes() { return new ArrayList<>(nodes); }
}
