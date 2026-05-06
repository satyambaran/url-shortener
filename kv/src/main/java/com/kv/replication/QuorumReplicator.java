package com.kv.replication;

import com.kv.conflict.ConflictResolver;
import com.kv.conflict.LastWriteWinsResolver;
import com.kv.partitioner.Partitioner;
import com.kv.transport.Transport;
import com.kv.types.MessageActions;
import com.kv.types.VersionedValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * N/W/R quorum coordinator. Direct port of
 * {@code python/kvstore/replication.py::QuorumReplicator}.
 *
 * <h3>Write</h3>
 * <ol>
 * <li>Stamp value (already done by caller).</li>
 * <li>Apply locally (synchronous, no network) if self is a replica.</li>
 * <li>Fan out PUT_REPLICA to remote replicas in parallel via the executor.</li>
 * <li>Return success once W total acks (local counts) are collected.</li>
 * <li>Stragglers continue in the background; eventual consistency is restored
 * by read-repair on the next read.</li>
 * </ol>
 *
 * <h3>Read</h3>
 * <ol>
 * <li>Fan out GET_REPLICA in parallel + read self.</li>
 * <li>Wait until R reachable responses are collected (or timeout).</li>
 * <li>Resolve conflict via the {@link ConflictResolver} (default: LWW).</li>
 * <li>Push the winner to any reachable-but-stale replica (read repair).</li>
 * </ol>
 *
 * <p>
 * This is a "sloppy quorum" minus hinted handoff; honest about its limits
 * (concurrent writes drop one via LWW). Documented in the README.
 */
public class QuorumReplicator implements Replicator {
    /** Local apply: write to *this node's* storage, bypassing the network. */
    public interface LocalApply {
        void apply(String key, VersionedValue vv);
    }

    /** Local read from *this node's* storage. */
    public interface LocalRead {
        Optional<VersionedValue> get(String key);
    }

    private final String nodeId;
    private final Partitioner partitioner;
    private final Transport transport;
    private final LocalApply localApply;
    private final LocalRead localRead;
    private final int W;
    private final int R;
    private final long timeoutMillis;
    private final ConflictResolver resolver;
    private final ExecutorService pool;

    public QuorumReplicator(String nodeId, Partitioner partitioner, Transport transport, LocalApply localApply,
            LocalRead localRead, int writeQuorum, int readQuorum, long timeoutMillis, ConflictResolver resolver) {
        this.nodeId = nodeId;
        this.partitioner = partitioner;
        this.transport = transport;
        this.localApply = localApply;
        this.localRead = localRead;
        this.W = writeQuorum;
        this.R = readQuorum;
        this.timeoutMillis = timeoutMillis;
        this.resolver = resolver != null ? resolver : new LastWriteWinsResolver();
        // Bounded pool + bounded queue + caller-runs rejection so a flood of
        // clients can't exhaust threads OR balloon the heap with queued tasks.
        // When all 16 workers are blocked on slow replicas AND the 128-slot
        // queue is full, submit() runs the task synchronously on the calling
        // thread. That degrades coordinator throughput (instead of OOMing) and
        // applies natural backpressure all the way back to the client.
        // Daemon threads so the pool doesn't block JVM exit.
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger idx = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "repl-" + nodeId + "-" + idx.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = new ThreadPoolExecutor(16, 16, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(128), tf,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    // ------------------------------------------------------------------ write //
    @Override
    public boolean write(String key, VersionedValue vv) {
        List<String> replicas = partitioner.replicasFor(key);
        if (replicas.size() < W) {
            // Cluster too small to ever satisfy the quorum -- fail fast.
            return false;
        }
        int acks = 0;
        List<Future<Boolean>> futures = new ArrayList<>();
        for (String r : replicas) {
            if (r.equals(nodeId)) {
                try {
                    localApply.apply(key, vv);
                    acks += 1;
                } catch (RuntimeException ignored) {
                    // disk full etc. -- caller sees false if acks < W
                }
            } else {
                final String target = r;
                futures.add(pool.submit((Callable<Boolean>) () -> sendPut(target, key, vv)));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Future<Boolean> fut : futures) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            boolean ok;
            try {
                ok = fut.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | InterruptedException | java.util.concurrent.ExecutionException e) {
                if (e instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                ok = false;
            }
            if (ok) {
                acks += 1;
                if (acks >= W) {
                    // Don't cancel outstanding futures -- let them complete in
                    // the background so replicas converge.
                    return true;
                }
            }
        }
        return acks >= W;
    }

    private boolean sendPut(String target, String key, VersionedValue vv) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("action", MessageActions.PUT_REPLICA);
        msg.put("key", key);
        msg.put("vv", vv.toMap());
        Map<String, Object> resp = transport.send(target, msg, nodeId);
        return resp != null && Boolean.TRUE.equals(resp.get("ok"));
    }

    // ------------------------------------------------------------------- read //
    /** Triple of (nodeId, reachable, value-or-null). */
    private record Resp(String nodeId, boolean reachable, VersionedValue vv) {
    }

    @Override
    public VersionedValue read(String key) {
        List<String> replicas = partitioner.replicasFor(key);
        if (replicas.isEmpty())
            return null;
        List<Resp> responses = new ArrayList<>(replicas.size());
        List<Map.Entry<String, Future<Resp>>> futures = new ArrayList<>();
        for (String r : replicas) {
            if (r.equals(nodeId)) {
                responses.add(new Resp(r, true, localRead.get(key).orElse(null)));
            } else {
                final String target = r;
                Future<Resp> f = pool.submit((Callable<Resp>) () -> sendGet(target, key));
                futures.add(Map.entry(r, f));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Map.Entry<String, Future<Resp>> e : futures) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            Resp resp;
            try {
                resp = e.getValue().get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | InterruptedException | java.util.concurrent.ExecutionException ex) {
                if (ex instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                resp = new Resp(e.getKey(), false, null);
            }
            responses.add(resp);
        }
        long reachableCount = responses.stream().filter(Resp::reachable).count();
        if (reachableCount < R) {
            return null; // couldn't reach enough replicas
        }
        List<VersionedValue> reachableValues = new ArrayList<>();
        for (Resp resp : responses)
            if (resp.reachable)
                reachableValues.add(resp.vv);
        VersionedValue winner = resolver.winner(reachableValues);
        // Read repair: anyone reachable whose value is stale gets the winner pushed.
        if (winner != null) {
            for (Resp resp : responses) {
                if (!resp.reachable)
                    continue;
                if (resp.vv == null || winner.isNewerThan(resp.vv)) {
                    if (resp.nodeId.equals(nodeId)) {
                        try {
                            localApply.apply(key, winner);
                        } catch (RuntimeException ignored) {
                        }
                    } else {
                        // Fire and forget. Failure is fine -- next read retries.
                        final String target = resp.nodeId;
                        pool.submit(() -> sendRepair(target, key, winner));
                    }
                }
            }
        }
        return winner;
    }

    private Resp sendGet(String target, String key) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("action", MessageActions.GET_REPLICA);
        msg.put("key", key);
        Map<String, Object> resp = transport.send(target, msg, nodeId);
        if (resp == null)
            return new Resp(target, false, null);
        VersionedValue vv = VersionedValue.fromMap(resp.get("vv"));
        return new Resp(target, true, vv);
    }

    private void sendRepair(String target, String key, VersionedValue vv) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("action", MessageActions.READ_REPAIR);
        msg.put("key", key);
        msg.put("vv", vv.toMap());
        transport.send(target, msg, nodeId);
    }

    @Override
    public void shutdown() { pool.shutdownNow(); }
}
