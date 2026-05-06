package com.kv.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kv.storage.WalCodec;
import com.kv.storage.WalSnapshotStorage;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot + WAL-tail copy. Direct port of
 * {@code python/kvstore/backup.py::WalCheckpointBackup}.
 *
 * <ol>
 * <li>Force a fresh snapshot. The lock is held only for a dict copy
 * (microseconds); slow disk I/O happens outside the lock.</li>
 * <li>Remember the LSN at which the snapshot was frozen.</li>
 * <li>Copy {@code snapshot.json} to the backup directory. After step 1 the file
 * is immutable until the next snapshot, so a plain copy is safe.</li>
 * <li>Copy WAL entries with {@code lsn > frozenLsn} (anything that landed after
 * snapshot freeze but before this backup completes). Concurrent writers keep
 * appending; we just stop at the first torn line / EOF.</li>
 * </ol>
 *
 * <p>
 * No completed write is lost: every write is either in the snapshot (lsn ≤
 * frozenLsn), or in the WAL tail we copied (frozenLsn &lt; lsn ≤
 * maxLsnInBackup), or in the next backup (lsn &gt; maxLsnInBackup).
 */
public class WalCheckpointBackup implements BackupStrategy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WalSnapshotStorage storage;

    public WalCheckpointBackup(WalSnapshotStorage storage) { this.storage = storage; }

    @Override
    public Map<String, Object> backup(String destDir) {
        Path dest = Path.of(destDir);
        try {
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Step 1+2: force snapshot, capture frozen LSN.
        long frozenLsn = storage.snapshot();
        // Step 3: copy snapshot file (immutable until next snapshot).
        Path snapDst = dest.resolve("snapshot.json");
        try {
            Files.copy(storage.snapshotPath(), snapDst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Step 4: copy WAL entries with lsn > frozenLsn. The WAL may be being
        // appended to right now; that's fine -- we stop at the first torn line.
        Path walDst = dest.resolve("wal.log");
        long maxLsn = frozenLsn;
        long copied = 0;
        try (BufferedWriter out = Files.newBufferedWriter(walDst, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            if (Files.exists(storage.walPath())) {
                try (BufferedReader br = Files.newBufferedReader(storage.walPath(), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.strip().isEmpty())
                            continue;
                        // Validate the framed line (CRC + JSON). decode()
                        // returns null on torn / corrupt records -- writer is
                        // mid-flush. Stop here; everything past is incomplete
                        // and will be in the next backup.
                        Map<String, Object> entry = WalCodec.decode(line);
                        if (entry == null) {
                            break;
                        }
                        long lsn = ((Number) entry.get("lsn")).longValue();
                        // snapshot() already truncated through frozenLsn, but
                        // be defensive in case of races.
                        if (lsn <= frozenLsn)
                            continue;
                        // Preserve the original framed line verbatim so the
                        // backup keeps its CRCs intact for restore-time
                        // validation.
                        out.write(line);
                        out.write('\n');
                        copied += 1;
                        if (lsn > maxLsn)
                            maxLsn = lsn;
                    }
                }
            }
            out.flush();
            try (RandomAccessFile raf = new RandomAccessFile(walDst.toFile(), "rwd")) {
                raf.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("frozen_lsn", frozenLsn);
        manifest.put("wal_tail_entries", copied);
        manifest.put("max_lsn_in_backup", maxLsn);
        manifest.put("taken_at_unix_millis", System.currentTimeMillis());
        Path manifestPath = dest.resolve("manifest.json");
        try (BufferedWriter bw = Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            bw.flush();
            try (RandomAccessFile raf = new RandomAccessFile(manifestPath.toFile(), "rwd")) {
                raf.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return manifest;
    }
}
