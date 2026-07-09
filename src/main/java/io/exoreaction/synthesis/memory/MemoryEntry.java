package io.exoreaction.synthesis.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * A hash-pinned episodic memory entry — a plan or grounded-answer artifact
 * that can be re-verified against a moved world (#371 item 3).
 *
 * <p>Mirrors kcp-agent's memory architecture: content-stripped (never caches
 * unit bytes), hash-addressed (re-recording is idempotent), re-verifiable
 * (recall re-checks against live manifests).
 *
 * @param memoryId       sha256 of the content-stripped artifact (stable across re-records)
 * @param kind           "plan" or "grounded-answer"
 * @param task           the task this artifact answered/planned — the recall matching key
 * @param manifestSource manifest file path (provenance)
 * @param manifestSha    manifest sha256 (provenance)
 * @param optionsKey     digest of planner options (capabilities context)
 * @param recordedAt     ISO-8601 timestamp
 * @param artifactJson   content-stripped JSON artifact
 * @param workspace      workspace scope (nullable)
 */
public record MemoryEntry(
        String memoryId,
        String kind,
        String task,
        String manifestSource,
        String manifestSha,
        String optionsKey,
        String recordedAt,
        String artifactJson,
        String workspace
) {
    /** Create a memory entry, computing the id from the artifact JSON. */
    public static MemoryEntry of(String kind, String task, String artifactJson,
                                   String recordedAt, String workspace) {
        return new MemoryEntry(
                sha256(artifactJson), kind, task,
                null, null, null,
                recordedAt, artifactJson, workspace);
    }

    /** Create with full provenance metadata. */
    public static MemoryEntry ofFull(String kind, String task, String artifactJson,
                                       String recordedAt, String workspace,
                                       String manifestSource, String manifestSha,
                                       String optionsKey) {
        return new MemoryEntry(
                sha256(artifactJson), kind, task,
                manifestSource, manifestSha, optionsKey,
                recordedAt, artifactJson, workspace);
    }

    /** Verify that the stored id matches the artifact content — fail-closed tamper detection. */
    public boolean verify() {
        return memoryId != null && memoryId.equals(sha256(artifactJson));
    }

    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
