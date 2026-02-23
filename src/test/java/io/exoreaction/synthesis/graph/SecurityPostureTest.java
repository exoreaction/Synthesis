package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SecurityPosture} — shared security posture snapshot
 * used by maintain, summary, and report commands.
 */
class SecurityPostureTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private SecurityRepository repo;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new SecurityRepository();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // SecurityPosture.query() with populated data
    // -----------------------------------------------------------------------

    @Test
    void query_returns_correct_severity_counts() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/UserDao.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/XmlParser.java", "direct");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);

        assertFalse(posture.noData());
        assertEquals(3, posture.highCount());
        assertEquals(1, posture.mediumCount());
        assertEquals(1, posture.lowCount());
        assertEquals(0, posture.infoCount());
        assertEquals(5, posture.totalCount());
    }

    @Test
    void query_splits_agentic_vs_traditional() throws SQLException {
        // Traditional: S001-S014
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/Xml.java", "indirect");
        // Agentic: S016-S021
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");
        insertFinding("S019_UNVALIDATED_PATHS", "MEDIUM", "src/Path.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);

        assertEquals(3, posture.agenticCount());
        assertEquals(2, posture.traditionalCount());
    }

    @Test
    void query_returns_top_signals() throws SQLException {
        // Create findings with varying counts per signal
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/A.java", "agentic");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/B.java", "agentic");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/C.java", "agentic");
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Repo.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/Xml.java", "indirect");

        SecurityPosture posture = SecurityPosture.query(conn, WS);

        assertFalse(posture.topSignals().isEmpty());
        // Top signal should be S016 with 3 findings
        assertEquals("S016_DIRECT_PROMPT_INJECTION", posture.topSignals().get(0).signalId());
        assertEquals(3, posture.topSignals().get(0).count());
    }

    @Test
    void query_counts_distinct_files() throws SQLException {
        // Same file, different signals
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/Dao.java", "indirect");
        // Different file
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);

        assertEquals(2, posture.fileCount(), "Should count 2 distinct files");
    }

    // -----------------------------------------------------------------------
    // SecurityPosture.query() with empty data
    // -----------------------------------------------------------------------

    @Test
    void query_returns_empty_when_no_findings() {
        SecurityPosture posture = SecurityPosture.query(conn, WS);

        assertTrue(posture.noData());
        assertEquals(0, posture.totalCount());
    }

    @Test
    void empty_posture_has_zero_counts() {
        SecurityPosture posture = SecurityPosture.empty();

        assertTrue(posture.noData());
        assertEquals(0, posture.highCount());
        assertEquals(0, posture.mediumCount());
        assertEquals(0, posture.lowCount());
        assertEquals(0, posture.infoCount());
        assertEquals(0, posture.agenticCount());
        assertEquals(0, posture.traditionalCount());
        assertTrue(posture.topSignals().isEmpty());
    }

    // -----------------------------------------------------------------------
    // SecurityPosture.oneLiner()
    // -----------------------------------------------------------------------

    @Test
    void oneLiner_formats_severity_counts() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/Xml.java", "indirect");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);
        String oneLiner = posture.oneLiner();

        assertTrue(oneLiner.contains("1 HIGH"));
        assertTrue(oneLiner.contains("1 MEDIUM"));
        assertTrue(oneLiner.contains("1 LOW"));
    }

    @Test
    void oneLiner_handles_empty_posture() {
        SecurityPosture posture = SecurityPosture.empty();
        assertEquals("No security findings", posture.oneLiner());
    }

    // -----------------------------------------------------------------------
    // SecurityPosture.format()
    // -----------------------------------------------------------------------

    @Test
    void format_executive_is_concise() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);
        String formatted = posture.format("executive");

        assertTrue(formatted.contains("Security:"));
        assertTrue(formatted.contains("HIGH"));
        assertTrue(formatted.contains("agentic"));
    }

    @Test
    void format_manager_includes_top_signals() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);
        String formatted = posture.format("manager");

        assertTrue(formatted.contains("Security Posture"));
        assertTrue(formatted.contains("Top signals:"));
        assertTrue(formatted.contains("Agentic AI risks:"));
        assertTrue(formatted.contains("Traditional risks:"));
    }

    @Test
    void format_developer_includes_command_hint() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");

        SecurityPosture posture = SecurityPosture.query(conn, WS);
        String formatted = posture.format("developer");

        assertTrue(formatted.contains("synthesis code-graph security"));
    }

    @Test
    void format_empty_shows_unavailable_message() {
        SecurityPosture posture = SecurityPosture.empty();
        String formatted = posture.format("manager");

        assertTrue(formatted.contains("unavailable") || formatted.contains("maintain"));
    }

    // -----------------------------------------------------------------------
    // SecurityPosture.formatMarkdown()
    // -----------------------------------------------------------------------

    @Test
    void formatMarkdown_produces_table() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/Xml.java", "indirect");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);
        String md = posture.formatMarkdown();

        assertTrue(md.contains("## Security Posture"));
        assertTrue(md.contains("| Severity | Count | Trend |"));
        assertTrue(md.contains("| HIGH"));
        assertTrue(md.contains("| MEDIUM"));
        assertTrue(md.contains("| LOW"));
        assertTrue(md.contains("Agentic AI risks"));
        assertTrue(md.contains("Traditional risks"));
        assertTrue(md.contains("CKG-5"));
    }

    @Test
    void formatMarkdown_handles_empty() {
        SecurityPosture posture = SecurityPosture.empty();
        String md = posture.formatMarkdown();

        assertTrue(md.contains("No security scan data available"));
    }

    // -----------------------------------------------------------------------
    // SecurityRepository.countFindingsBySeverity()
    // -----------------------------------------------------------------------

    @Test
    void countFindingsBySeverity_groups_correctly() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/A.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "HIGH", "src/B.java", "direct");
        insertFinding("S002_HARDCODED_SECRETS", "MEDIUM", "src/C.java", "structural");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/D.java", "agentic");

        Map<String, Integer> counts = repo.countFindingsBySeverity(conn, WS);

        assertEquals(2, counts.getOrDefault("HIGH", 0));
        assertEquals(1, counts.getOrDefault("MEDIUM", 0));
        assertEquals(1, counts.getOrDefault("LOW", 0));
    }

    // -----------------------------------------------------------------------
    // SecurityRepository.countFindingsByFlowType()
    // -----------------------------------------------------------------------

    @Test
    void countFindingsByFlowType_groups_correctly() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/A.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/B.java", "indirect");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/C.java", "agentic");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/D.java", "agentic");

        Map<String, Integer> counts = repo.countFindingsByFlowType(conn, WS);

        assertEquals(1, counts.getOrDefault("direct", 0));
        assertEquals(1, counts.getOrDefault("indirect", 0));
        assertEquals(2, counts.getOrDefault("agentic", 0));
    }

    // -----------------------------------------------------------------------
    // SecurityRepository.getTopSignals()
    // -----------------------------------------------------------------------

    @Test
    void getTopSignals_orders_by_count_desc() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/A.java", "direct");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/B.java", "agentic");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/C.java", "agentic");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/D.java", "agentic");

        List<SecurityRepository.SignalSummary> top = repo.getTopSignals(conn, WS, 5);

        assertFalse(top.isEmpty());
        assertEquals("S016_DIRECT_PROMPT_INJECTION", top.get(0).signalId());
        assertEquals(3, top.get(0).count());
        assertEquals("S001_SQL_INJECTION", top.get(1).signalId());
        assertEquals(1, top.get(1).count());
    }

    @Test
    void getTopSignals_respects_limit() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/A.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/B.java", "indirect");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/C.java", "agentic");

        List<SecurityRepository.SignalSummary> top = repo.getTopSignals(conn, WS, 2);

        assertEquals(2, top.size());
    }

    // -----------------------------------------------------------------------
    // Phase 11 security summary line format
    // -----------------------------------------------------------------------

    @Test
    void maintain_phase11_summary_shows_severity_counts() throws SQLException {
        // Simulate what Phase 11 does: after analysis, query counts
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S005_XXE_VULNERABILITY", "MEDIUM", "src/Xml.java", "indirect");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "LOW", "src/Agent.java", "agentic");

        Map<String, Integer> severityCounts = repo.countFindingsBySeverity(conn, WS);
        int high = severityCounts.getOrDefault("HIGH", 0);
        int medium = severityCounts.getOrDefault("MEDIUM", 0);
        int low = severityCounts.getOrDefault("LOW", 0);

        // Build summary line using same logic as MaintainOrchestrator
        String summary;
        if (high == 0 && medium == 0 && low == 0) {
            summary = "no findings";
        } else {
            summary = high + " HIGH \u00b7 " + medium + " MEDIUM \u00b7 " + low + " LOW";
        }

        assertTrue(summary.contains("1 HIGH"));
        assertTrue(summary.contains("1 MEDIUM"));
        assertTrue(summary.contains("2 LOW"));
    }

    @Test
    void maintain_phase11_no_findings_message() {
        // No findings inserted — empty DB
        Map<String, Integer> severityCounts;
        try {
            severityCounts = repo.countFindingsBySeverity(conn, WS);
        } catch (SQLException e) {
            fail("Should not throw");
            return;
        }

        int high = severityCounts.getOrDefault("HIGH", 0);
        int medium = severityCounts.getOrDefault("MEDIUM", 0);
        int low = severityCounts.getOrDefault("LOW", 0);

        String summary;
        if (high == 0 && medium == 0 && low == 0) {
            summary = "no findings";
        } else {
            summary = high + " HIGH";
        }

        assertEquals("no findings", summary);
    }

    // -----------------------------------------------------------------------
    // Report command: security section in executive report
    // -----------------------------------------------------------------------

    @Test
    void report_security_section_formats_as_markdown() throws SQLException {
        insertFinding("S001_SQL_INJECTION", "HIGH", "src/Dao.java", "direct");
        insertFinding("S016_DIRECT_PROMPT_INJECTION", "HIGH", "src/Agent.java", "agentic");
        insertFinding("S021_MISSING_PROMPT_BOUNDARIES", "LOW", "src/Chat.java", "agentic");

        SecurityPosture posture = SecurityPosture.query(conn, WS);
        String section = posture.formatMarkdown();

        // Verify executive-friendly format
        assertTrue(section.contains("## Security Posture"));
        assertTrue(section.contains("| Severity | Count | Trend |"));
        assertTrue(section.contains("Agentic AI risks"));
        assertTrue(section.contains("2 findings"), "Should show 2 agentic findings");
        assertTrue(section.contains("Traditional risks"));
        assertTrue(section.contains("1 findings"), "Should show 1 traditional finding");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void insertFinding(String signalId, String severity, String filePath,
                                String flowType) throws SQLException {
        SecuritySignal signal = new SecuritySignal(
                signalId, severity, null,
                filePath, 1, null, null,
                "test description", null, "test suggestion", flowType);
        repo.upsertFinding(conn, WS, signal, NOW);
    }
}
