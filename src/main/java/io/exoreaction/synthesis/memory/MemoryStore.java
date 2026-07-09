package io.exoreaction.synthesis.memory;

import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the episodic memory table (#371 item 3).
 *
 * <p>Hash-addressed: appending the same artifact twice is idempotent (INSERT OR IGNORE).
 * Tamper-detected: {@link #recall} drops entries whose stored id doesn't match
 * the artifact content, fail-closed.
 */
public class MemoryStore {

    private final SynthesisDatabase db;

    public MemoryStore(SynthesisDatabase db) {
        this.db = db;
    }

    /** Append a memory entry. Idempotent: duplicate memory_id is silently ignored. */
    public synchronized void append(MemoryEntry entry) throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO memories" +
                " (memory_id, kind, task, manifest_source, manifest_sha," +
                "  options_key, recorded_at, artifact_json, workspace)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" +
                " ON CONFLICT(memory_id) DO NOTHING")) {
            ps.setString(1, entry.memoryId());
            ps.setString(2, entry.kind());
            ps.setString(3, entry.task());
            ps.setString(4, entry.manifestSource());
            ps.setString(5, entry.manifestSha());
            ps.setString(6, entry.optionsKey());
            ps.setString(7, entry.recordedAt());
            ps.setString(8, entry.artifactJson());
            ps.setString(9, entry.workspace());
            ps.executeUpdate();
        }
    }

    /**
     * Recall memories whose task lexically overlaps the query, ranked by FTS5 score.
     * Fail-closed: entries that fail hash verification are silently dropped.
     */
    public synchronized List<MemoryEntry> recall(String query, int limit) throws SQLException {
        if (query == null || query.isBlank()) return List.of();
        String ftsQuery = sanitizeFtsQuery(query);
        if (ftsQuery.isBlank()) return List.of();

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT m.memory_id, m.kind, m.task, m.manifest_source, m.manifest_sha,
                       m.options_key, m.recorded_at, m.artifact_json, m.workspace
                FROM memories_fts f
                JOIN memories m ON f.rowid = m.id
                WHERE memories_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """)) {
            ps.setString(1, ftsQuery);
            ps.setInt(2, limit);

            List<MemoryEntry> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MemoryEntry entry = fromRow(rs);
                    // Fail-closed: drop tampered entries
                    if (entry.verify()) {
                        results.add(entry);
                    }
                }
            }
            return results;
        }
    }

    /** List all memories, optionally filtered by workspace. */
    public synchronized List<MemoryEntry> list(String workspaceFilter, int limit) throws SQLException {
        String sql = workspaceFilter != null
                ? "SELECT * FROM memories WHERE workspace = ? ORDER BY recorded_at DESC LIMIT ?"
                : "SELECT * FROM memories ORDER BY recorded_at DESC LIMIT ?";

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (workspaceFilter != null) {
                ps.setString(1, workspaceFilter);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }

            List<MemoryEntry> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MemoryEntry entry = fromRow(rs);
                    if (entry.verify()) {
                        results.add(entry);
                    }
                }
            }
            return results;
        }
    }

    /** Look up a single memory by id. */
    public synchronized Optional<MemoryEntry> get(String memoryId) throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM memories WHERE memory_id = ?")) {
            ps.setString(1, memoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    MemoryEntry entry = fromRow(rs);
                    return entry.verify() ? Optional.of(entry) : Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Count total memories. */
    public synchronized int count() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM memories");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Delete a memory by id. */
    public synchronized boolean forget(String memoryId) throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM memories WHERE memory_id = ?")) {
            ps.setString(1, memoryId);
            return ps.executeUpdate() > 0;
        }
    }

    private static MemoryEntry fromRow(ResultSet rs) throws SQLException {
        return new MemoryEntry(
                rs.getString("memory_id"),
                rs.getString("kind"),
                rs.getString("task"),
                rs.getString("manifest_source"),
                rs.getString("manifest_sha"),
                rs.getString("options_key"),
                rs.getString("recorded_at"),
                rs.getString("artifact_json"),
                rs.getString("workspace"));
    }

    /** Sanitize a query for FTS5: strip stop words, limit tokens. */
    static String sanitizeFtsQuery(String query) {
        if (query == null) return "";
        String[] words = query.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        var stops = java.util.Set.of("the", "a", "an", "is", "are", "was", "were",
                "in", "on", "at", "to", "for", "of", "and", "or", "not", "it",
                "this", "that", "with", "from", "by", "as", "be", "has", "have",
                "do", "does", "did", "will", "would", "could", "should", "may",
                "can", "how", "what", "which", "who", "when", "where", "why");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String w : words) {
            if (w.length() > 1 && !stops.contains(w) && count < 6) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(w);
                count++;
            }
        }
        return sb.toString();
    }
}
