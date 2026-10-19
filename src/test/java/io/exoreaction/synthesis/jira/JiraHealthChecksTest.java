package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.cli.HealthCommand.HealthIssue;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssueLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JiraHealthChecks}: W025 (stale), W026 (conflict), W027 (dangling link).
 */
class JiraHealthChecksTest {

    private static final String WORKSPACE = "test-workspace";

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private JiraSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new JiraSyncState(db);
    }

    private SynthesisConfig jiraEnabled(int pollIntervalMinutes) {
        SynthesisConfig config = new SynthesisConfig();
        config.getJira().setEnabled(true);
        config.getJira().setPollIntervalMinutes(pollIntervalMinutes);
        return config;
    }

    private SynthesisConfig jiraDisabled() {
        SynthesisConfig config = new SynthesisConfig();
        config.getJira().setEnabled(false);
        return config;
    }

    private void recordSyncAt(Instant time) throws SQLException {
        syncState.upsertSyncState(WORKSPACE, "ORDER BY updated", time, 10, "ok", null);
    }

    private void recordIssue(String issueKey, String virtualPath) throws SQLException {
        syncState.recordIssue(WORKSPACE, issueKey, null, "PROJ", "Summary " + issueKey, "Open",
                virtualPath, Instant.now(), "hash-" + issueKey, null);
    }

    // -------------------------------------------------------------------------
    // W025 — jira-stale
    // -------------------------------------------------------------------------

    @Test
    void w025_triggeredWhen_jiraEnabled_andStale() throws SQLException {
        SynthesisConfig config = jiraEnabled(15);
        Instant staleTime = Instant.now().minus(120, ChronoUnit.MINUTES);
        recordSyncAt(staleTime);

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W025", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("120"));
        assertTrue(issue.description().contains("15"));
    }

    @Test
    void w025_notTriggeredWhen_jiraDisabled() throws SQLException {
        SynthesisConfig config = jiraDisabled();
        recordSyncAt(Instant.now().minus(120, ChronoUnit.MINUTES));

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        assertTrue(checks.checkStale(WORKSPACE).isEmpty());
    }

    @Test
    void w025_notTriggeredWhen_recentSync() throws SQLException {
        SynthesisConfig config = jiraEnabled(15);
        recordSyncAt(Instant.now().minus(5, ChronoUnit.MINUTES));

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        assertTrue(checks.checkStale(WORKSPACE).isEmpty());
    }

    // -------------------------------------------------------------------------
    // W026 — jira-conflict
    // -------------------------------------------------------------------------

    @Test
    void w026_triggeredWhen_duplicatesExist() throws SQLException {
        SynthesisConfig config = jiraEnabled(15);
        recordIssue("PROJ-1", "PROJ/Shared.md");
        recordIssue("PROJ-2", "PROJ/Shared.md");

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkConflicts(WORKSPACE);

        assertEquals(1, issues.size());
        assertEquals("W026", issues.get(0).code());
    }

    @Test
    void w026_notTriggeredWhen_noDuplicates() throws SQLException {
        SynthesisConfig config = jiraEnabled(15);
        recordIssue("PROJ-1", "PROJ/PROJ-1.md");
        recordIssue("PROJ-2", "PROJ/PROJ-2.md");

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        assertTrue(checks.checkConflicts(WORKSPACE).isEmpty());
    }

    // -------------------------------------------------------------------------
    // W027 — jira-dangling-link
    // -------------------------------------------------------------------------

    @Test
    void w027_triggeredWhen_linkTargetNotSynced() throws SQLException {
        SynthesisConfig config = jiraEnabled(15);
        recordIssue("PROJ-1", "PROJ/PROJ-1.md");
        syncState.recordIssueLinks(WORKSPACE, "PROJ-1", List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-99", "Out of scope issue")));

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkDanglingLinks(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W027", issue.code());
        assertEquals(1, issue.details().size());
        assertTrue(issue.details().get(0).contains("PROJ-1"));
        assertTrue(issue.details().get(0).contains("PROJ-99"));
    }

    @Test
    void w027_notTriggeredWhen_allLinkTargetsSynced() throws SQLException {
        SynthesisConfig config = jiraEnabled(15);
        recordIssue("PROJ-1", "PROJ/PROJ-1.md");
        recordIssue("PROJ-2", "PROJ/PROJ-2.md");
        syncState.recordIssueLinks(WORKSPACE, "PROJ-1", List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-2", "In scope")));

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        assertTrue(checks.checkDanglingLinks(WORKSPACE).isEmpty());
    }

    @Test
    void w027_notTriggeredWhen_jiraDisabled() throws SQLException {
        SynthesisConfig config = jiraDisabled();
        recordIssue("PROJ-1", "PROJ/PROJ-1.md");
        syncState.recordIssueLinks(WORKSPACE, "PROJ-1", List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-99", "Out of scope")));

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        assertTrue(checks.checkDanglingLinks(WORKSPACE).isEmpty());
    }

    // -------------------------------------------------------------------------
    // All checks — jira disabled
    // -------------------------------------------------------------------------

    @Test
    void allChecks_jiraDisabled_noSignals() throws SQLException {
        SynthesisConfig config = jiraDisabled();

        recordSyncAt(Instant.now().minus(120, ChronoUnit.MINUTES));
        recordIssue("dup-1", "PROJ/Dup.md");
        recordIssue("dup-2", "PROJ/Dup.md");

        JiraHealthChecks checks = new JiraHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkAll(WORKSPACE);

        assertTrue(issues.isEmpty(), "All checks should return empty when Jira is disabled, got "
                + issues.size() + " issues");
    }
}
