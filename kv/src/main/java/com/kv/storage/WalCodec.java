package com.kv.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * CRC32-framed WAL line codec. Each WAL entry is one line of the form:
 *
 * <pre>
 *   &lt;8-hex-CRC32&gt; &lt;JSON_PAYLOAD&gt;\n
 * </pre>
 *
 * <h3>Why CRC and not just JSON parse?</h3> Plain "JSON parse fails on torn
 * write" is not sufficient. A torn write at the WAL tail can produce a string
 * that:
 * <ul>
 * <li>happens to parse as valid JSON (e.g. truncated right after a closing
 * brace, or missing fields that deserialize as {@code null}),</li>
 * <li>is byte-for-byte a prefix of an older entry with different content (when
 * the WAL was rewritten by snapshot truncation and then partially appended
 * over).</li>
 * </ul>
 * In both cases the JSON would parse, but the entry would be silently corrupt
 * and recovery would replay the wrong state. Every production WAL (etcd,
 * PostgreSQL, RocksDB, Kafka) frames its records with a checksum for exactly
 * this reason.
 *
 * <h3>Format choice: hex CRC + space + JSON, kept text-mode</h3> A binary
 * length+CRC frame would be marginally smaller, but the text format keeps the
 * WAL grep-able / diffable / diagnosable with {@code less}, which matters more
 * for a take-home than 10 bytes per record.
 *
 * <p>
 * {@link #decode} returns {@code null} for any failure (corrupt CRC, malformed
 * prefix, invalid JSON, truncation). Callers treat {@code null} as "torn write
 * at WAL tail; stop replay here", which preserves the existing recovery
 * semantics.
 */
public final class WalCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WalCodec() {}

    /** Encodes an entry to a framed line ending in {@code '\n'}. */
    public static String encode(Map<String, Object> entry) throws IOException {
        String json = MAPPER.writeValueAsString(entry);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(payload);
        return "%08x %s\n".formatted(crc.getValue(), json);
    }

    /**
     * Decodes a framed line. Returns the parsed entry, or {@code null} on any
     * failure (corrupt CRC, malformed prefix, invalid JSON, truncation). Caller
     * MUST stop replay on null -- everything past a torn record is untrustworthy.
     */
    public static Map<String, Object> decode(String line) {
        if (line == null)
            return null;
        line = line.strip();
        // 8 hex CRC + space + at least 2-char JSON ("{}" min)
        if (line.length() < 11)
            return null;
        if (line.charAt(8) != ' ')
            return null;
        long expected;
        try {
            expected = Long.parseLong(line.substring(0, 8), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        String json = line.substring(9);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((crc.getValue() & 0xFFFFFFFFL) != expected) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            return null;
        }
    }
}
