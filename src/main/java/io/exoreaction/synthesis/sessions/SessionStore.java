package io.exoreaction.synthesis.sessions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

/**
 * Data access object for Claude Code session history tables.
 *
 * <p>All methods synchronize on the parent {@link SynthesisDatabase} instance to
 * ensure thread safety (mirrors the pattern used by {@code FileTrackingDatabase}).
 *
 * <p>FTS5 full-text search is used for {@link #search}; plain SQL for all
 * structured queries.
 */
public class SessionStore {

    private static final Logger LOG = Logger.getLogger(SessionStore.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final SynthesisDatabase db;

    public SessionStore(SynthesisDatabase db) {
        this.db = db;
    }

    // -----------------------------------------------------------------------
    // Write operations
    // -----------------------------------------------------------------------

    /**
     * Inserts or replaces a session record (upsert by session_id).
     */
    public synchronized void upsert(ClaudeSession session) throws SQLException {
        String sql = """
            INSERT INTO claude_sessions (
                session_id, project_dir, started_at, ended_at,
                turn_count, tool_call_count, tool_names_json,
                first_message, all_user_text, scanned_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                project_dir     = excluded.project_dir,
                started_at      = excluded.started_at,
                ended_at        = excluded.ended_at,
                turn_count      = excluded.turn_count,
                tool_call_count = excluded.tool_call_count,
                tool_names_json = excluded.tool_names_json,
                first_message   = excluded.first_message,
                all_user_text   = excluded.all_user_text,
                scanned_at      = excluded.scanned_at
            """;

        String toolNamesJson = null;
        if (session.toolNames() != null && !session.toolNames().isEmpty()) {
            try {
                toolNamesJson = JSON.writeValueAsString(session.toolNames());
            } catch (Exception e) {
                LOG.warning("Could not serialize tool names: " + e.getMessage());
            }
        }

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, session.sessionId());
            ps.setString(2, session.projectDir());
            ps.setLong(3, session.startedAt() != null ? session.startedAt().getEpochSecond() : 0);
            ps.setObject(4, session.endedAt() != null ? session.endedAt().getEpochSecond() : null);
            ps.setInt(5, session.turnCount());
            ps.setInt(6, session.toolCallCount());
            ps.setString(7, toolNamesJson);
            ps.setString(8, session.firstMessage());
            ps.setString(9, session.allUserText());
            ps.setLong(10, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------------

    /**
     * Full-text searches sessions using FTS5.
     *
     * @param query FTS5 query string (e.g. {@code "authentication"} or {@code "login OR auth"})
     * @param limit maximum number of results
     * @return list of matching sessions, ordered by FTS rank
     */
    public synchronized List<ClaudeSession> search(String query, int limit) throws SQLException {
        if (query == null || query.isBlank()) {
            return listRecent(limit, null);
        }

        String sql = """
            SELECT cs.*
            FROM claude_sessions cs
            JOIN claude_sessions_fts fts ON cs.id = fts.rowid
            WHERE claude_sessions_fts MATCH ?
            ORDER BY rank
            LIMIT ?
            """;

        Connection conn = db.getConnection();
        List<ClaudeSession> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            // FTS query syntax error — return empty rather than crashing
            LOG.warning("FTS search failed for query '" + query + "': " + e.getMessage());
        }
        return results;
    }

    /**
     * Lists recent sessions, optionally filtered by project directory.
     *
     * @param limit         maximum rows
     * @param projectFilter substring match on project_dir (null = all projects)
     * @return sessions ordered by started_at DESC
     */
    public synchronized List<ClaudeSession> listRecent(int limit, String projectFilter) throws SQLException {
        String sql;
        if (projectFilter != null && !projectFilter.isBlank()) {
            sql = "SELECT * FROM claude_sessions WHERE project_dir LIKE ? ORDER BY started_at DESC LIMIT ?";
        } else {
            sql = "SELECT * FROM claude_sessions ORDER BY started_at DESC LIMIT ?";
        }

        Connection conn = db.getConnection();
        List<ClaudeSession> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (projectFilter != null && !projectFilter.isBlank()) {
                ps.setString(1, "%" + projectFilter + "%");
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Lists sessions since a given instant.
     */
    public synchronized List<ClaudeSession> listSince(Instant since, String projectFilter) throws SQLException {
        String sql;
        if (projectFilter != null && !projectFilter.isBlank()) {
            sql = "SELECT * FROM claude_sessions WHERE started_at >= ? AND project_dir LIKE ? ORDER BY started_at DESC";
        } else {
            sql = "SELECT * FROM claude_sessions WHERE started_at >= ? ORDER BY started_at DESC";
        }

        Connection conn = db.getConnection();
        List<ClaudeSession> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, since.getEpochSecond());
            if (projectFilter != null && !projectFilter.isBlank()) {
                ps.setString(2, "%" + projectFilter + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Fetches a single session by session UUID.
     */
    public synchronized Optional<ClaudeSession> getBySessionId(String sessionId) throws SQLException {
        String sql = "SELECT * FROM claude_sessions WHERE session_id = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(fromResultSet(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the set of known session IDs and their scanned_at epoch seconds.
     * Used by the scanner for incremental updates (skip unchanged files).
     */
    public synchronized Map<String, Long> getKnownSessions() throws SQLException {
        String sql = "SELECT session_id, scanned_at FROM claude_sessions";
        Connection conn = db.getConnection();
        Map<String, Long> result = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString("session_id"), rs.getLong("scanned_at"));
            }
        }
        return result;
    }

    /**
     * Returns the total number of indexed sessions.
     */
    public synchronized int count() throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM claude_sessions")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // -----------------------------------------------------------------------
    // Mapping
    // -----------------------------------------------------------------------

    private ClaudeSession fromResultSet(ResultSet rs) throws SQLException {
        String toolNamesJson = rs.getString("tool_names_json");
        List<String> toolNames = new ArrayList<>();
        if (toolNamesJson != null && !toolNamesJson.isBlank()) {
            try {
                toolNames = JSON.readValue(toolNamesJson, LIST_TYPE);
            } catch (Exception e) {
                LOG.fine("Could not parse tool_names_json: " + e.getMessage());
            }
        }

        long startedAtEpoch = rs.getLong("started_at");
        Instant startedAt = startedAtEpoch > 0 ? Instant.ofEpochSecond(startedAtEpoch) : null;

        long endedAtEpoch = rs.getLong("ended_at");
        Instant endedAt = !rs.wasNull() && endedAtEpoch > 0 ? Instant.ofEpochSecond(endedAtEpoch) : null;

        return new ClaudeSession(
                rs.getString("session_id"),
                rs.getString("project_dir"),
                startedAt,
                endedAt,
                rs.getInt("turn_count"),
                rs.getInt("tool_call_count"),
                Collections.unmodifiableList(toolNames),
                rs.getString("first_message"),
                rs.getString("all_user_text")
        );
    }
}
