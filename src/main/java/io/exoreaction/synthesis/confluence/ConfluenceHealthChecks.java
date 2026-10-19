package io.exoreaction.synthesis.confluence;

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
 * Confluence workspace health checks: W025 (stale), W026 (orphan), W027 (conflict).
 *
 * <p>Next available codes after Notion's W022/W023/W024 — structurally identical to
 * {@code NotionHealthChecks} since these checks are generic over "page with a parent
 * and a virtual path," not Notion-specific (per RFC #541).
 *
 * <p>All checks are gated on {@code confluence.enabled} in the config. When Confluence
 * integration is disabled, all checks return empty lists.
 *
 * <p>Designed to be called from {@link io.exoreaction.synthesis.cli.HealthCommand}
 * during the audit phase.
 */
public class ConfluenceHealthChecks {

    private static final Logger LOG = Logger.getLogger(ConfluenceHealthChecks.class.getName());

    private final SynthesisConfig config;
    private final SynthesisDatabase db;

    public ConfluenceHealthChecks(SynthesisConfig config, SynthesisDatabase db) {
        this.config = config;
        this.db = db;
    }

    /**
     * Runs all Confluence health checks and returns any issues found.
     *
     * @param workspaceName the Synthesis workspace name (used as the key in confluence_sync_state)
     * @return list of health issues (empty if Confluence is disabled or all checks pass)
     */
    public List<HealthIssue> checkAll(String workspaceName) {
        if (!config.getConfluence().isEnabled()) {
            return List.of();
        }

        List<HealthIssue> issues = new ArrayList<>();
        issues.addAll(checkStale(workspaceName));
        issues.addAll(checkOrphans(workspaceName));
        issues.addAll(checkConflicts(workspaceName));
        return issues;
    }

    /**
     * W025 — confluence-stale: Confluence pages not synced recently.
     *
     * <p>Triggered when the last sync time exceeds {@code pollIntervalMinutes * 3}.
     *
     * @param workspaceName the Synthesis workspace name
     * @return list containing a single W025 issue, or empty if check passes
     */
    List<HealthIssue> checkStale(String workspaceName) {
        if (!config.getConfluence().isEnabled()) {
            return List.of();
        }

        try {
            ConfluenceSyncState syncState = new ConfluenceSyncState(db);
            int pollInterval = config.getConfluence().getPollIntervalMinutes();
            long staleThresholdMinutes = (long) pollInterval * 3;

            Optional<Instant> lastSync = syncState.getLastSyncTime(workspaceName);
            if (lastSync.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W025",
                        "Confluence workspace never synced (expected every " + pollInterval + " min)",
                        "synthesis scan --source confluence"));
            }

            long minutesSinceSync = Duration.between(lastSync.get(), Instant.now()).toMinutes();
            if (minutesSinceSync > staleThresholdMinutes) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W025",
                        "Confluence workspace not synced in " + minutesSinceSync
                                + " minutes (expected every " + pollInterval + " min)",
                        "synthesis scan --source confluence"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W025 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * W026 — confluence-orphan: Confluence pages with no parent reachable from a space root.
     *
     * <p>Queries the database for pages whose {@code parent_page_id} is non-null
     * and does not match any known page_id in the same workspace. This is a
     * database-only check (no Confluence API call required).
     *
     * @param workspaceName the Synthesis workspace name
     * @return list containing a single W026 issue, or empty if no orphans found
     */
    List<HealthIssue> checkOrphans(String workspaceName) {
        if (!config.getConfluence().isEnabled()) {
            return List.of();
        }

        try {
            List<String> orphans = findOrphanPages(workspaceName);
            if (!orphans.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W026",
                        orphans.size() + " Confluence pages have no path from a space root (orphaned)",
                        "synthesis scan --source confluence"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W026 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * W027 — confluence-conflict: Duplicate virtual paths in Confluence index.
     *
     * @param workspaceName the Synthesis workspace name
     * @return list containing a single W027 issue, or empty if no conflicts found
     */
    List<HealthIssue> checkConflicts(String workspaceName) {
        if (!config.getConfluence().isEnabled()) {
            return List.of();
        }

        try {
            ConfluenceSyncState syncState = new ConfluenceSyncState(db);
            Set<String> duplicates = syncState.getDuplicateVirtualPaths(workspaceName);
            if (!duplicates.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W027",
                        duplicates.size() + " Confluence pages share virtual paths (index conflict)",
                        "synthesis scan --source confluence"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W027 check failed: " + e.getMessage(), e);
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

        String sql = "SELECT page_id, parent_page_id FROM confluence_pages WHERE workspace_name = ?";
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
