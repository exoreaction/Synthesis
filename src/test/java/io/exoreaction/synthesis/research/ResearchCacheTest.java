package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchCache — in-memory SQLite put/get, invalidation, workspace isolation.
 * Uses the V6 schema (research_cache table).
 */
class ResearchCacheTest {

    private Connection connection;
    private ResearchCache cache;

    private static final Path WORKSPACE = Path.of("/test/workspace");
    private static final String FINGERPRINT = "testfingerprint123";
    // Must match passNames() from sampleResult: ["architecture", "synthesis"]
    private static final String PASSES = "architecture,synthesis";

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        applySchema(connection);
        cache = new ResearchCache(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // --- Cache miss ---

    @Test
    void get_emptyCache_returnsEmpty() {
        Optional<ResearchResult> result = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);
        assertTrue(result.isEmpty(), "Empty cache should return empty Optional");
    }

    @Test
    void get_differentTarget_returnsEmpty() {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> result = cache.get(
                WORKSPACE, ResearchTarget.NOTEBOOKLM_INFOGRAPHIC,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);
        assertTrue(result.isEmpty(), "Different target should be a cache miss");
    }

    @Test
    void get_differentTopic_returnsEmpty() {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> result = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.ARCHITECTURE, PASSES, FINGERPRINT);
        assertTrue(result.isEmpty(), "Different topic should be a cache miss");
    }

    @Test
    void get_differentPasses_returnsEmpty() {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        // PASSES = "architecture,synthesis"; lookup with different passes
        Optional<ResearchResult> result = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, "security,synthesis", FINGERPRINT);
        assertTrue(result.isEmpty(), "Different passes should be a cache miss");
    }

    @Test
    void get_differentFingerprint_returnsEmpty() {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> result = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, "differentFingerprint");
        assertTrue(result.isEmpty(), "Changed fingerprint should invalidate cache");
    }

    // --- Cache hit ---

    @Test
    void putAndGet_returnsStoredReport() {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> retrieved = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);

        assertTrue(retrieved.isPresent(), "Should find cached result");
        assertEquals(stored.finalReport(), retrieved.get().finalReport());
    }

    @Test
    void putAndGet_cachedResultHasFromCacheTrue() {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.ARCHITECTURE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> retrieved = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.ARCHITECTURE, PASSES, FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertTrue(retrieved.get().fromCache(), "Retrieved result must have fromCache=true");
    }

    @Test
    void putAndGet_preservesTarget() {
        ResearchResult stored = sampleResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, ResearchTopic.SECURITY);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> retrieved = cache.get(
                WORKSPACE, ResearchTarget.NOTEBOOKLM_INFOGRAPHIC,
                ResearchTopic.SECURITY, PASSES, FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertEquals(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, retrieved.get().target());
    }

    @Test
    void putAndGet_preservesTotalTokenCount() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch", 500),
                new ResearchPassResult("synthesis", "syn", 300)
        );
        ResearchResult stored = ResearchResult.fromCache(
                ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS,
                passes, "report", "claude-test", 800, 0.01, Instant.now(), 1000L);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> retrieved = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertEquals(800, retrieved.get().totalTokenCount(), "Token count should be preserved");
    }

    @Test
    void putAndGet_preservesModel() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch", 100),
                new ResearchPassResult("synthesis", "syn", 50)
        );
        ResearchResult stored = ResearchResult.fromCache(
                ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.QUALITY,
                passes, "report", "claude-opus-test", 150, 0.0, Instant.now(), 0L);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ResearchResult> retrieved = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.QUALITY, PASSES, FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertEquals("claude-opus-test", retrieved.get().model(), "Model should be preserved");
    }

    // --- Upsert behavior ---

    @Test
    void put_replacesPreviousEntryForSameKey() {
        ResearchResult first = sampleResultWithReport(ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, "First report");
        cache.put(WORKSPACE, first, FINGERPRINT);

        ResearchResult second = sampleResultWithReport(ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, "Updated report");
        assertDoesNotThrow(() -> cache.put(WORKSPACE, second, FINGERPRINT),
                "Second put with same key should upsert without throwing");

        Optional<ResearchResult> retrieved = cache.get(
                WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertEquals("Updated report", retrieved.get().finalReport(),
                "Second put should replace first (upsert)");
    }

    // --- Workspace isolation ---

    @Test
    void put_isolatesEntriesByWorkspace() {
        Path workspace1 = Path.of("/workspace/alpha");
        Path workspace2 = Path.of("/workspace/beta");

        ResearchResult result1 = sampleResultWithReport(ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, "Report for alpha");
        ResearchResult result2 = sampleResultWithReport(ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, "Report for beta");

        cache.put(workspace1, result1, FINGERPRINT);
        cache.put(workspace2, result2, FINGERPRINT);

        Optional<ResearchResult> from1 = cache.get(workspace1, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);
        Optional<ResearchResult> from2 = cache.get(workspace2, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);

        assertTrue(from1.isPresent() && from2.isPresent());
        assertEquals("Report for alpha", from1.get().finalReport());
        assertEquals("Report for beta", from2.get().finalReport());
    }

    // --- clearWorkspace ---

    @Test
    void clearWorkspace_removesAllEntriesForWorkspace() {
        cache.put(WORKSPACE, sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS), "fp1");
        cache.put(WORKSPACE, sampleResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, ResearchTopic.SECURITY), "fp2");

        int removed = cache.clearWorkspace(WORKSPACE);
        assertEquals(2, removed, "clearWorkspace should remove all entries for the workspace");

        assertTrue(cache.get(WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, "architecture,synthesis", "fp1").isEmpty());
    }

    @Test
    void clearWorkspace_doesNotAffectOtherWorkspaces() {
        Path otherWorkspace = Path.of("/other/workspace");
        cache.put(WORKSPACE, sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS), FINGERPRINT);
        cache.put(otherWorkspace, sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS), FINGERPRINT);

        cache.clearWorkspace(WORKSPACE);

        assertTrue(cache.get(otherWorkspace, ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT).isPresent(),
                "clearWorkspace should not affect other workspaces");
    }

    // --- getStats ---

    @Test
    void getStats_emptyWorkspace_returnsZero() {
        ResearchCache.CacheStats stats = cache.getStats(WORKSPACE);
        assertEquals(0, stats.entries());
        assertEquals(0L, stats.totalHits());
    }

    @Test
    void getStats_afterPut_reflectsStoredEntries() {
        cache.put(WORKSPACE, sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS), "fp1");
        cache.put(WORKSPACE, sampleResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, ResearchTopic.ARCHITECTURE), "fp2");

        ResearchCache.CacheStats stats = cache.getStats(WORKSPACE);
        assertEquals(2, stats.entries(), "Stats should reflect 2 stored entries");
    }

    // --- Hit counter ---

    @Test
    void get_incrementsHitCounterOnEachAccess() throws Exception {
        ResearchResult stored = sampleResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        cache.get(WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);
        cache.get(WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);
        cache.get(WORKSPACE, ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS, PASSES, FINGERPRINT);

        try (var stmt = connection.prepareStatement(
                "SELECT hits FROM research_cache WHERE workspace_path = ?")) {
            stmt.setString(1, WORKSPACE.toString());
            var rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(3, rs.getInt("hits"), "Hit counter should be 3 after three get() calls");
        }
    }

    // --- helpers ---

    private ResearchResult sampleResult(ResearchTarget target, ResearchTopic topic) {
        return sampleResultWithReport(target, topic, "Sample report for " + target.cliValue() + "/" + topic.cliValue());
    }

    private ResearchResult sampleResultWithReport(ResearchTarget target, ResearchTopic topic, String report) {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch", 100),
                new ResearchPassResult("synthesis", "syn", 50)
        );
        return ResearchResult.fromCache(
                target, topic, passes, report, "claude-test", 150, 0.001, Instant.now(), 500L);
    }

    private static void applySchema(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS research_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        target TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        passes TEXT NOT NULL,
                        index_fingerprint TEXT NOT NULL,
                        model TEXT NOT NULL,
                        report_content TEXT NOT NULL,
                        pass_results TEXT NOT NULL,
                        token_count INTEGER,
                        estimated_cost_usd REAL,
                        created_at TEXT NOT NULL,
                        hits INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(workspace_path, target, topic, passes, index_fingerprint)
                    )
                    """);
        }
    }
}
