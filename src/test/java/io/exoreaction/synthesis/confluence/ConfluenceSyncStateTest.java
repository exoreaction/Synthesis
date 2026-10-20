package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.db.SynthesisDatabase;
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
 * Tests for {@link ConfluenceSyncState} — DAO for confluence_pages and
 * confluence_sync_state tables.
 *
 * <p>Mirrors {@code NotionSyncStateTest}: in-memory SQLite (via {@link SynthesisDatabase}
 * with a temp directory) with Flyway migrations applied automatically
 * (see {@code V26__confluence_workspace_source.sql}).
 */
class ConfluenceSyncStateTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private ConfluenceSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new ConfluenceSyncState(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // recordPage tests
    // -----------------------------------------------------------------------

    @Test
    void recordPage_thenQueryBack() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "131073", "ENG", "Architecture",
                null, "ENG/Architecture.md", now, "abc123",
                "https://example.atlassian.net/wiki/spaces/ENG/pages/131073", null);

        List<String> orphans = syncState.getOrphanPageIds("ws1", Set.of("131073"));
        assertTrue(orphans.isEmpty(), "Page should not be orphan when in livePageIds");
    }

    @Test
    void recordPage_upsertUpdatesExisting() throws SQLException {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        syncState.recordPage("ws1", "131073", "ENG", "Old Title",
                null, "ENG/Old.md", t1, "hash1", null, null);
        syncState.recordPage("ws1", "131073", "ENG", "New Title",
                null, "ENG/New.md", t2, "hash2", null, "draft");

        List<String> orphans = syncState.getOrphanPageIds("ws1", Set.of());
        assertEquals(1, orphans.size(), "Should be exactly one page (upsert, not duplicate)");
        assertEquals("131073", orphans.get(0));
    }

    @Test
    void recordPage_multipleWorkspaces_isolated() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "131073", "ENG", "Title", null, "ENG/A.md", now, null, null, null);
        syncState.recordPage("ws2", "131074", "ENG", "Title", null, "ENG/A.md", now, null, null, null);

        List<String> orphansWs1 = syncState.getOrphanPageIds("ws1", Set.of());
        assertEquals(1, orphansWs1.size());
        assertEquals("131073", orphansWs1.get(0));

        List<String> orphansWs2 = syncState.getOrphanPageIds("ws2", Set.of());
        assertEquals(1, orphansWs2.size());
        assertEquals("131074", orphansWs2.get(0));
    }

    // -----------------------------------------------------------------------
    // upsertSyncState / getLastSyncTime tests
    // -----------------------------------------------------------------------

    @Test
    void upsertSyncState_thenGetLastSyncTime() throws SQLException {
        Instant syncTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        syncState.upsertSyncState("ws1", "ENG,PROD", syncTime, 42, "ok", null);

        Optional<Instant> result = syncState.getLastSyncTime("ws1");
        assertTrue(result.isPresent());
        assertEquals(syncTime, result.get());
    }

    @Test
    void getLastSyncTime_noRecord_returnsEmpty() throws SQLException {
        Optional<Instant> result = syncState.getLastSyncTime("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void upsertSyncState_updatesExisting() throws SQLException {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        syncState.upsertSyncState("ws1", "ENG", t1, 10, "ok", null);
        syncState.upsertSyncState("ws1", "ENG", t2, 50, "ok", null);

        Optional<Instant> result = syncState.getLastSyncTime("ws1");
        assertTrue(result.isPresent());
        assertEquals(t2, result.get());
    }

    // -----------------------------------------------------------------------
    // getOrphanPageIds tests
    // -----------------------------------------------------------------------

    @Test
    void getOrphanPageIds_returnsDeletedPages() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "A", null, "A.md", now, null, null, null);
        syncState.recordPage("ws1", "page-B", "ENG", "B", null, "B.md", now, null, null, null);
        syncState.recordPage("ws1", "page-C", "ENG", "C", null, "C.md", now, null, null, null);

        Set<String> live = Set.of("page-A", "page-C");
        List<String> orphans = syncState.getOrphanPageIds("ws1", live);

        assertEquals(1, orphans.size());
        assertEquals("page-B", orphans.get(0));
    }

    @Test
    void getOrphanPageIds_allLive_returnsEmpty() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "A", null, "A.md", now, null, null, null);

        List<String> orphans = syncState.getOrphanPageIds("ws1", Set.of("page-A"));
        assertTrue(orphans.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getStalePaths tests
    // -----------------------------------------------------------------------

    @Test
    void getStalePaths_justSynced_notStale() throws SQLException {
        Instant oldEdit = Instant.now().minus(30, ChronoUnit.DAYS);
        syncState.recordPage("ws1", "page-old", "ENG", "Old", null, "Old.md", oldEdit, null, null, null);

        List<String> stale = syncState.getStalePaths("ws1", Duration.ofHours(1));
        assertTrue(stale.isEmpty(), "Just-synced page should not be stale with 1h threshold");
    }

    @Test
    void getStalePaths_emptyWorkspace_returnsEmpty() throws SQLException {
        List<String> stale = syncState.getStalePaths("ws1", Duration.ofHours(1));
        assertTrue(stale.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getDuplicateVirtualPaths tests
    // -----------------------------------------------------------------------

    @Test
    void getDuplicateVirtualPaths_detectsDuplicates() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "Notes", null, "ENG/Notes.md", now, null, null, null);
        syncState.recordPage("ws1", "page-B", "ENG", "Notes Copy", null, "ENG/Notes.md", now, null, null, null);

        Set<String> duplicates = syncState.getDuplicateVirtualPaths("ws1");
        assertEquals(1, duplicates.size());
        assertTrue(duplicates.contains("ENG/Notes.md"));
    }

    @Test
    void getDuplicateVirtualPaths_noDuplicates_returnsEmpty() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "Alpha", null, "ENG/Alpha.md", now, null, null, null);
        syncState.recordPage("ws1", "page-B", "ENG", "Beta", null, "ENG/Beta.md", now, null, null, null);

        Set<String> duplicates = syncState.getDuplicateVirtualPaths("ws1");
        assertTrue(duplicates.isEmpty());
    }

    @Test
    void getDuplicateVirtualPaths_crossWorkspace_notDuplicate() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "Notes", null, "ENG/Notes.md", now, null, null, null);
        syncState.recordPage("ws2", "page-B", "ENG", "Notes", null, "ENG/Notes.md", now, null, null, null);

        Set<String> duplicatesWs1 = syncState.getDuplicateVirtualPaths("ws1");
        assertTrue(duplicatesWs1.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getPageCount / getTotalPages / getAllPageIds
    // -----------------------------------------------------------------------

    @Test
    void getPageCount_countsRecordedPages() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "A", null, "A.md", now, null, null, null);
        syncState.recordPage("ws1", "page-B", "ENG", "B", null, "B.md", now, null, null, null);

        assertEquals(2, syncState.getPageCount("ws1"));
    }

    @Test
    void getTotalPages_returnsSyncStateValue() throws SQLException {
        syncState.upsertSyncState("ws1", "ENG", Instant.now(), 7, "ok", null);
        assertEquals(7, syncState.getTotalPages("ws1"));
    }

    @Test
    void getAllPageIds_returnsAllTrackedIds() throws SQLException {
        Instant now = Instant.now();
        syncState.recordPage("ws1", "page-A", "ENG", "A", null, "A.md", now, null, null, null);
        syncState.recordPage("ws1", "page-B", "ENG", "B", null, "B.md", now, null, null, null);

        Set<String> ids = syncState.getAllPageIds("ws1");
        assertEquals(Set.of("page-A", "page-B"), ids);
    }
}
