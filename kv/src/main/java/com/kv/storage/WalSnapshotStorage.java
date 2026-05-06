package com.kv.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kv.conflict.ConflictResolver;
import com.kv.conflict.LastWriteWinsResolver;
import com.kv.types.VersionedValue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-safe storage. Direct port of
 * {@code python/kvstore/storage.py::WalSnapshotStorage}.
 * <p>
 * On-disk layout under {@code dataDir}:
 * 
 * <pre>
 *   snapshot.json   last full snapshot {lsn, data: {key: vv}}
 *   wal.log         newline-delimited JSON entries: {lsn, key, vv}
 * </pre>
 *
 * <h3>Write path ({@link #put})</h3>
 * <ol>
 * <li>Acquire lock, assign next LSN.</li>
 * <li>Update in-memory dict (LWW guarded).</li>
 * <li>Append entry to WAL, flush + fsync. <-- durability boundary</li>
 * <li>Return LSN.</li>
 * </ol>
 *
 * <h3>Snapshot ({@link #snapshot}, also used by backup)</h3> Hot path is a
 * LinkedHashMap copy under lock (microseconds). The slow disk write happens
 * outside the lock so concurrent writers are NOT blocked.
 */
public class WalSnapshotStorage implements Storage {
    public static final String SNAPSHOT_FILE = "snapshot.json";
    public static final String WAL_FILE = "wal.log";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final boolean fsyncOnWrite;
    private final InMemoryStorage mem;
    private final ReentrantLock lock = new ReentrantLock();
    private final Path walPath;
    private final Path snapPath;
    private long lsn = 0;
    private RandomAccessFile walRaf;

    public WalSnapshotStorage(String dataDir) { this(Path.of(dataDir), true, new LastWriteWinsResolver()); }

    public WalSnapshotStorage(String dataDir, ConflictResolver resolver) { this(Path.of(dataDir), true, resolver); }

    public WalSnapshotStorage(Path dataDir, boolean fsyncOnWrite) {
        this(dataDir, fsyncOnWrite, new LastWriteWinsResolver());
    }

    public WalSnapshotStorage(Path dataDir, boolean fsyncOnWrite, ConflictResolver resolver) {
        this.fsyncOnWrite = fsyncOnWrite;
        this.mem = new InMemoryStorage(resolver);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.walPath = dataDir.resolve(WAL_FILE);
        this.snapPath = dataDir.resolve(SNAPSHOT_FILE);
        recover();
        try {
            // Ensure file exists, then open for append + fsync.
            if (!Files.exists(walPath)) {
                Files.createFile(walPath);
            }
            this.walRaf = new RandomAccessFile(walPath.toFile(), "rwd"); // 'd' = sync data writes
            this.walRaf.seek(this.walRaf.length());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    // ----- public API ------------------------------------------------------ //

    @Override
    public Optional<VersionedValue> get(String key) { return mem.get(key); }

    @Override
    public long put(String key, VersionedValue vv) {
        lock.lock();
        try {
            lsn += 1;
            long thisLsn = lsn;
            // Apply in-memory first (LWW guarded inside InMemoryStorage).
            mem.put(key, vv);
            // Always log the attempt, even if the resolver rejected it. The WAL
            // is the system of record; on replay we re-apply the resolver.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lsn", thisLsn);
            entry.put("key", key);
            entry.put("vv", vv.toMap());
            // CRC32-framed line. Catches torn writes that JSON parsing alone
            // would miss (truncated payload that still happens to parse).
            String line = WalCodec.encode(entry);
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            walRaf.write(bytes);
            if (fsyncOnWrite) {
                walRaf.getFD().sync();
            }
            return thisLsn;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<String, VersionedValue> items() { return mem.items(); }

    @Override
    public long currentLsn() {
        lock.lock();
        try {
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (walRaf != null) {
                try {
                    if (fsyncOnWrite)
                        walRaf.getFD().sync();
                } catch (IOException ignored) {
                    // best-effort during close
                } finally {
                    try {
                        walRaf.close();
                    } catch (IOException ignored) {
                    }
                    walRaf = null;
                }
            }
        } finally {
            lock.unlock();
        }
    }
    // ----- snapshot / compaction (used by backup as well) ------------------ //

    /**
     * Take a consistent snapshot of in-memory state and truncate the WAL. Returns
     * the frozen LSN.
     * <p>
     * Concurrency: the only critical section is the dict copy + LSN read. Disk I/O
     * happens outside the lock so concurrent puts are not blocked.
     */
    public long snapshot() {
        long frozenLsn;
        Map<String, VersionedValue> snapshotData;
        lock.lock();
        try {
            frozenLsn = this.lsn;
            // VersionedValue is a record (immutable), shallow copy is safe.
            snapshotData = new LinkedHashMap<>(mem.items());
        } finally {
            lock.unlock();
        }
        // Convert to JSON-friendly form outside the lock.
        Map<String, Object> serializableData = new LinkedHashMap<>();
        for (Map.Entry<String, VersionedValue> e : snapshotData.entrySet()) {
            serializableData.put(e.getKey(), e.getValue().toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("lsn", frozenLsn);
        root.put("data", serializableData);
        Path tmp = snapPath.resolveSibling(SNAPSHOT_FILE + ".tmp");
        try {
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                MAPPER.writeValue(out, root);
                out.flush();
                // Best-effort fsync on the tmp file before atomic rename.
                try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rwd")) {
                    raf.getFD().sync();
                }
            }
            Files.move(tmp, snapPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        truncateWalThrough(frozenLsn);
        return frozenLsn;
    }

    public Path walPath() { return walPath; }

    public Path snapshotPath() { return snapPath; }
    // ----- internals ------------------------------------------------------- //

    private void recover() {
        if (Files.exists(snapPath)) {
            try (var in = Files.newInputStream(snapPath)) {
                Map<String, Object> snap = MAPPER.readValue(in, new TypeReference<>() {
                });
                long snapLsn = ((Number) snap.getOrDefault("lsn", 0)).longValue();
                this.lsn = snapLsn;
                @SuppressWarnings("unchecked")
                Map<String, Object> rawData = (Map<String, Object>) snap.getOrDefault("data", new LinkedHashMap<>());
                for (Map.Entry<String, Object> e : rawData.entrySet()) {
                    VersionedValue vv = VersionedValue.fromMap(e.getValue());
                    if (vv != null) {
                        mem.putRaw(e.getKey(), vv);
                    }
                }
                mem.setLsn(snapLsn);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (Files.exists(walPath)) {
            try (BufferedReader br = Files.newBufferedReader(walPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.strip().isEmpty())
                        continue;
                    Map<String, Object> entry = WalCodec.decode(line);
                    if (entry == null) {
                        // Torn / CRC-mismatched record at the tail. Common
                        // after a crash. Stop here -- everything past is
                        // untrustworthy.
                        break;
                    }
                    long entryLsn = ((Number) entry.get("lsn")).longValue();
                    if (entryLsn <= this.lsn)
                        continue;
                    VersionedValue vv = VersionedValue.fromMap(entry.get("vv"));
                    if (vv != null) {
                        mem.put((String) entry.get("key"), vv);
                    }
                    this.lsn = entryLsn;
                    mem.setLsn(entryLsn);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Drop WAL entries whose lsn <= throughLsn. Keep the rest. */
    private void truncateWalThrough(long throughLsn) {
        lock.lock();
        try {
            try {
                if (walRaf != null && fsyncOnWrite)
                    walRaf.getFD().sync();
            } catch (IOException ignored) {
            }
            List<String> survivors = new ArrayList<>();
            if (Files.exists(walPath)) {
                try (BufferedReader br = Files.newBufferedReader(walPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.strip().isEmpty())
                            continue;
                        Map<String, Object> entry = WalCodec.decode(line);
                        if (entry == null) {
                            break; // torn / corrupt -- stop here
                        }
                        long entryLsn = ((Number) entry.get("lsn")).longValue();
                        if (entryLsn > throughLsn) {
                            // Preserve the original framed line verbatim so
                            // the rewritten WAL keeps its CRCs intact.
                            survivors.add(line);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            // Close current handle, rewrite, reopen for append.
            try {
                if (walRaf != null) {
                    try {
                        walRaf.close();
                    } catch (IOException ignored) {
                    }
                    walRaf = null;
                }
                Path tmp = walPath.resolveSibling(WAL_FILE + ".tmp");
                try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (String s : survivors) {
                        bw.write(s);
                        bw.write('\n');
                    }
                    bw.flush();
                }
                try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rwd")) {
                    raf.getFD().sync();
                }
                Files.move(tmp, walPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                walRaf = new RandomAccessFile(walPath.toFile(), "rwd");
                walRaf.seek(walRaf.length());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            lock.unlock();
        }
    }
}
