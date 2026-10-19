package io.exoreaction.synthesis.confluence;

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
 * Tests for {@link ConfluenceHealthChecks}: W025 (stale), W026 (orphan), W027 (conflict).
 *
 * <p>Mirrors {@code NotionHealthChecksTest} exactly — these checks are generic over
 * "page with a parent and a virtual path," not Confluence-specific.
 */
class ConfluenceHealthChecksTest {

    private static final String WORKSPACE = "test-workspace";

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private ConfluenceSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new ConfluenceSyncState(db);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SynthesisConfig confluenceEnabled(int pollIntervalMinutes) {
        SynthesisConfig config = new SynthesisConfig();
        config.getConfluence().setEnabled(true);
        config.getConfluence().setPollIntervalMinutes(pollIntervalMinutes);
        return config;
    }

    private SynthesisConfig confluenceDisabled() {
        SynthesisConfig config = new SynthesisConfig();
        config.getConfluence().setEnabled(false);
        return config;
    }

    private void recordSyncAt(Instant time) throws SQLException {
        syncState.upsertSyncState(WORKSPACE, "ENG", time, 10, "ok", null);
    }

    private void recordPage(String pageId, String parentPageId, String virtualPath)
            throws SQLException {
        syncState.recordPage(WORKSPACE, pageId, "ENG", "Title " + pageId, parentPageId,
                virtualPath, Instant.now(), "hash-" + pageId,
                "https://example.atlassian.net/wiki/spaces/ENG/pages/" + pageId, null);
    }

    // -------------------------------------------------------------------------
    // W025 — confluence-stale
    // -------------------------------------------------------------------------

    @Test
    void w025_triggeredWhen_confluenceEnabled_andStale() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);
        Instant staleTime = Instant.now().minus(120, ChronoUnit.MINUTES);
        recordSyncAt(staleTime);

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W025", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("120"));
        assertTrue(issue.description().contains("15"));
    }

    @Test
    void w025_notTriggeredWhen_confluenceDisabled() throws SQLException {
        SynthesisConfig config = confluenceDisabled();

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertTrue(issues.isEmpty(), "W025 should not fire when Confluence is disabled");
    }

    @Test
    void w025_notTriggeredWhen_recentSync() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);
        Instant recentTime = Instant.now().minus(5, ChronoUnit.MINUTES);
        recordSyncAt(recentTime);

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertTrue(issues.isEmpty(), "W025 should not fire when last sync is recent");
    }

    @Test
    void w025_triggeredWhen_neverSynced() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkStale(WORKSPACE);

        assertEquals(1, issues.size());
        assertEquals("W025", issues.get(0).code());
    }

    // -------------------------------------------------------------------------
    // W026 — confluence-orphan
    // -------------------------------------------------------------------------

    @Test
    void w026_triggeredWhen_orphansExist() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);

        recordPage("root-1", null, "ENG/Root");
        recordPage("child-1", "root-1", "ENG/Root/Child1");
        recordPage("orphan-1", "missing-parent-1", "ENG/Orphan1");
        recordPage("orphan-2", "missing-parent-2", "ENG/Orphan2");
        recordPage("orphan-3", "missing-parent-3", "ENG/Orphan3");

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkOrphans(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W026", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("3"));
    }

    @Test
    void w026_notTriggeredWhen_noOrphans() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);

        recordPage("root-1", null, "ENG/Root");
        recordPage("child-1", "root-1", "ENG/Root/Child1");
        recordPage("child-2", "root-1", "ENG/Root/Child2");

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkOrphans(WORKSPACE);

        assertTrue(issues.isEmpty(), "W026 should not fire when all pages have valid parents");
    }

    // -------------------------------------------------------------------------
    // W027 — confluence-conflict
    // -------------------------------------------------------------------------

    @Test
    void w027_triggeredWhen_duplicatesExist() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);

        recordPage("page-1", null, "ENG/Shared/Path");
        recordPage("page-2", null, "ENG/Shared/Path");
        recordPage("page-3", null, "ENG/Another/Dup");
        recordPage("page-4", null, "ENG/Another/Dup");

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkConflicts(WORKSPACE);

        assertEquals(1, issues.size());
        HealthIssue issue = issues.get(0);
        assertEquals("W027", issue.code());
        assertEquals(HealthIssue.Severity.WARNING, issue.severity());
        assertTrue(issue.description().contains("2"));
    }

    @Test
    void w027_notTriggeredWhen_noDuplicates() throws SQLException {
        SynthesisConfig config = confluenceEnabled(15);

        recordPage("page-1", null, "ENG/Unique/Path1");
        recordPage("page-2", null, "ENG/Unique/Path2");
        recordPage("page-3", null, "ENG/Unique/Path3");

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkConflicts(WORKSPACE);

        assertTrue(issues.isEmpty(), "W027 should not fire when all paths are unique");
    }

    // -------------------------------------------------------------------------
    // All checks — confluence disabled
    // -------------------------------------------------------------------------

    @Test
    void allChecks_confluenceDisabled_noSignals() throws SQLException {
        SynthesisConfig config = confluenceDisabled();

        Instant staleTime = Instant.now().minus(120, ChronoUnit.MINUTES);
        recordSyncAt(staleTime);
        recordPage("orphan-1", "missing-parent", "ENG/Orphan");
        recordPage("dup-1", null, "ENG/Dup");
        recordPage("dup-2", null, "ENG/Dup");

        ConfluenceHealthChecks checks = new ConfluenceHealthChecks(config, db);
        List<HealthIssue> issues = checks.checkAll(WORKSPACE);

        assertTrue(issues.isEmpty(),
                "All checks should return empty when Confluence is disabled, got " + issues.size() + " issues");
    }
}
