package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompletenessScorer} -- computes module completeness scores.
 *
 * @since v1.12.2 (CKG-3.05)
 */
class CompletenessScoreTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CompletenessScorer scorer;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        scorer = new CompletenessScorer();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // score()
    // -----------------------------------------------------------------------

    @Test
    void score_no_gaps_returns_1_0() {
        double score = scorer.score(List.of());
        assertEquals(1.0, score, 0.001, "No gaps should give perfect score of 1.0");
    }

    @Test
    void score_one_high_gap_returns_0_7() {
        List<QualityGap> gaps = List.of(
                new QualityGap("com/example", "MISSING_TESTS", "HIGH",
                        "No tests", null, "Add tests")
        );
        double score = scorer.score(gaps);
        assertEquals(0.7, score, 0.001, "One HIGH gap should give score of 0.7");
    }

    @Test
    void score_multiple_gaps_floor_at_0() {
        // 4 HIGH gaps = 4 * 0.30 = 1.20 penalty, should floor at 0.0
        List<QualityGap> gaps = List.of(
                new QualityGap("com/example", "MISSING_TESTS", "HIGH", "desc", null, "sug"),
                new QualityGap("com/example", "GAP2", "HIGH", "desc", null, "sug"),
                new QualityGap("com/example", "GAP3", "HIGH", "desc", null, "sug"),
                new QualityGap("com/example", "GAP4", "HIGH", "desc", null, "sug")
        );
        double score = scorer.score(gaps);
        assertEquals(0.0, score, 0.001, "Multiple HIGH gaps exceeding 1.0 should floor at 0.0");
    }

    @Test
    void score_only_low_gaps_high_score() {
        List<QualityGap> gaps = List.of(
                new QualityGap("com/example", "MISSING_README", "LOW",
                        "No README", null, "Add README"),
                new QualityGap("com/example", "MISSING_PACKAGE_INFO", "LOW",
                        "No package-info", null, "Add package-info")
        );
        double score = scorer.score(gaps);
        assertEquals(0.9, score, 0.001, "Two LOW gaps should give score of 0.9");
    }

    @Test
    void score_mixed_severity() {
        List<QualityGap> gaps = List.of(
                new QualityGap("com/example", "MISSING_TESTS", "HIGH",
                        "No tests", null, "Add tests"),
                new QualityGap("com/example", "MISSING_INTERFACE", "MEDIUM",
                        "No interface", null, "Extract interface"),
                new QualityGap("com/example", "MISSING_README", "LOW",
                        "No README", null, "Add README")
        );
        // 0.30 + 0.15 + 0.05 = 0.50 penalty
        double score = scorer.score(gaps);
        assertEquals(0.5, score, 0.001, "Mixed severity gaps should compute correctly");
    }

    @Test
    void score_null_gaps_returns_1_0() {
        double score = scorer.score(null);
        assertEquals(1.0, score, 0.001, "Null gaps list should give perfect score");
    }

    // -----------------------------------------------------------------------
    // computeAndPersistAll()
    // -----------------------------------------------------------------------

    @Test
    void computeAndPersistAll_adds_column_if_missing() throws SQLException {
        // Insert a module profile
        CodeGraphRepository repo = new CodeGraphRepository();
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A.java", "A", "com.a",
                null, "String", "java.lang", "import", true, NOW));
        ModuleProfileComputer computer = new ModuleProfileComputer(repo);
        computer.computeAndPersist(WS, conn);

        // computeAndPersistAll should add completeness_score column lazily
        Map<String, List<QualityGap>> gapsByModule = Map.of(
                "com/a", List.of(new QualityGap("com/a", "MISSING_TESTS", "HIGH",
                        "No tests", null, "Add tests"))
        );

        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) () ->
                        scorer.computeAndPersistAll(WS, conn, gapsByModule),
                "Should not throw when adding completeness_score column");
    }

    @Test
    void computeAndPersistAll_updates_module_profiles() throws SQLException {
        // Set up a module profile
        CodeGraphRepository repo = new CodeGraphRepository();
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A.java", "A", "com.a",
                null, "String", "java.lang", "import", true, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/B.java", "B", "com.b",
                null, "List", "java.util", "import", true, NOW));
        ModuleProfileComputer computer = new ModuleProfileComputer(repo);
        computer.computeAndPersist(WS, conn);

        // Set gaps for com/a (one HIGH = 0.70 score), nothing for com/b (1.0 score)
        Map<String, List<QualityGap>> gapsByModule = Map.of(
                "com/a", List.of(new QualityGap("com/a", "MISSING_TESTS", "HIGH",
                        "No tests", null, "Add tests"))
        );

        scorer.computeAndPersistAll(WS, conn, gapsByModule);

        // Verify com/a has score 0.70
        Double scoreA = loadCompletenessScore(conn, WS, "com/a");
        assertNotNull(scoreA, "com/a should have a completeness score");
        assertEquals(0.70, scoreA, 0.01, "com/a with one HIGH gap should have score 0.70");

        // Verify com/b has score 1.0 (no gaps)
        Double scoreB = loadCompletenessScore(conn, WS, "com/b");
        assertNotNull(scoreB, "com/b should have a completeness score");
        assertEquals(1.0, scoreB, 0.01, "com/b with no gaps should have score 1.0");
    }

    @Test
    void computeAndPersistAll_idempotent() throws SQLException {
        // Set up a module profile
        CodeGraphRepository repo = new CodeGraphRepository();
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A.java", "A", "com.a",
                null, "String", "java.lang", "import", true, NOW));
        ModuleProfileComputer computer = new ModuleProfileComputer(repo);
        computer.computeAndPersist(WS, conn);

        Map<String, List<QualityGap>> gapsByModule = Map.of(
                "com/a", List.of(new QualityGap("com/a", "MISSING_TESTS", "HIGH",
                        "No tests", null, "Add tests"))
        );

        // Run twice -- should not fail
        scorer.computeAndPersistAll(WS, conn, gapsByModule);
        scorer.computeAndPersistAll(WS, conn, gapsByModule);

        Double score = loadCompletenessScore(conn, WS, "com/a");
        assertNotNull(score);
        assertEquals(0.70, score, 0.01, "Score should be consistent after two runs");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Double loadCompletenessScore(Connection conn, String wsPath, String modulePath)
            throws SQLException {
        String sql = """
            SELECT completeness_score FROM module_profiles
            WHERE workspace_path = ? AND module_path = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wsPath);
            ps.setString(2, modulePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double val = rs.getDouble(1);
                    return rs.wasNull() ? null : val;
                }
            }
        }
        return null;
    }
}
