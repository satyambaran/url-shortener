package com.kv;

import com.kv.storage.InMemoryStorage;
import com.kv.storage.Storage;
import com.kv.storage.WalSnapshotStorage;
import com.kv.transport.InProcessTransport;
import com.kv.types.VersionedValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the distributed KV store. Mirror of
 * {@code python/tests/test_kvstore.py}.
 *
 * Covers:
 * <ul>
 * <li>basic put/get, replication across all nodes</li>
 * <li>quorum tolerates 1 node down, fails when 2 are down</li>
 * <li>persistence across restart (WAL replay)</li>
 * <li>LWW resolves concurrent writes deterministically</li>
 * <li>read repair fixes stale replicas</li>
 * <li>live backup is consistent (no completed write lost)</li>
 * </ul>
 */
class KVStoreTest {
    private static final Path TMP_ROOT = Path.of(".kv_test_data");

    @BeforeEach
    void wipe() throws IOException {
        deleteRecursively(TMP_ROOT);
        Files.createDirectories(TMP_ROOT);
    }

    @AfterEach
    void cleanup() throws IOException { deleteRecursively(TMP_ROOT); }

    private record Cluster(InProcessTransport transport, List<KVStore> nodes) {
        void closeAll() {
            for (KVStore n : nodes)
                n.close();
        }
    }

    private Cluster makeCluster(boolean useDisk) {
        InProcessTransport transport = new InProcessTransport();
        List<String> ids = List.of("node1", "node2", "node3");
        List<KVStore> nodes = new ArrayList<>();
        for (String id : ids) {
            List<String> peers = ids.stream().filter(p -> !p.equals(id)).toList();
            Storage s = useDisk ? new WalSnapshotStorage(TMP_ROOT.resolve(id).toString()) : new InMemoryStorage();
            nodes.add(KVStore.builder(id, peers).transport(transport).storage(s).build());
        }
        return new Cluster(transport, nodes);
    }

    // ----- basic ----------------------------------------------------------- //
    @Test
    void putGetRoundtrip() {
        Cluster c = makeCluster(false);
        try {
            assertTrue(c.nodes.get(0).put("k", "v"));
            for (KVStore n : c.nodes) {
                assertEquals(Optional.of("v"), n.get("k"));
            }
        } finally {
            c.closeAll();
        }
    }

    @Test
    void getMissingReturnsEmpty() {
        Cluster c = makeCluster(false);
        try {
            assertTrue(c.nodes.get(0).get("nope").isEmpty());
        } finally {
            c.closeAll();
        }
    }

    @Test
    void deleteTombstone() {
        Cluster c = makeCluster(false);
        try {
            KVStore n1 = c.nodes.get(0);
            n1.put("k", "v");
            assertEquals(Optional.of("v"), n1.get("k"));
            n1.delete("k");
            for (KVStore n : c.nodes) {
                assertTrue(n.get("k").isEmpty());
            }
        } finally {
            c.closeAll();
        }
    }

    // ----- fault tolerance ------------------------------------------------- //
    @Test
    void oneNodeDownWritesStillSucceed() {
        Cluster c = makeCluster(false);
        try {
            c.transport.markDown("node3");
            assertTrue(c.nodes.get(0).put("k", "v"));
            assertEquals(Optional.of("v"), c.nodes.get(1).get("k"));
        } finally {
            c.closeAll();
        }
    }

    @Test
    void twoNodesDownWritesFail() {
        Cluster c = makeCluster(false);
        try {
            c.transport.markDown("node2");
            c.transport.markDown("node3");
            // W=2 needed but only 1 reachable replica (self) -> fail.
            assertFalse(c.nodes.get(0).put("k", "v"));
        } finally {
            c.closeAll();
        }
    }

