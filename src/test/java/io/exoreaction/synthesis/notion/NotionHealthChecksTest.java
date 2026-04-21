package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.cli.HealthCommand.HealthIssue;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
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
 * Tests for {@link NotionHealthChecks}: W022 (stale), W023 (orphan), W024 (conflict).
 */
class NotionHealthChecksTest {

    private static final String WORKSPACE = "test-workspace";

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private NotionSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new NotionSyncState(db);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SynthesisConfig notionEnabled(int pollIntervalMinutes) {
        SynthesisConfig config = new SynthesisConfig();
        config.getNotion().setEnabled(true);
        config.getNotion().setPollIntervalMinutes(pollIntervalMinutes);
        return config;
    }

    private SynthesisConfig notionDisabled() {
        SynthesisConfig config = new SynthesisConfig();
        config.getNotion().setEnabled(false);
        return config;
    }

    private void recordSyncAt(Instant time) throws SQLException {
        syncState.upsertSyncState(WORKSPACE, "root-page-id", time, 10, "ok", null);
    }

    private void recordPage(String pageId, String parentPageId, String virtualPath)
            throws SQLException {
        syncState.recordPage(WORKSPACE, pageId, "Title " + pageId, parentPageId,
                virtualPath, Instant.now(), "hash-" + pageId,
                "https://notion.so/" + pageId, false);
    }

    // -------------------------------------------------------------------------
    // W022 — notion-stale
    // -------------------------------------------------------------------------

    @Test
    void w022_triggeredWhen_notionEnabled_andStale() throws SQLException {
        // Last sync 120 min ago, pollInterval 15 -> threshold = 45 min -> 120 > 45 -> fires
        SynthesisConfig config = notionEnabled(15);
        Instant staleTime = Instant.now().minus(120, ChronoUnit.MINUTES);
        recordSyncAt(staleTime);

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W022", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("120"),
                "Should mention minutes since last sync, got: " + issue.description());
        assertTrue(issue.description().contains("15"),
                "Should mention poll interval, got: " + issue.description());
    }

    @Test
    void w022_notTriggeredWhen_notionDisabled() throws SQLException {
        SynthesisConfig config = notionDisabled();

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertTrue(issues.isEmpty(), "W022 should not fire when Notion is disabled");
    }

    @Test
    void w022_notTriggeredWhen_recentSync() throws SQLException {
        // Last sync 5 min ago, pollInterval 15 -> threshold = 45 min -> 5 < 45 -> no fire
        SynthesisConfig config = notionEnabled(15);
        Instant recentTime = Instant.now().minus(5, ChronoUnit.MINUTES);
        recordSyncAt(recentTime);

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertTrue(issues.isEmpty(), "W022 should not fire when last sync is recent");
    }

    // -------------------------------------------------------------------------
    // W023 — notion-orphan
    // -------------------------------------------------------------------------

    @Test
    void w023_triggeredWhen_orphansExist() throws SQLException {
        SynthesisConfig config = notionEnabled(15);

        // Root page (no parent)
        recordPage("root-1", null, "notion/Root");
        // Child with valid parent
        recordPage("child-1", "root-1", "notion/Root/Child1");
        // 3 orphans — parent IDs not in the page set
        recordPage("orphan-1", "missing-parent-1", "notion/Orphan1");
        recordPage("orphan-2", "missing-parent-2", "notion/Orphan2");
        recordPage("orphan-3", "missing-parent-3", "notion/Orphan3");

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkOrphans(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W023", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("3"),
                "Should mention count of 3 orphans, got: " + issue.description());
    }

    @Test
    void w023_notTriggeredWhen_noOrphans() throws SQLException {
        SynthesisConfig config = notionEnabled(15);

        // All pages have valid parents or null parent
        recordPage("root-1", null, "notion/Root");
        recordPage("child-1", "root-1", "notion/Root/Child1");
        recordPage("child-2", "root-1", "notion/Root/Child2");

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkOrphans(WORKSPACE);

        assertTrue(issues.isEmpty(), "W023 should not fire when all pages have valid parents");
    }

    // -------------------------------------------------------------------------
    // W024 — notion-conflict
    // -------------------------------------------------------------------------

    @Test
    void w024_triggeredWhen_duplicatesExist() throws SQLException {
        SynthesisConfig config = notionEnabled(15);

        // Two pages sharing the same virtual path
        recordPage("page-1", null, "notion/Shared/Path");
        recordPage("page-2", null, "notion/Shared/Path");
        // Another duplicate pair
        recordPage("page-3", null, "notion/Another/Dup");
        recordPage("page-4", null, "notion/Another/Dup");

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkConflicts(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W024", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("2"),
                "Should mention 2 duplicate paths, got: " + issue.description());
    }

    @Test
    void w024_notTriggeredWhen_noDuplicates() throws SQLException {
        SynthesisConfig config = notionEnabled(15);

        recordPage("page-1", null, "notion/Unique/Path1");
        recordPage("page-2", null, "notion/Unique/Path2");
        recordPage("page-3", null, "notion/Unique/Path3");

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkConflicts(WORKSPACE);

        assertTrue(issues.isEmpty(), "W024 should not fire when all paths are unique");
    }

    // -------------------------------------------------------------------------
    // All checks — notion disabled
    // -------------------------------------------------------------------------

    @Test
    void allChecks_notionDisabled_noSignals() throws SQLException {
        SynthesisConfig config = notionDisabled();

        // Set up data that would trigger all three checks if enabled
        Instant staleTime = Instant.now().minus(120, ChronoUnit.MINUTES);
        recordSyncAt(staleTime);
        recordPage("orphan-1", "missing-parent", "notion/Orphan");
        recordPage("dup-1", null, "notion/Dup");
        recordPage("dup-2", null, "notion/Dup");

        NotionHealthChecks checks = new NotionHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkAll(WORKSPACE);

        assertTrue(issues.isEmpty(),
                "All checks should return empty when Notion is disabled, got " + issues.size() + " issues");
    }
}
