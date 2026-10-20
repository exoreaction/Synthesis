package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssueLink;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JiraSyncState} — DAO for jira_issues, jira_issue_links, and
 * jira_sync_state tables.
 */
class JiraSyncStateTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private JiraSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new JiraSyncState(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // recordIssue tests
    // -----------------------------------------------------------------------

    @Test
    void recordIssue_thenQueryBack() throws SQLException {
        Instant now = Instant.now();
        syncState.recordIssue("ws1", "PROJ-1", "10001", "PROJ", "Fix bug", "Open",
                "PROJ/PROJ-1.md", now, "abc123", "https://x.atlassian.net/browse/PROJ-1");

        List<String> orphans = syncState.getOrphanIssueKeys("ws1", Set.of("PROJ-1"));
        assertTrue(orphans.isEmpty(), "Issue should not be orphan when in liveIssueKeys");
    }

    @Test
    void recordIssue_upsertUpdatesExisting() throws SQLException {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        syncState.recordIssue("ws1", "PROJ-1", "10001", "PROJ", "Old summary", "Open",
                "PROJ/PROJ-1.md", t1, "hash1", null);
        syncState.recordIssue("ws1", "PROJ-1", "10001", "PROJ", "New summary", "Done",
                "PROJ/PROJ-1.md", t2, "hash2", null);

        List<String> orphans = syncState.getOrphanIssueKeys("ws1", Set.of());
        assertEquals(1, orphans.size(), "Should be exactly one issue (upsert, not duplicate)");
        assertEquals("PROJ-1", orphans.get(0));
    }

    @Test
    void recordIssue_multipleWorkspaces_isolated() throws SQLException {
        Instant now = Instant.now();
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "S", null, "PROJ/PROJ-1.md", now, null, null);
        syncState.recordIssue("ws2", "PROJ-2", null, "PROJ", "S", null, "PROJ/PROJ-2.md", now, null, null);

        assertEquals(1, syncState.getOrphanIssueKeys("ws1", Set.of()).size());
        assertEquals(1, syncState.getOrphanIssueKeys("ws2", Set.of()).size());
    }

    // -----------------------------------------------------------------------
    // upsertSyncState / getLastSyncTime tests
    // -----------------------------------------------------------------------

    @Test
    void upsertSyncState_thenGetLastSyncTime() throws SQLException {
        Instant syncTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        syncState.upsertSyncState("ws1", "project = PROJ", syncTime, 42, "ok", null);

        Optional<Instant> result = syncState.getLastSyncTime("ws1");
        assertTrue(result.isPresent());
        assertEquals(syncTime, result.get());
    }

    @Test
    void getLastSyncTime_noRecord_returnsEmpty() throws SQLException {
        assertTrue(syncState.getLastSyncTime("nonexistent").isEmpty());
    }

    // -----------------------------------------------------------------------
    // getOrphanIssueKeys tests
    // -----------------------------------------------------------------------

    @Test
    void getOrphanIssueKeys_returnsDeletedOrOutOfScopeIssues() throws SQLException {
        Instant now = Instant.now();
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "A", null, "PROJ/PROJ-1.md", now, null, null);
        syncState.recordIssue("ws1", "PROJ-2", null, "PROJ", "B", null, "PROJ/PROJ-2.md", now, null, null);
        syncState.recordIssue("ws1", "PROJ-3", null, "PROJ", "C", null, "PROJ/PROJ-3.md", now, null, null);

        List<String> orphans = syncState.getOrphanIssueKeys("ws1", Set.of("PROJ-1", "PROJ-3"));

        assertEquals(1, orphans.size());
        assertEquals("PROJ-2", orphans.get(0));
    }

    // -----------------------------------------------------------------------
    // getStalePaths tests
    // -----------------------------------------------------------------------

    @Test
    void getStalePaths_justSynced_notStale() throws SQLException {
        Instant oldUpdate = Instant.now().minus(30, ChronoUnit.DAYS);
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "A", null, "PROJ/PROJ-1.md", oldUpdate, null, null);

        List<String> stale = syncState.getStalePaths("ws1", Duration.ofHours(1));
        assertTrue(stale.isEmpty(), "Just-synced issue should not be stale with 1h threshold");
    }

    // -----------------------------------------------------------------------
    // getDuplicateVirtualPaths (defensive invariant check)
    // -----------------------------------------------------------------------

    @Test
    void getDuplicateVirtualPaths_detectsDuplicates() throws SQLException {
        Instant now = Instant.now();
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "A", null, "PROJ/Shared.md", now, null, null);
        syncState.recordIssue("ws1", "PROJ-2", null, "PROJ", "B", null, "PROJ/Shared.md", now, null, null);

        Set<String> duplicates = syncState.getDuplicateVirtualPaths("ws1");
        assertEquals(1, duplicates.size());
        assertTrue(duplicates.contains("PROJ/Shared.md"));
    }

    @Test
    void getDuplicateVirtualPaths_normalCase_noDuplicates() throws SQLException {
        Instant now = Instant.now();
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "A", null, "PROJ/PROJ-1.md", now, null, null);
        syncState.recordIssue("ws1", "PROJ-2", null, "PROJ", "B", null, "PROJ/PROJ-2.md", now, null, null);

        assertTrue(syncState.getDuplicateVirtualPaths("ws1").isEmpty());
    }

    // -----------------------------------------------------------------------
    // Issue links: recordIssueLinks / getIssueLinks / getDanglingLinks
    // -----------------------------------------------------------------------

    @Test
    void recordIssueLinks_thenGetIssueLinks_roundTrips() throws SQLException {
        List<JiraIssueLink> links = List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-2", "Other issue"),
                new JiraIssueLink("is blocked by", "inward", "PROJ-3", "Blocking issue")
        );
        syncState.recordIssueLinks("ws1", "PROJ-1", links);

        List<JiraIssueLink> loaded = syncState.getIssueLinks("ws1", "PROJ-1");
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().anyMatch(l -> l.targetKey().equals("PROJ-2") && l.relationship().equals("blocks")));
        assertTrue(loaded.stream().anyMatch(l -> l.targetKey().equals("PROJ-3") && l.relationship().equals("is blocked by")));
    }

    @Test
    void recordIssueLinks_replacesStaleLinks() throws SQLException {
        syncState.recordIssueLinks("ws1", "PROJ-1", List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-2", "Old link")));

        // Re-sync with a different set of links — old one should be gone
        syncState.recordIssueLinks("ws1", "PROJ-1", List.of(
                new JiraIssueLink("relates to", "outward", "PROJ-5", "New link")));

        List<JiraIssueLink> loaded = syncState.getIssueLinks("ws1", "PROJ-1");
        assertEquals(1, loaded.size());
        assertEquals("PROJ-5", loaded.get(0).targetKey());
    }

    @Test
    void getDanglingLinks_targetNotSynced_isDangling() throws SQLException {
        Instant now = Instant.now();
        // PROJ-1 is synced, but its link target PROJ-99 is not (out of JQL scope)
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "A", null, "PROJ/PROJ-1.md", now, null, null);
        syncState.recordIssueLinks("ws1", "PROJ-1", List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-99", "Out of scope")));

        List<String> dangling = syncState.getDanglingLinks("ws1");
        assertEquals(1, dangling.size());
        assertTrue(dangling.get(0).contains("PROJ-1"));
        assertTrue(dangling.get(0).contains("PROJ-99"));
    }

    @Test
    void getDanglingLinks_targetSynced_notDangling() throws SQLException {
        Instant now = Instant.now();
        syncState.recordIssue("ws1", "PROJ-1", null, "PROJ", "A", null, "PROJ/PROJ-1.md", now, null, null);
        syncState.recordIssue("ws1", "PROJ-2", null, "PROJ", "B", null, "PROJ/PROJ-2.md", now, null, null);
        syncState.recordIssueLinks("ws1", "PROJ-1", List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-2", "In scope")));

        assertTrue(syncState.getDanglingLinks("ws1").isEmpty());
    }
}
