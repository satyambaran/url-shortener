package com.kv;

import com.kv.conflict.LastWriteWinsResolver;
import com.kv.storage.WalSnapshotStorage;
import com.kv.transport.InProcessTransport;
import com.kv.types.MessageActions;
import com.kv.types.VersionedValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end demo. Direct port of {@code python/demo.py}.
 *
 * <ol>
 * <li>3 nodes start fresh, share an in-process Transport, all writes/reads
 * succeed.</li>
 * <li>Kill node3 mid-flight. Writes still succeed (W=2 of 3).</li>
 * <li>Restart node3 from disk. WAL replay restores its previous data.</li>
 * <li>Read repair pushes the missed updates on the next coordinated read.</li>
 * <li>Launch a writer thread, take a live backup. Restore + verify.</li>
 * </ol>
 */
public final class Demo {
    private static final Path DATA_ROOT = Path.of(".kv_data_demo");
    private static final Path BACKUP_DIR = Path.of(".kv_backup_demo");

    public static void main(String[] args) throws Exception {
        freshDirs();
        InProcessTransport transport = new InProcessTransport();
        header("1. Bootstrap a 3-node cluster sharing one in-process transport");
        KVStore node1 = makeNode("node1", List.of("node2", "node3"), transport);
        KVStore node2 = makeNode("node2", List.of("node1", "node3"), transport);
        KVStore node3 = makeNode("node3", List.of("node1", "node2"), transport);
        node1.put("user:1", Map.of("name", "Alice", "email", "alice@example.com"));
        node1.put("user:2", Map.of("name", "Bob"));
        expect("node1 GET user:1", Map.of("name", "Alice", "email", "alice@example.com"),
                node1.get("user:1").orElse(null));
        expect("node2 GET user:1 (replicated)", Map.of("name", "Alice", "email", "alice@example.com"),
                node2.get("user:1").orElse(null));
        expect("node3 GET user:2 (replicated)", Map.of("name", "Bob"), node3.get("user:2").orElse(null));
        header("2. Kill node3, keep writing -- W=2 of 3 still meets quorum");
        transport.markDown("node3");
        expect("put while node3 down (W=2 still met)", true, node1.put("user:1", Map.of("name", "Alice v2")));
        expect("node2 GET user:1", Map.of("name", "Alice v2"), node2.get("user:1").orElse(null));
        header("3. Restart node3 from disk (WAL replay) -- old data survives");
        node3.close();
        transport.unregister("node3");
        KVStore node3Restarted = makeNode("node3", List.of("node1", "node2"), transport);
        transport.markUp("node3");
        expect("node3 LOCAL view of user:1 right after restart (the OLD value -- it missed the write)",
                Map.of("name", "Alice", "email", "alice@example.com"),
                node3Restarted.storage().get("user:1").map(VersionedValue::value).orElse(null));
        header("4. A coordinated read triggers read-repair on the stale replica");
        expect("node1 GET user:1 (also pushes repair to node3)", Map.of("name", "Alice v2"),
                node1.get("user:1").orElse(null));
        Thread.sleep(50); // fire-and-forget repair settles
        expect("node3 LOCAL view of user:1 after read-repair", Map.of("name", "Alice v2"),
                node3Restarted.storage().get("user:1").map(VersionedValue::value).orElse(null));
        header("5. Two nodes down -- quorum cannot be met, fails cleanly (no partial writes visible)");
        transport.markDown("node2");
        transport.markDown("node3");
        expect("put fails when only 1/3 reachable (W=2 unreachable)", false, node1.put("blocked:1", "should-fail"));
        expect("get returns empty when only 1/3 reachable (R=2 unreachable)", null,
                node1.get("blocked:1").orElse(null));
        transport.markUp("node2");
        transport.markUp("node3");
        expect("put succeeds again once cluster is healthy", true, node1.put("blocked:1", "now-works"));
        expect("node2 GET blocked:1 sees the recovered write", "now-works", node2.get("blocked:1").orElse(null));
        header("6. Tombstone delete -- replicates like a put, defeats stale-replica resurrection");
        expect("put temp:1", true, node1.put("temp:1", "before-delete"));
        expect("node1 GET temp:1", "before-delete", node1.get("temp:1").orElse(null));
        expect("node2 GET temp:1", "before-delete", node2.get("temp:1").orElse(null));
        expect("node3 GET temp:1", "before-delete", node3Restarted.get("temp:1").orElse(null));
        expect("delete temp:1 (quorum write of a tombstone)", true, node1.delete("temp:1"));
        expect("node1 GET after delete -> empty", null, node1.get("temp:1").orElse(null));
        expect("node2 GET after delete -> empty", null, node2.get("temp:1").orElse(null));
        expect("node3 GET after delete -> empty", null, node3Restarted.get("temp:1").orElse(null));
        VersionedValue tomb = node1.storage().get("temp:1").orElse(null);
        expect("local storage retains a tombstone record (NOT physically absent)", true,
                tomb != null && tomb.tombstone());
        expect("tombstone payload is null", null, tomb == null ? "<missing>" : tomb.value());
        expect("tombstone carries the default GC grace (millis)", VersionedValue.DEFAULT_TOMBSTONE_GRACE_MILLIS,
                tomb == null ? -1L : tomb.gcGraceMillis());
        expect("a fresh tombstone is NOT yet expired (compactor must wait out the grace)", false,
                tomb != null && tomb.isExpiredTombstone(System.currentTimeMillis()));
        header("7. LWW conflict resolution determinism");
        LastWriteWinsResolver lww = new LastWriteWinsResolver();
        VersionedValue v_n1 = VersionedValue.of("from-node1", 5_000L, "node1");
        VersionedValue v_n2 = VersionedValue.of("from-node2", 5_000L, "node2");
        expect("same timestamp, lex-larger nodeId wins", "from-node2", lww.winner(List.of(v_n1, v_n2)).value());
        expect("order-independent (reverse input gives same answer)", "from-node2",
                lww.winner(List.of(v_n2, v_n1)).value());
        VersionedValue v_newer = VersionedValue.of("newer-from-n1", 9_999L, "node1");
        expect("higher timestamp beats lex-larger nodeId", "newer-from-n1", lww.winner(List.of(v_n2, v_newer)).value());
        expect("empty candidate list -> no winner", null, lww.winner(List.of()));
        expect("all-null candidates -> no winner", null,
                lww.winner(Arrays.asList((VersionedValue) null, (VersionedValue) null)));
        expect("shouldReplace agrees with winner (write-time pluggability check)", true, lww.shouldReplace(v_n1, v_n2));
        expect("shouldReplace rejects strictly older incoming", false, lww.shouldReplace(v_newer, v_n2));
        header("8. PING / sendToNode contract");
        Map<String, Object> ping = Map.of("action", MessageActions.PING);
        Map<String, Object> pong = node1.sendToNode("node2", ping);
        expect("PING returns ok=true", true, pong == null ? null : pong.get("ok"));
        expect("PING returns the target's nodeId", "node2", pong == null ? null : pong.get("node_id"));
        expect("send to unregistered target returns null (unreachable contract)", null,
                node1.sendToNode("ghost-not-registered", ping));
        Map<String, Object> bad = node1.sendToNode("node2", Map.of("action", "NOT_A_REAL_ACTION"));
        expect("unknown action gets ok=false response (not an exception)", false, bad == null ? null : bad.get("ok"));
        header("9. Live backup while writes keep coming");
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger writes = new AtomicInteger(0);
        Thread writer = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                node1.put("bg:" + (i % 50), Map.of("i", i, "ts", System.currentTimeMillis()));
                writes.incrementAndGet();
                i++;
            }
        }, "demo-writer");
        writer.setDaemon(true);
        writer.start();
        Thread.sleep(100); // let the writer spin up
        Path backupDest = BACKUP_DIR.resolve("node1");
        int writesBefore = writes.get();
        Map<String, Object> manifest = node1.backup(backupDest.toString());
        int writesAfter = writes.get();
        System.out.println("        writes-so-far before backup: " + writesBefore);
        System.out.println("        writes-so-far after  backup: " + writesAfter);
        System.out.println("        backup manifest: " + manifest);
        expect("manifest has frozen_lsn > 0", true, ((Number) manifest.get("frozen_lsn")).longValue() > 0);
        expect("max_lsn_in_backup >= frozen_lsn (WAL tail captured)", true, ((Number) manifest.get("max_lsn_in_backup"))
                .longValue() >= ((Number) manifest.get("frozen_lsn")).longValue());
        expect("writer kept making progress DURING backup (lock-free)", true, writesAfter > writesBefore);
        stop.set(true);
        writer.join();
        header("10. Restore the backup into a fresh storage and verify");
        Path restoreDir = BACKUP_DIR.resolve("restored");
        Files.createDirectories(restoreDir);
        Files.copy(backupDest.resolve("snapshot.json"), restoreDir.resolve("snapshot.json"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(backupDest.resolve("wal.log"), restoreDir.resolve("wal.log"), StandardCopyOption.REPLACE_EXISTING);
        try (WalSnapshotStorage restored = new WalSnapshotStorage(restoreDir.toString())) {
            expect("restored user:1 -> Alice v2", Map.of("name", "Alice v2"),
                    restored.get("user:1").map(VersionedValue::value).orElse(null));
            long bgCount = restored.items().keySet().stream().filter(k -> k.startsWith("bg:")).count();
            expect("restored backup contains all 50 distinct bg:* keys", 50L, bgCount);
        }
        header("11. CRC32 framing catches WAL corruption on replay");
        Path crcDir = DATA_ROOT.resolve("crc-test");
        Files.createDirectories(crcDir);
        try (WalSnapshotStorage s = new WalSnapshotStorage(crcDir.toString())) {
            s.put("ok:1", VersionedValue.of("first", 1_000_000_000L, "test-node"));
            s.put("ok:2", VersionedValue.of("second", 2_000_000_000L, "test-node"));
            s.put("ok:3", VersionedValue.of("third", 3_000_000_000L, "test-node"));
        }
        Path walFile = crcDir.resolve("wal.log");
        List<String> lines = new ArrayList<>(Files.readAllLines(walFile, StandardCharsets.UTF_8));
        // Flip one nibble of the CRC on the SECOND entry (ok:2). Replay must stop here.
        String victim = lines.get(1);
        int digitVal = Character.digit(victim.charAt(0), 16);
        char flipped = Character.forDigit((digitVal + 1) % 16, 16);
        lines.set(1, flipped + victim.substring(1));
        Files.write(walFile, lines, StandardCharsets.UTF_8);
        System.out.println("        flipped first hex digit of line 2's CRC (ok:2's entry)");
        try (WalSnapshotStorage s2 = new WalSnapshotStorage(crcDir.toString())) {
            expect("ok:1 survives (before the corruption boundary)", "first",
                    s2.get("ok:1").map(VersionedValue::value).orElse(null));
            expect("ok:2 dropped (replay halted exactly at the CRC mismatch)", null,
                    s2.get("ok:2").map(VersionedValue::value).orElse(null));
            expect("ok:3 dropped (past the corruption boundary -- never trusted)", null,
                    s2.get("ok:3").map(VersionedValue::value).orElse(null));
        }
        header("12. Backpressure under sustained latency (CallerRunsPolicy safety net)");
        transport.setLatencyMs(15);
        int bpThreads = 32;
        int bpPerThread = 20;
        AtomicInteger writeOk = new AtomicInteger();
        AtomicInteger writeFail = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(bpThreads);
        long t0 = System.nanoTime();
        for (int t = 0; t < bpThreads; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < bpPerThread; i++) {
                    if (node1.put("bp:t" + tid + ":i" + i, i)) {
                        writeOk.incrementAndGet();
                    } else {
                        writeFail.incrementAndGet();
                    }
                }
                done.countDown();
            }, "bp-" + t);
            th.setDaemon(true);
            th.start();
        }
        start.countDown();
        done.await();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        transport.setLatencyMs(0);
        expect("all puts acked under sustained 15ms artificial network latency", bpThreads * bpPerThread,
                writeOk.get());
        expect("zero failures -- pool overflow degrades latency, never drops writes", 0, writeFail.get());
        System.out.println("        " + bpThreads + " threads x " + bpPerThread
                + " puts under 15ms artificial latency took " + elapsedMs + "ms");
        System.out.println("        replicator pool=16, queue=128, fallback=CallerRunsPolicy");
        header("Cleanup");
        node1.close();
        node2.close();
        node3Restarted.close();
        System.out.println("done. all assertions passed.");
    }

    // ----- helpers --------------------------------------------------------- //
    private static KVStore makeNode(String id, List<String> peers, InProcessTransport t) {
        WalSnapshotStorage s = new WalSnapshotStorage(DATA_ROOT.resolve(id).toString());
        return KVStore.builder(id, peers).transport(t).storage(s).build();
    }

    private static void freshDirs() throws IOException {
        for (Path p : List.of(DATA_ROOT, BACKUP_DIR)) {
            if (Files.exists(p))
                deleteRecursively(p);
            Files.createDirectories(p);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p))
            return;
        try (var stream = Files.walk(p)) {
            stream.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static void header(String s) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println(s);
        System.out.println("=".repeat(70));
    }

    /**
     * Asserts {@code expected.equals(actual)} and prints both for the human reading
     * the demo output. Throws {@link AssertionError} on mismatch so a regression in
     * any layer fails the demo loudly instead of just printing a wrong number.
     */
    private static void expect(String label, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        String mark = ok ? " OK " : "FAIL";
        System.out.println("  [" + mark + "] " + label);
        System.out.println("        expected: " + expected);
        System.out.println("        actual:   " + actual);
        if (!ok) {
            throw new AssertionError(
                    "DEMO ASSERTION FAILED: " + label + " | expected=" + expected + " actual=" + actual);
        }
    }

    private Demo() {}
}
