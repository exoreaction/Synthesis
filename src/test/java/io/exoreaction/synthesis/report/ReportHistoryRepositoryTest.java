package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportHistoryRepository — validates issue #250 (report history tracking).
 *
 * <p>Covers:
 * <ul>
 *   <li>First run with no history defaults appropriately</li>
 *   <li>Second run with history uses delta since last generation</li>
 *   <li>Period persisted correctly after generation</li>
 *   <li>History listing for --history flag</li>
 *   <li>UPSERT semantics (same target/topic overwrites)</li>
 * </ul>
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/250">#250</a>
 */
class ReportHistoryRepositoryTest {

    private Connection connection;
    private ReportHistoryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        applySchema(connection);
        repository = new ReportHistoryRepository(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // --- First run: no history ---

    @Test
    void getLastGenerated_returnsEmptyWhenNoHistory() {
        Optional<Instant> result = repository.getLastGenerated("ceo", "weekly");
        assertTrue(result.isEmpty(), "Should return empty when no history exists");
    }

    @Test
    void daysSinceLastReport_returnsEmptyWhenNoHistory() {
        Optional<Integer> result = repository.daysSinceLastReport("ceo", "weekly");
        assertTrue(result.isEmpty(), "Should return empty when no history exists (first run)");
    }

    @Test
    void getAllHistory_returnsEmptyListWhenNoHistory() {
        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertTrue(entries.isEmpty(), "Should return empty list when no history exists");
    }

    // --- Record and retrieve ---

    @Test
    void recordGeneration_persistsTimestamp() {
        Instant now = Instant.now();
        repository.recordGeneration("ceo", "weekly", now, 7, 5, "report.md");

        Optional<Instant> retrieved = repository.getLastGenerated("ceo", "weekly");
        assertTrue(retrieved.isPresent(), "Should retrieve recorded timestamp");
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS),
                retrieved.get().truncatedTo(ChronoUnit.MILLIS),
                "Retrieved timestamp should match recorded timestamp");
    }

    @Test
    void recordGeneration_persistsPeriodDays() {
        Instant now = Instant.now();
        repository.recordGeneration("ceo", "pipeline", now, 14, 3, null);

        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(1, entries.size());
        assertEquals(14, entries.get(0).periodDays(), "Period days should be persisted");
    }

    @Test
    void recordGeneration_persistsSourceDocuments() {
        Instant now = Instant.now();
        repository.recordGeneration("board", "executive", now, 7, 8, "exec-report.md");

        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(1, entries.size());
        assertEquals(8, entries.get(0).sourceDocuments(), "Source document count should be persisted");
    }

    @Test
    void recordGeneration_persistsOutputFile() {
        Instant now = Instant.now();
        repository.recordGeneration("investor", "pipeline", now, 30, 2, "/path/to/report.md");

        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(1, entries.size());
        assertEquals("/path/to/report.md", entries.get(0).outputFile(), "Output file should be persisted");
    }

    @Test
    void recordGeneration_handlesNullSourceDocuments() {
        Instant now = Instant.now();
        repository.recordGeneration("ceo", "weekly", now, 7, null, null);

        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(1, entries.size());
        assertNull(entries.get(0).sourceDocuments(), "Null source documents should be handled");
    }

    // --- Delta computation (second run with history) ---

    @Test
    void daysSinceLastReport_computesDeltaFromHistory() {
        // Record a report generated 5 days ago
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS);
        repository.recordGeneration("ceo", "weekly", fiveDaysAgo, 7, 4, null);

        Optional<Integer> days = repository.daysSinceLastReport("ceo", "weekly");
        assertTrue(days.isPresent(), "Should compute delta when history exists");
        // The exact value depends on date boundaries, but should be 4-6 days
        assertTrue(days.get() >= 4 && days.get() <= 6,
                "Delta should be approximately 5 days, got: " + days.get());
    }

    @Test
    void daysSinceLastReport_returnsMinimumOneDay() {
        // Record a report generated just now (same day)
        Instant now = Instant.now();
        repository.recordGeneration("ceo", "weekly", now, 7, 4, null);

        Optional<Integer> days = repository.daysSinceLastReport("ceo", "weekly");
        assertTrue(days.isPresent());
        assertTrue(days.get() >= 1, "Should return at least 1 day even for same-day reports");
    }

    @Test
    void daysSinceLastReport_isolatesTargetTopicCombinations() {
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);

        repository.recordGeneration("ceo", "weekly", threeDaysAgo, 7, 4, null);
        repository.recordGeneration("board", "pipeline", tenDaysAgo, 14, 2, null);

        // CEO/weekly should be ~3 days
        Optional<Integer> ceoDays = repository.daysSinceLastReport("ceo", "weekly");
        assertTrue(ceoDays.isPresent());
        assertTrue(ceoDays.get() >= 2 && ceoDays.get() <= 4,
                "CEO/weekly delta should be ~3 days, got: " + ceoDays.get());

        // Board/pipeline should be ~10 days
        Optional<Integer> boardDays = repository.daysSinceLastReport("board", "pipeline");
        assertTrue(boardDays.isPresent());
        assertTrue(boardDays.get() >= 9 && boardDays.get() <= 11,
                "Board/pipeline delta should be ~10 days, got: " + boardDays.get());

        // CEO/pipeline should have no history
        Optional<Integer> missingDays = repository.daysSinceLastReport("ceo", "pipeline");
        assertTrue(missingDays.isEmpty(), "CEO/pipeline should have no history");
    }

    // --- UPSERT behavior ---

    @Test
    void recordGeneration_updatesExistingEntry() {
        Instant first = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant second = Instant.now().minus(2, ChronoUnit.DAYS);

        repository.recordGeneration("ceo", "weekly", first, 7, 3, "first.md");
        repository.recordGeneration("ceo", "weekly", second, 14, 5, "second.md");

        // Should have only one entry (UPSERT)
        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(1, entries.size(), "UPSERT should keep only one entry per target/topic");

        // Should reflect the latest values
        ReportHistoryRepository.ReportHistoryEntry entry = entries.get(0);
        assertEquals(14, entry.periodDays(), "Should have updated period");
        assertEquals(5, entry.sourceDocuments(), "Should have updated source count");
        assertEquals("second.md", entry.outputFile(), "Should have updated output file");
    }

    // --- History listing (--history flag) ---

    @Test
    void getAllHistory_returnsAllEntries() {
        Instant now = Instant.now();
        repository.recordGeneration("ceo", "weekly", now, 7, 5, null);
        repository.recordGeneration("board", "pipeline", now.minus(1, ChronoUnit.HOURS), 14, 3, null);
        repository.recordGeneration("investor", "executive", now.minus(2, ChronoUnit.HOURS), 30, 8, null);

        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(3, entries.size(), "Should return all history entries");
    }

    @Test
    void getAllHistory_orderedByMostRecentFirst() {
        Instant oldest = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant middle = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant newest = Instant.now();

        repository.recordGeneration("investor", "executive", oldest, 30, 8, null);
        repository.recordGeneration("board", "pipeline", middle, 14, 3, null);
        repository.recordGeneration("ceo", "weekly", newest, 7, 5, null);

        List<ReportHistoryRepository.ReportHistoryEntry> entries = repository.getAllHistory();
        assertEquals(3, entries.size());
        assertEquals("ceo", entries.get(0).target(), "Most recent should be first");
        assertEquals("investor", entries.get(2).target(), "Oldest should be last");
    }

    // --- Helpers ---

    /**
     * Applies the V16 report_history schema to an in-memory SQLite connection.
     * Mirrors the Flyway migration at V16__report_history.sql.
     */
    private static void applySchema(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS report_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        generated_at TEXT NOT NULL,
                        period_days INTEGER NOT NULL,
                        source_documents INTEGER,
                        output_file TEXT,
                        UNIQUE(target, topic)
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_report_history_target_topic
                        ON report_history(target, topic)
                    """);
        }
    }
}
