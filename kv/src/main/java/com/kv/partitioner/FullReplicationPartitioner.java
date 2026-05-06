package com.kv.partitioner;

import java.util.ArrayList;
import java.util.List;

/**
 * Every node holds every key. With a 3-node cluster + RF=3 this is what
 * consistent hashing degenerates to anyway. Useful for clarity and tests.
 */
public class FullReplicationPartitioner implements Partitioner {
    private final List<String> nodes;

    public FullReplicationPartitioner(List<String> nodes) { this.nodes = new ArrayList<>(nodes); }

    @Override
    public List<String> replicasFor(String key) { return new ArrayList<>(nodes); }

    @Override
    public List<String> allNodes() { return new ArrayList<>(nodes); }
}
