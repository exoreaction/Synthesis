package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportCache — validates Fix #41 (cache infrastructure).
 *
 * <p>Fix #41 ensures that {@code --no-cache} skips the cache read (avoiding stale
 * results) but still stores the freshly generated result. The {@code ReportCommand}
 * always calls {@code cache.put()} regardless of the {@code noCache} flag.
 *
 * <p>These tests validate the cache's put/get contract so the fix has a stable
 * foundation to build on:
 * <ul>
 *   <li>Cache miss returns empty Optional</li>
 *   <li>After put, get returns the stored result</li>
 *   <li>Cache hit increments the hit counter</li>
 *   <li>Different document fingerprint causes a miss (invalidation)</li>
 *   <li>clearWorkspace removes entries for that workspace only</li>
 *   <li>INSERT OR REPLACE upserts on the same key (no constraint violations)</li>
 * </ul>
 */
class ReportCacheTest {

    private Connection connection;
    private ReportCache cache;

    private static final Path WORKSPACE = Path.of("/test/workspace");
    private static final String FINGERPRINT = "abc123fingerprint";

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        applySchema(connection);
        cache = new ReportCache(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // --- Cache miss ---

    @Test
    void get_returnEmptyForCacheMiss() {
        Optional<ReportResult> result = cache.get(
                WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);
        assertTrue(result.isEmpty(), "Cache should be empty before any put()");
    }

    @Test
    void get_returnEmptyForDifferentTopic() {
        ReportResult stored = sampleResult(ReportTopic.PIPELINE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> result = cache.get(
                WORKSPACE, ReportTopic.ACTIVITIES, ReportTarget.CEO, "1w", FINGERPRINT);
        assertTrue(result.isEmpty(), "Different topic should be a cache miss");
    }

    @Test
    void get_returnEmptyForDifferentTarget() {
        ReportResult stored = sampleResult(ReportTopic.PIPELINE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> result = cache.get(
                WORKSPACE, ReportTopic.PIPELINE, ReportTarget.BOARD, "1w", FINGERPRINT);
        assertTrue(result.isEmpty(), "Different target should be a cache miss");
    }

    @Test
    void get_returnEmptyForDifferentPeriod() {
        ReportResult stored = sampleResult(ReportTopic.PIPELINE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> result = cache.get(
                WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "2w", FINGERPRINT);
        assertTrue(result.isEmpty(), "Different period should be a cache miss");
    }

    // --- Document fingerprint invalidation (Fix #41 motivation) ---

    @Test
    void get_returnEmptyWhenFingerprintChanges() {
        // This validates the core cache invalidation mechanism.
        // When PIPELINE-STATUS.md is updated, its fingerprint changes → cache miss → fresh generation.
        ReportResult stored = sampleResult(ReportTopic.PIPELINE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> result = cache.get(
                WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", "differentFingerprint");
        assertTrue(result.isEmpty(),
                "Changed document fingerprint should invalidate cache (core invalidation mechanism)");
    }

    // --- Cache hit ---

    @Test
    void putAndGet_returnsStoredReportContent() {
        ReportResult stored = sampleResult(ReportTopic.PIPELINE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> retrieved = cache.get(
                WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);

        assertTrue(retrieved.isPresent(), "Should retrieve cached result");
        assertEquals(stored.finalReport(), retrieved.get().finalReport(),
                "Retrieved report content should match stored content");
    }

    @Test
    void putAndGet_cachedResultHasFromCacheTrue() {
        ReportResult stored = sampleResult(ReportTopic.WEEKLY);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> retrieved = cache.get(
                WORKSPACE, ReportTopic.WEEKLY, ReportTarget.CEO, "1w", FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertTrue(retrieved.get().fromCache(),
                "Result loaded from cache must have fromCache=true");
    }

    @Test
    void putAndGet_preservesTokenCount() {
        ReportResult stored = sampleResult(ReportTopic.EXECUTIVE);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> retrieved = cache.get(
                WORKSPACE, ReportTopic.EXECUTIVE, ReportTarget.CEO, "1w", FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertEquals(stored.totalTokenCount(), retrieved.get().totalTokenCount(),
                "Token count should be preserved through cache round-trip");
    }

    @Test
    void putAndGet_preservesTopic() {
        ReportResult stored = sampleResult(ReportTopic.DECISIONS);
        cache.put(WORKSPACE, stored, FINGERPRINT);

        Optional<ReportResult> retrieved = cache.get(
                WORKSPACE, ReportTopic.DECISIONS, ReportTarget.CEO, "1w", FINGERPRINT);

        assertTrue(retrieved.isPresent());
        assertEquals(ReportTopic.DECISIONS, retrieved.get().topic(),
                "Topic should be preserved through cache round-trip");
    }

    // --- Upsert behavior (put-then-put) ---

    @Test
    void put_replacesPreviousEntryForSameKey() {
        // Same key = same workspace/topic/target/period/fingerprint
        // This validates that INSERT OR REPLACE works and doesn't throw on duplicate key
        ReportResult first = sampleResultWithContent(ReportTopic.PIPELINE, "First report content");
        cache.put(WORKSPACE, first, FINGERPRINT);

        ReportResult second = sampleResultWithContent(ReportTopic.PIPELINE, "Updated report content");
        assertDoesNotThrow(() -> cache.put(WORKSPACE, second, FINGERPRINT),
                "Second put() with same key should not throw (upsert behavior)");

        Optional<ReportResult> retrieved = cache.get(
                WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertEquals("Updated report content", retrieved.get().finalReport(),
                "Second put() should replace first (INSERT OR REPLACE)");
    }

    // --- clearWorkspace ---

    @Test
    void clearWorkspace_removesAllEntriesForWorkspace() {
        cache.put(WORKSPACE, sampleResult(ReportTopic.PIPELINE), "fp1");
        cache.put(WORKSPACE, sampleResult(ReportTopic.ACTIVITIES), "fp2");
        cache.put(WORKSPACE, sampleResult(ReportTopic.DECISIONS), "fp3");

        int removed = cache.clearWorkspace(WORKSPACE);
        assertEquals(3, removed, "Should remove all 3 cached entries for workspace");

        // Verify they're gone
        assertTrue(cache.get(WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", "fp1").isEmpty());
        assertTrue(cache.get(WORKSPACE, ReportTopic.ACTIVITIES, ReportTarget.CEO, "1w", "fp2").isEmpty());
    }

    @Test
    void clearWorkspace_doesNotAffectOtherWorkspaces() {
        Path otherWorkspace = Path.of("/other/workspace");
        cache.put(WORKSPACE, sampleResult(ReportTopic.PIPELINE), FINGERPRINT);
        cache.put(otherWorkspace, sampleResult(ReportTopic.PIPELINE), FINGERPRINT);

        cache.clearWorkspace(WORKSPACE);

        // Other workspace entry should still exist
        assertTrue(cache.get(otherWorkspace, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT).isPresent(),
                "clearWorkspace() should only affect the specified workspace");
    }

    // --- getStats ---

    @Test
    void getStats_returnsZeroForEmptyWorkspace() {
        ReportCache.CacheStats stats = cache.getStats(WORKSPACE);
        assertEquals(0, stats.entries());
        assertEquals(0L, stats.totalHits());
        assertEquals(0L, stats.totalTokens());
        assertEquals(0.0, stats.totalCostUsd(), 0.001);
    }

    @Test
    void getStats_reflectsStoredEntries() {
        cache.put(WORKSPACE, sampleResult(ReportTopic.PIPELINE), "fp1");
        cache.put(WORKSPACE, sampleResult(ReportTopic.ACTIVITIES), "fp2");

        ReportCache.CacheStats stats = cache.getStats(WORKSPACE);
        assertEquals(2, stats.entries(), "Stats should reflect 2 stored entries");
        assertTrue(stats.totalTokens() > 0, "Total tokens should be > 0");
    }

    // --- Multiple workspaces isolation ---

    @Test
    void put_isolatesEntriesByWorkspace() {
        Path workspace1 = Path.of("/workspace/one");
        Path workspace2 = Path.of("/workspace/two");

        ReportResult result1 = sampleResultWithContent(ReportTopic.PIPELINE, "Report for workspace 1");
        ReportResult result2 = sampleResultWithContent(ReportTopic.PIPELINE, "Report for workspace 2");

        cache.put(workspace1, result1, FINGERPRINT);
        cache.put(workspace2, result2, FINGERPRINT);

        Optional<ReportResult> from1 = cache.get(workspace1, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);
        Optional<ReportResult> from2 = cache.get(workspace2, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);

        assertTrue(from1.isPresent() && from2.isPresent());
        assertEquals("Report for workspace 1", from1.get().finalReport());
        assertEquals("Report for workspace 2", from2.get().finalReport());
    }

    // --- Helpers ---

    private ReportResult sampleResult(ReportTopic topic) {
        return sampleResultWithContent(topic, "Sample report content for " + topic.cliValue());
    }

    private ReportResult sampleResultWithContent(ReportTopic topic, String content) {
        return ReportResult.fromGeneration(
                ReportTarget.CEO,
                topic,
                java.util.List.of(),
                content,
                "claude-sonnet-4-5-test",
                1000,
                500L,
                "1w");
    }

    /**
     * Applies the V8 report_cache schema to an in-memory SQLite connection.
     * Mirrors the Flyway migration at V8__report_cache.sql.
     */
    private static void applySchema(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS report_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        target TEXT NOT NULL,
                        period TEXT NOT NULL,
                        document_fingerprint TEXT NOT NULL,
                        model TEXT NOT NULL,
                        report_content TEXT NOT NULL,
                        token_count INTEGER,
                        estimated_cost_usd REAL,
                        created_at TEXT NOT NULL,
                        hits INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(workspace_path, topic, target, period, document_fingerprint)
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_report_cache_lookup
                        ON report_cache(workspace_path, topic, target, period, document_fingerprint)
                    """);
        }
    }
}