    @Test
    void persistenceAcrossRestart() {
        Cluster c = makeCluster(true);
        c.nodes.get(0).put("k", "v1");
        c.closeAll();
        // Bring them back up with the SAME data dirs and a fresh transport.
        InProcessTransport transport2 = new InProcessTransport();
        List<String> ids = List.of("node1", "node2", "node3");
        List<KVStore> nodes2 = new ArrayList<>();
        try {
            for (String id : ids) {
                List<String> peers = ids.stream().filter(p -> !p.equals(id)).toList();
                Storage s = new WalSnapshotStorage(TMP_ROOT.resolve(id).toString());
                nodes2.add(KVStore.builder(id, peers).transport(transport2).storage(s).build());
            }
            assertEquals(Optional.of("v1"), nodes2.get(0).get("k"));
        } finally {
            for (KVStore n : nodes2)
                n.close();
        }
    }

    // ----- conflict + repair ---------------------------------------------- //
    @Test
    void lwwHigherTimestampWins() {
        VersionedValue old = VersionedValue.of("old", 10, "n1");
        VersionedValue neu = VersionedValue.of("new", 20, "n1");
        assertTrue(neu.isNewerThan(old));
        assertFalse(old.isNewerThan(neu));
    }

    @Test
    void lwwNodeIdBreaksTie() {
        VersionedValue a = VersionedValue.of("a", 10, "node1");
        VersionedValue b = VersionedValue.of("b", 10, "node2");
        assertTrue(b.isNewerThan(a));
        assertFalse(a.isNewerThan(b));
    }

    @Test
    void readRepairFixesStaleReplica() throws InterruptedException {
        Cluster c = makeCluster(false);
        try {
            KVStore n1 = c.nodes.get(0);
            KVStore n3 = c.nodes.get(2);
            // Take node3 down, do a write that misses it.
            c.transport.markDown("node3");
            assertTrue(n1.put("k", "v_new"));
            c.transport.markUp("node3");
            // node3 is stale right now.
            assertTrue(n3.storage().get("k").isEmpty());
            // Coordinated read should repair node3.
            assertEquals(Optional.of("v_new"), n1.get("k"));
            Thread.sleep(50); // repair is fire-and-forget
            Optional<VersionedValue> repaired = n3.storage().get("k");
            assertTrue(repaired.isPresent(), "node3 should have been repaired");
            assertEquals("v_new", repaired.get().value());
        } finally {
            c.closeAll();
        }
    }

    // ----- backup ---------------------------------------------------------- //
    @Test
    void liveBackupNoLostCompletedWrites() throws InterruptedException, IOException {
        Cluster c = makeCluster(true);
        try {
            KVStore n1 = c.nodes.get(0);
            for (int i = 0; i < 100; i++) {
                assertTrue(n1.put("k" + i, i));
            }
            AtomicBoolean stop = new AtomicBoolean(false);
            Thread hammer = new Thread(() -> {
                int i = 100;
                while (!stop.get()) {
                    n1.put("k" + i, i);
                    i++;
                }
            });
            hammer.setDaemon(true);
            hammer.start();
            Thread.sleep(50);
            Path backupDir = TMP_ROOT.resolve("backup_n1");
            var manifest = n1.backup(backupDir.toString());
            stop.set(true);
            hammer.join();
            assertTrue(((Number) manifest.get("frozen_lsn")).longValue() > 0);
            // Restore as if recovering on a fresh disk.
            Path restoreDir = TMP_ROOT.resolve("restored");
            Files.createDirectories(restoreDir);
            Files.copy(backupDir.resolve("snapshot.json"), restoreDir.resolve("snapshot.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(backupDir.resolve("wal.log"), restoreDir.resolve("wal.log"),
                    StandardCopyOption.REPLACE_EXISTING);
            try (WalSnapshotStorage restored = new WalSnapshotStorage(restoreDir.toString())) {
                for (int i = 0; i < 100; i++) {
                    Optional<VersionedValue> v = restored.get("k" + i);
                    assertTrue(v.isPresent(), "k" + i + " missing from backup");
                    assertEquals(i, ((Number) v.get().value()).intValue());
                }
            }
        } finally {
            c.closeAll();
        }
    }

    // ----- helpers --------------------------------------------------------- //
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
}
