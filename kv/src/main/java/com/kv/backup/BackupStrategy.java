package com.kv.backup;

import java.util.Map;

/**
 * Pluggable backup strategy. Default impl: {@link WalCheckpointBackup}.
 * <p>
 * Future ideas: copy-on-write snapshot, fork-style, S3-uploaded snapshots.
 */
public interface BackupStrategy {
    /** Take a live, consistent backup. Returns a manifest describing it. */
    Map<String, Object> backup(String destDir);
}
