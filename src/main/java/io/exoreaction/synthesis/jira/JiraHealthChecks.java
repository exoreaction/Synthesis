package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.cli.HealthCommand.HealthIssue;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jira workspace health checks: W025 (stale), W026 (conflict), W027 (dangling link).
 *
 * <p>All checks are gated on {@code jira.enabled} in the config. When Jira
 * integration is disabled, all checks return empty lists.
 *
 * <p>W027 has no analog in {@code NotionHealthChecks}: it is specific to Jira's
 * structured {@code issuelinks} (RFC #542's distinguishing value) — it flags links
 * whose target issue was never synced (out of JQL scope, deleted, or not yet
 * indexed), which is only meaningful because issue links are persisted as
 * first-class rows in {@code jira_issue_links}.
 *
 * <p>Notion's W023 (orphan: page with no reachable parent) has no Jira analog
 * here: Jira issues have no parent-page hierarchy to be orphaned from (see
 * {@link JiraIssueMapper}'s javadoc), so that check is not force-fitted.
 *
 * <p>Designed to be called from {@link io.exoreaction.synthesis.cli.HealthCommand}
 * during the audit phase.
 */
public class JiraHealthChecks {

    private static final Logger LOG = Logger.getLogger(JiraHealthChecks.class.getName());

    private final SynthesisConfig config;
    private final SynthesisDatabase db;

    public JiraHealthChecks(SynthesisConfig config, SynthesisDatabase db) {
        this.config = config;
        this.db = db;
    }

    /**
     * Runs all Jira health checks and returns any issues found.
     *
     * @param workspaceName the Synthesis workspace name (used as the key in jira_sync_state)
     * @return list of health issues (empty if Jira is disabled or all checks pass)
     */
    public List<HealthIssue> checkAll(String workspaceName) {
        if (!config.getJira().isEnabled()) {
            return List.of();
        }

        List<HealthIssue> issues = new ArrayList<>();
        issues.addAll(checkStale(workspaceName));
        issues.addAll(checkConflicts(workspaceName));
        issues.addAll(checkDanglingLinks(workspaceName));
        return issues;
    }

    /**
     * W025 — jira-stale: Jira issues not synced recently.
     *
     * <p>Triggered when the last sync time exceeds {@code pollIntervalMinutes * 3}.
     */
    List<HealthIssue> checkStale(String workspaceName) {
        if (!config.getJira().isEnabled()) {
            return List.of();
        }

        try {
            JiraSyncState syncState = new JiraSyncState(db);
            int pollInterval = config.getJira().getPollIntervalMinutes();
            long staleThresholdMinutes = (long) pollInterval * 3;

            Optional<Instant> lastSync = syncState.getLastSyncTime(workspaceName);
            if (lastSync.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W025",
                        "Jira workspace never synced (expected every " + pollInterval + " min)",
                        "synthesis scan --source jira"));
            }

            long minutesSinceSync = Duration.between(lastSync.get(), Instant.now()).toMinutes();
            if (minutesSinceSync > staleThresholdMinutes) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W025",
                        "Jira workspace not synced in " + minutesSinceSync
                                + " minutes (expected every " + pollInterval + " min)",
                        "synthesis scan --source jira"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W025 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * W026 — jira-conflict: Duplicate virtual paths in the Jira index.
     *
     * <p>Structurally should not happen (virtual paths derive from the already-unique
     * issue key), but kept as a defensive invariant check, same shape as Notion's W024.
     */
    List<HealthIssue> checkConflicts(String workspaceName) {
        if (!config.getJira().isEnabled()) {
            return List.of();
        }

        try {
            JiraSyncState syncState = new JiraSyncState(db);
            Set<String> duplicates = syncState.getDuplicateVirtualPaths(workspaceName);
            if (!duplicates.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W026",
                        duplicates.size() + " Jira issues share virtual paths (index conflict)",
                        "synthesis scan --source jira"));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W026 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * W027 — jira-dangling-link: issue links pointing at an issue that was never
     * synced (out of JQL scope, deleted, or not yet indexed).
     */
    List<HealthIssue> checkDanglingLinks(String workspaceName) {
        if (!config.getJira().isEnabled()) {
            return List.of();
        }

        try {
            JiraSyncState syncState = new JiraSyncState(db);
            List<String> dangling = syncState.getDanglingLinks(workspaceName);
            if (!dangling.isEmpty()) {
                return List.of(new HealthIssue(
                        HealthIssue.Severity.WARNING, "W027",
                        dangling.size() + " Jira issue links point to issues outside the synced scope",
                        "synthesis scan --source jira", dangling));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "W027 check failed: " + e.getMessage(), e);
        }

        return List.of();
    }
}
