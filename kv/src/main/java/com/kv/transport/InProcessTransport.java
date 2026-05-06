package com.kv.transport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared bus used by all nodes in the same JVM. Lets us spin up a 3-node
 * cluster inside one process and simulate node down / restart / slow-network
 * without sockets. Used by the demo and by the unit tests.
 *
 * <h3>Failure injection</h3>
 * <ul>
 * <li>{@link #markDown(String)} / {@link #markUp(String)} -- toggle node
 * availability.</li>
 * <li>{@link #setLatencyMs(int)} -- artificial sleep before delivery.</li>
 * <li>{@link #setDropRate(double)} -- probability send returns null even if
 * up.</li>
 * </ul>
 */
public class InProcessTransport implements Transport {
    private final Map<String, MessageHandler> handlers = new HashMap<>();
    private final Set<String> down = new HashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile int latencyMs = 0;
    private volatile double dropRate = 0.0;
    private final Random rng = new Random(0);

    @Override
    public void register(String nodeId, MessageHandler handler) {
        lock.lock();
        try {
            handlers.put(nodeId, handler);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unregister(String nodeId) {
        lock.lock();
        try {
            handlers.remove(nodeId);
        } finally {
            lock.unlock();
        }
    }

    public void markDown(String nodeId) {
        lock.lock();
        try {
            down.add(nodeId);
        } finally {
            lock.unlock();
        }
    }

    public void markUp(String nodeId) {
        lock.lock();
        try {
            down.remove(nodeId);
        } finally {
            lock.unlock();
        }
    }

    public void setLatencyMs(int ms) { this.latencyMs = Math.max(0, ms); }

    public void setDropRate(double rate) { this.dropRate = Math.max(0.0, Math.min(1.0, rate)); }

    @Override
    public Map<String, Object> send(String targetNode, Map<String, Object> message, String fromNode) {
        // Snapshot state under lock so we don't race with mark_down/up.
        MessageHandler handler;
        int latency;
        double drop;
        lock.lock();
        try {
            if (down.contains(targetNode))
                return null;
            handler = handlers.get(targetNode);
            if (handler == null)
                return null; // not registered = effectively down
            latency = this.latencyMs;
            drop = this.dropRate;
        } finally {
            lock.unlock();
        }
        if (drop > 0) {
            double r;
            synchronized (rng) {
                r = rng.nextDouble();
            }
            if (r < drop)
                return null;
        }
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        try {
            return handler.handle(fromNode, message);
        } catch (RuntimeException e) {
            // A peer-side exception is indistinguishable from a network error
            // to the caller. Returning null lets the quorum logic treat it as
            // an unreachable replica.
            return null;
        }
    }
}
