package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.summary.CodebaseProfile.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SummaryCache — in-memory SQLite put/get, invalidation, TTL, workspace isolation.
 * Uses the V5 schema (summary_cache table).
 */
class SummaryCacheTest {

    private Connection connection;
    private SummaryCache cache;

    private static final Path WORKSPACE = Path.of("/test/summary-workspace");
    private static final String FINGERPRINT = "summaryfingerprint123";

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        applySchema(connection);
        cache = new SummaryCache(connection, 0); // 0 = no TTL
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
        Optional<SummaryResult> result = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT);
        assertTrue(result.isEmpty(), "Empty cache should return empty Optional");
    }

    @Test
    void get_differentLevel_returnsEmpty() {
        SummaryResult stored = sampleResult(SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL);
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> result = cache.get(
                WORKSPACE, SummaryLevel.DEVELOPER, SummaryPerspective.GENERAL, FINGERPRINT);
        assertTrue(result.isEmpty(), "Different level should be a cache miss");
    }

    @Test
    void get_differentPerspective_returnsEmpty() {
        SummaryResult stored = sampleResult(SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL);
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> result = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.ARCHITECT, FINGERPRINT);
        assertTrue(result.isEmpty(), "Different perspective should be a cache miss");
    }

    @Test
    void get_differentFingerprint_returnsEmpty() {
        SummaryResult stored = sampleResult(SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL);
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> result = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "differentFingerprint");
        assertTrue(result.isEmpty(), "Changed fingerprint should invalidate cache");
    }

    // --- Cache hit ---

    @Test
    void putAndGet_returnsStoredResult() {
        SummaryResult stored = sampleResult(SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL);
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> retrieved = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT);
        assertTrue(retrieved.isPresent(), "Should find cached result");
    }

    @Test
    void putAndGet_cachedResultHasFromCacheTrue() {
        SummaryResult stored = sampleResult(SummaryLevel.MANAGER, SummaryPerspective.ARCHITECT);
        cache.put(WORKSPACE, SummaryLevel.MANAGER, SummaryPerspective.ARCHITECT, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> retrieved = cache.get(
                WORKSPACE, SummaryLevel.MANAGER, SummaryPerspective.ARCHITECT, FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertTrue(retrieved.get().fromCache(), "Cached result must have fromCache=true");
    }

    @Test
    void putAndGet_preservesLevel() {
        SummaryResult stored = sampleResult(SummaryLevel.DEVELOPER, SummaryPerspective.SECURITY);
        cache.put(WORKSPACE, SummaryLevel.DEVELOPER, SummaryPerspective.SECURITY, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> retrieved = cache.get(
                WORKSPACE, SummaryLevel.DEVELOPER, SummaryPerspective.SECURITY, FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertEquals(SummaryLevel.DEVELOPER, retrieved.get().level(), "Level should be preserved");
    }

    @Test
    void putAndGet_preservesPerspective() {
        SummaryResult stored = sampleResult(SummaryLevel.EXECUTIVE, SummaryPerspective.DEVOPS);
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.DEVOPS, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> retrieved = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.DEVOPS, FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertEquals(SummaryPerspective.DEVOPS, retrieved.get().perspective(), "Perspective should be preserved");
    }

    @Test
    void putAndGet_preservesAiSummary() {
        SummaryResult stored = sampleResultWithAiSummary(
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "This is the AI summary text");
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT,
                stored, "claude-test");

        Optional<SummaryResult> retrieved = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertEquals("This is the AI summary text", retrieved.get().aiSummary(),
                "AI summary should be preserved");
    }

    // --- Upsert ---

    @Test
    void put_replacesPreviousEntryForSameKey() {
        SummaryResult first = sampleResultWithAiSummary(
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "First summary");
        cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT,
                first, "claude-test");

        SummaryResult second = sampleResultWithAiSummary(
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "Updated summary");
        assertDoesNotThrow(() -> cache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL,
                FINGERPRINT, second, "claude-test"), "Second put should upsert without throwing");

        Optional<SummaryResult> retrieved = cache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, FINGERPRINT);
        assertTrue(retrieved.isPresent());
        assertEquals("Updated summary", retrieved.get().aiSummary(), "Second put should replace first");
    }

    // --- Workspace isolation ---

    @Test
    void put_isolatesEntriesByWorkspace() {
        Path workspace1 = Path.of("/workspace/summary-alpha");
        Path workspace2 = Path.of("/workspace/summary-beta");

        SummaryResult result1 = sampleResultWithAiSummary(
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "Summary for alpha");
        SummaryResult result2 = sampleResultWithAiSummary(
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "Summary for beta");

        cache.put(workspace1, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL,
                FINGERPRINT, result1, "claude-test");
        cache.put(workspace2, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL,
                FINGERPRINT, result2, "claude-test");

        Optional<SummaryResult> from1 = cache.get(workspace1, SummaryLevel.EXECUTIVE,
                SummaryPerspective.GENERAL, FINGERPRINT);
        Optional<SummaryResult> from2 = cache.get(workspace2, SummaryLevel.EXECUTIVE,
                SummaryPerspective.GENERAL, FINGERPRINT);

        assertTrue(from1.isPresent() && from2.isPresent(), "Both workspaces should have entries");
        assertEquals("Summary for alpha", from1.get().aiSummary());
        assertEquals("Summary for beta", from2.get().aiSummary());
    }

    // --- generateIndexFingerprint ---

    @Test
    void generateIndexFingerprint_nonExistentPath_returnsNoIndex() {
        String fingerprint = SummaryCache.generateIndexFingerprint(Path.of("/nonexistent/path"));
        assertEquals("no-index", fingerprint, "Non-existent index path should return 'no-index'");
    }

    @Test
    void generateIndexFingerprint_returnsNonBlankString() {
        // With a real path, it should return some non-blank fingerprint
        String fingerprint = SummaryCache.generateIndexFingerprint(Path.of("/tmp"));
        assertNotNull(fingerprint);
        assertFalse(fingerprint.isBlank());
    }

    @Test
    void generateIndexFingerprint_sameCallReturnsSameValue() {
        // Calling twice on the same path should return the same value
        String fp1 = SummaryCache.generateIndexFingerprint(Path.of("/nonexistent/path"));
        String fp2 = SummaryCache.generateIndexFingerprint(Path.of("/nonexistent/path"));
        assertEquals(fp1, fp2, "Same path should produce same fingerprint");
    }

    // --- TTL ---

    @Test
    void cache_withZeroTtl_neverExpires() throws Exception {
        SummaryCache noTtlCache = new SummaryCache(connection, 0);
        SummaryResult stored = sampleResult(SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL);
        noTtlCache.put(WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL,
                "ttl-fp", stored, "claude-test");

        Optional<SummaryResult> retrieved = noTtlCache.get(
                WORKSPACE, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, "ttl-fp");
        assertTrue(retrieved.isPresent(), "Zero TTL should never expire");
    }

    // --- helpers ---

    private static SummaryResult sampleResult(SummaryLevel level, SummaryPerspective perspective) {
        return sampleResultWithAiSummary(level, perspective, "Sample AI summary");
    }

    private static SummaryResult sampleResultWithAiSummary(SummaryLevel level,
                                                              SummaryPerspective perspective,
                                                              String aiSummary) {
        Profile profile = new Profile(
                new ScaleMetrics(100, 1024L * 1024, Map.of(), Map.of("java", 80L), List.of(), 10),
                new QualityMetrics(0.75, 0.15, 15, 85, 5, List.of()),
                new ArchitectureMetrics(5, 0, 0, Map.of(), 2.5),
                List.of(new HealthIndicator("Overall", "green", "Healthy codebase")),
                List.of(),
                List.of("Consider adding more tests"),
                Instant.now()
        );
        return new SummaryResult(profile, aiSummary, level, perspective, null,
                Instant.now(), 500L, false, FINGERPRINT);
    }

    private static void applySchema(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS summary_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        summary_level TEXT NOT NULL,
                        perspective TEXT NOT NULL,
                        index_fingerprint TEXT NOT NULL,
                        profile_json TEXT NOT NULL,
                        ai_summary TEXT,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        generation_time_ms INTEGER NOT NULL,
                        model_used TEXT,
                        expires_at TIMESTAMP,
                        hits INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(workspace_path, summary_level, perspective, index_fingerprint)
                    )
                    """);
        }
    }
}
