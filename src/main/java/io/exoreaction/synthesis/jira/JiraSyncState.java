package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssueLink;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Data access object for the {@code jira_issues}, {@code jira_issue_links}, and
 * {@code jira_sync_state} tables — the Jira equivalent of {@code NotionSyncState}.
 *
 * <p>All methods synchronize on the parent {@link SynthesisDatabase} instance to
 * ensure thread safety (mirrors the pattern used by {@code NotionSyncState}).
 *
 * <p>{@code jira_issue_links} has no analog in {@code NotionSyncState}: Jira's
 * {@code issuelinks} are structured, typed relationships between issues (RFC #542's
 * distinguishing value), persisted here as their own first-class rows rather than
 * being inferred, so a future consumer (RFC #543) can read them as trusted edges.
 */
public class JiraSyncState {

    private static final Logger LOG = Logger.getLogger(JiraSyncState.class.getName());

    private final SynthesisDatabase db;

    public JiraSyncState(SynthesisDatabase db) {
        this.db = db;
    }

    /**
     * Inserts or updates a Jira issue record.
     *
     * @param workspaceName  the Synthesis workspace name
     * @param issueKey       the Jira issue key (e.g. "PROJ-123")
     * @param issueId        the Jira issue id (numeric), may be null
     * @param projectKey     the Jira project key
     * @param summary        the issue summary
     * @param status         the issue status name, may be null
     * @param virtualPath    the resolved virtual filesystem path
     * @param lastUpdatedTime when the issue was last updated in Jira
     * @param contentHash    SHA-256 hash of the rendered content (for change detection)
     * @param issueUrl       the browsable Jira issue URL
     */
    public synchronized void recordIssue(String workspaceName, String issueKey, String issueId,
                                         String projectKey, String summary, String status,
                                         String virtualPath, Instant lastUpdatedTime, String contentHash,
                                         String issueUrl) throws SQLException {
        String sql = """
            INSERT INTO jira_issues (
                issue_key, workspace_name, issue_id, project_key, summary, status,
                virtual_path, last_updated_at, last_synced_at, content_hash, issue_url
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(issue_key, workspace_name) DO UPDATE SET
                issue_id        = excluded.issue_id,
                project_key     = excluded.project_key,
                summary         = excluded.summary,
                status          = excluded.status,
                virtual_path    = excluded.virtual_path,
                last_updated_at = excluded.last_updated_at,
                last_synced_at  = excluded.last_synced_at,
                content_hash    = excluded.content_hash,
                issue_url       = excluded.issue_url
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueKey);
            ps.setString(2, workspaceName);
            ps.setString(3, issueId);
            ps.setString(4, projectKey);
            ps.setString(5, summary);
            ps.setString(6, status);
            ps.setString(7, virtualPath);
            ps.setLong(8, lastUpdatedTime != null ? lastUpdatedTime.getEpochSecond() : 0);
            ps.setLong(9, Instant.now().getEpochSecond());
            ps.setString(10, contentHash);
            ps.setString(11, issueUrl);
            ps.executeUpdate();
        }
    }

    /**
     * Replaces all issue links recorded for a given source issue (delete + re-insert,
     * so stale links from a previous sync are removed).
     *
     * @param workspaceName the Synthesis workspace name
     * @param sourceIssueKey the issue the links originate from
     * @param links         the current set of links for this issue (may be empty)
     */
    public synchronized void recordIssueLinks(String workspaceName, String sourceIssueKey,
                                              List<JiraIssueLink> links) throws SQLException {
        Connection conn = db.getConnection();

        String deleteSql = "DELETE FROM jira_issue_links WHERE workspace_name = ? AND source_issue_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, workspaceName);
            ps.setString(2, sourceIssueKey);
            ps.executeUpdate();
        }

        if (links == null || links.isEmpty()) {
            return;
        }

        String insertSql = """
            INSERT INTO jira_issue_links (
                workspace_name, source_issue_key, target_issue_key, link_type, direction, target_summary
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(workspace_name, source_issue_key, target_issue_key, link_type, direction)
            DO UPDATE SET target_summary = excluded.target_summary
            """;
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (JiraIssueLink link : links) {
                ps.setString(1, workspaceName);
                ps.setString(2, sourceIssueKey);
                ps.setString(3, link.targetKey());
                ps.setString(4, link.relationship());
                ps.setString(5, link.direction());
                ps.setString(6, link.targetSummary());
                ps.executeUpdate();
            }
        }
    }

    /**
     * Returns all issue links recorded for a given source issue.
     */
    public synchronized List<JiraIssueLink> getIssueLinks(String workspaceName, String sourceIssueKey)
            throws SQLException {
        String sql = """
            SELECT target_issue_key, link_type, direction, target_summary
            FROM jira_issue_links WHERE workspace_name = ? AND source_issue_key = ?
            """;
        List<JiraIssueLink> links = new ArrayList<>();
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            ps.setString(2, sourceIssueKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(new JiraIssueLink(
                            rs.getString("link_type"),
                            rs.getString("direction"),
                            rs.getString("target_issue_key"),
                            rs.getString("target_summary")));
                }
            }
        }
        return links;
    }

    /**
     * Returns issue links whose target issue key is not present in {@code jira_issues}
     * for this workspace — i.e. the link points at an issue that was never synced
     * (out of JQL scope, deleted, or not yet indexed). Backs health check W027.
     *
     * @param workspaceName the Synthesis workspace name
     * @return list of dangling links as "SOURCE -> TARGET (relationship)" strings
     */
    public synchronized List<String> getDanglingLinks(String workspaceName) throws SQLException {
        String sql = """
            SELECT l.source_issue_key, l.target_issue_key, l.link_type
            FROM jira_issue_links l
            WHERE l.workspace_name = ?
              AND NOT EXISTS (
                  SELECT 1 FROM jira_issues i
                  WHERE i.workspace_name = l.workspace_name AND i.issue_key = l.target_issue_key
              )
            """;
        List<String> dangling = new ArrayList<>();
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    dangling.add(rs.getString("source_issue_key") + " -> " + rs.getString("target_issue_key")
                            + " (" + rs.getString("link_type") + ")");
                }
            }
        }
        return dangling;
    }

    /**
     * Inserts or updates the sync state for a workspace.
     *
     * @param workspaceName the Synthesis workspace name
     * @param jqlFilter     the JQL filter used for this sync
     * @param lastSyncTime  when the last sync completed
     * @param totalIssues   total number of issues synced
     * @param status        sync status ("ok", "error", "partial")
     * @param errorMessage  error message (null if status is "ok")
     */
    public synchronized void upsertSyncState(String workspaceName, String jqlFilter,
                                             Instant lastSyncTime, int totalIssues,
                                             String status, String errorMessage) throws SQLException {
        String sql = """
            INSERT INTO jira_sync_state (
                workspace_name, jql_filter, last_sync_time, total_issues,
                last_sync_status, error_message
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(workspace_name) DO UPDATE SET
                jql_filter       = excluded.jql_filter,
                last_sync_time   = excluded.last_sync_time,
                total_issues     = excluded.total_issues,
                last_sync_status = excluded.last_sync_status,
                error_message    = excluded.error_message
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            ps.setString(2, jqlFilter != null ? jqlFilter : "");
            ps.setLong(3, lastSyncTime != null ? lastSyncTime.getEpochSecond() : 0);
            ps.setInt(4, totalIssues);
            ps.setString(5, status != null ? status : "ok");
            ps.setString(6, errorMessage);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the last sync time for a workspace.
     */
    public synchronized Optional<Instant> getLastSyncTime(String workspaceName) throws SQLException {
        String sql = "SELECT last_sync_time FROM jira_sync_state WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long epoch = rs.getLong("last_sync_time");
                    return epoch > 0 ? Optional.of(Instant.ofEpochSecond(epoch)) : Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns issue keys that exist in the database but are not in the provided set
     * of live issue keys. These are issues that have been deleted from Jira, or have
     * fallen out of the configured JQL scope.
     *
     * @param workspaceName  the Synthesis workspace name
     * @param liveIssueKeys  the set of currently-live Jira issue keys
     * @return list of orphaned issue keys
     */
    public synchronized List<String> getOrphanIssueKeys(String workspaceName,
                                                         Set<String> liveIssueKeys) throws SQLException {
        String sql = "SELECT issue_key FROM jira_issues WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        List<String> orphans = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String issueKey = rs.getString("issue_key");
                    if (!liveIssueKeys.contains(issueKey)) {
                        orphans.add(issueKey);
                    }
                }
            }
        }
        return orphans;
    }

    /**
     * Returns virtual paths of issues whose last sync time exceeds the stale threshold.
     */
    public synchronized List<String> getStalePaths(String workspaceName,
                                                    Duration staleThreshold) throws SQLException {
        long cutoffEpoch = Instant.now().minus(staleThreshold).getEpochSecond();
        String sql = "SELECT virtual_path FROM jira_issues WHERE workspace_name = ? AND last_synced_at < ?";
        Connection conn = db.getConnection();
        List<String> stalePaths = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            ps.setLong(2, cutoffEpoch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stalePaths.add(rs.getString("virtual_path"));
                }
            }
        }
        return stalePaths;
    }

    /**
     * Returns the total number of issues recorded for a workspace from the sync state table.
     */
    public synchronized int getTotalIssues(String workspaceName) throws SQLException {
        String sql = "SELECT total_issues FROM jira_sync_state WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total_issues");
                }
            }
        }
        return 0;
    }

    /**
     * Returns the count of issues in the {@code jira_issues} table for a workspace.
     */
    public synchronized int getIssueCount(String workspaceName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM jira_issues WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * Returns all tracked issue keys for a workspace.
     */
    public synchronized Set<String> getAllIssueKeys(String workspaceName) throws SQLException {
        String sql = "SELECT issue_key FROM jira_issues WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        Set<String> keys = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("issue_key"));
                }
            }
        }
        return keys;
    }

    /**
     * Returns virtual paths that are shared by more than one issue in the workspace.
     *
     * <p>Structurally should never happen (virtual paths are derived from the
     * already-unique issue key), but kept as a defensive invariant check, same
     * shape as {@code NotionSyncState#getDuplicateVirtualPaths}.
     */
    public synchronized Set<String> getDuplicateVirtualPaths(String workspaceName) throws SQLException {
        String sql = """
            SELECT virtual_path FROM jira_issues
            WHERE workspace_name = ?
            GROUP BY virtual_path
            HAVING COUNT(*) > 1
            """;
        Connection conn = db.getConnection();
        Set<String> duplicates = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    duplicates.add(rs.getString("virtual_path"));
                }
            }
        }
        return duplicates;
    }
}
