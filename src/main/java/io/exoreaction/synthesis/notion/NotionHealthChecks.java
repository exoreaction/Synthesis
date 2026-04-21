package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.cli.HealthCommand.HealthIssue;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Notion workspace health checks: W022 (stale), W023 (orphan), W024 (conflict).
 *
 * <p>All checks are gated on {@code notion.enabled} in the config. When Notion
 * integration is disabled, all checks return empty lists.
 *
 * <p>Designed to be called from {@link io.exoreaction.synthesis.cli.HealthCommand}
 * during the audit phase.
 *
 * @since v1.20.0 (Phase 5 — Notion workspace source)
 */
public class NotionHealthChecks {

    private static final Logger LOG = Logger.getLogger(NotionHealthChecks.class.getName());

    private final SynthesisConfig config;
    private final SynthesisDatabase db;

    public NotionHealthChecks(SynthesisConfig config, SynthesisDatabase db) {
        this.config = config;
        this.db = db;
    }

    /**
     * Runs all Notion health checks and returns any issues found.
     *
     * @param workspaceName the Synthesis workspace name (used as the key in notion_sync_state)
     * @return list of health issues (empty if Notion is disabled or all checks pass)
     */
    public List<HealthIssue> checkAll(String workspaceName) {
        if (!config.getNotion().isEnabled()) {
            return List.of();
        }

        List<HealthIssue> issues = new ArrayList<>();
        issues.addAll(checkStale(workspaceName));
        issues.addAll(checkOrphans(workspaceName));
        issues.addAll(checkConflicts(workspaceName));
        return issues;
    }

    /**
     * W022 — notion-stale: Notion pages not synced recently.
     *
     * <p>Triggered when the last sync time exceeds {@code pollIntervalMinutes * 3}.
     *
     * @param workspaceName the Synthesis workspace name
     * @return list containing a single W022 issue, or empty if check passes
     */
    List<HealthIssue> checkStale(String workspaceName) {
        if (!config.getNotion().isEnabled()) {
            return List.of();
        }

        try {
            NotionSyncState syncState = new NotionSyncState(db);
            int pollInterval = config.getNotion().getPollIntervalMinutes();
            long staleThresholdMinutes = (long) pollInterval * 3;

            Optional<Instant> lastSync = syncState.getLastSyncTime(workspaceName);
            if (lastSync.isEmpty()) {
                // No sync has ever occurred — this is stale by definition
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W022",
                        "Notion workspace never synced (expected every " + pollInterval + " min)",
                        "synthesis scan --source notion"));
            }

            long minutesSinceSync = Duration.between(lastSync.get(), Instant.now()).toMinutes();
            if (minutesSinceSync > staleThresholdMinutes) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W022",
                        "Notion workspace not synced in " + minutesSinceSync
                                + " minutes (expected every " + pollInterval + " min)",
                        "synthesis scan --source notion"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W022 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * W023 — notion-orphan: Notion pages with no parent reachable from root.
     *
     * <p>Queries the database for pages whose {@code parent_page_id} is non-null
     * and does not match any known page_id in the same workspace. This is a
     * database-only check (no Notion API call required).
     *
     * @param workspaceName the Synthesis workspace name
     * @return list containing a single W023 issue, or empty if no orphans found
     */
    List<HealthIssue> checkOrphans(String workspaceName) {
        if (!config.getNotion().isEnabled()) {
            return List.of();
        }

        try {
            List<String> orphans = findOrphanPages(workspaceName);
            if (!orphans.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W023",
                        orphans.size() + " Notion pages have no path from root (orphaned)",
                        "synthesis scan --source notion"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W023 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * W024 — notion-conflict: Duplicate virtual paths in Notion index.
     *
     * @param workspaceName the Synthesis workspace name
     * @return list containing a single W024 issue, or empty if no conflicts found
     */
    List<HealthIssue> checkConflicts(String workspaceName) {
        if (!config.getNotion().isEnabled()) {
            return List.of();
        }

        try {
            NotionSyncState syncState = new NotionSyncState(db);
            Set<String> duplicates = syncState.getDuplicateVirtualPaths(workspaceName);
            if (!duplicates.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W024",
                        duplicates.size() + " Notion pages share virtual paths (index conflict)",
                        "synthesis scan --source notion"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W024 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * Finds orphan pages by querying for pages whose parent_page_id does not
     * match any known page_id in the same workspace.
     */
    private List<String> findOrphanPages(String workspaceName) throws SQLException {
        Connection conn = db.getConnection();
        Set<String> allPageIds = new HashSet<>();
        List<String[]> pagesWithParents = new ArrayList<>();

        String sql = "SELECT page_id, parent_page_id FROM notion_pages WHERE workspace_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pageId = rs.getString("page_id");
                    String parentId = rs.getString("parent_page_id");
                    allPageIds.add(pageId);
                    pagesWithParents.add(new String[]{pageId, parentId});
                }
            }
        }

        List<String> orphans = new ArrayList<>();
        for (String[] entry : pagesWithParents) {
            String parentId = entry[1];
            if (parentId != null && !allPageIds.contains(parentId)) {
                orphans.add(entry[0]);
            }
        }

        return orphans;
    }
}
