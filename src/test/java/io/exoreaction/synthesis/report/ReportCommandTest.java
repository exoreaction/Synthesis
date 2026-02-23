package io.exoreaction.synthesis.report;

import io.exoreaction.synthesis.graph.SecurityPosture;
import io.exoreaction.synthesis.graph.SecurityRepository;
import io.exoreaction.synthesis.graph.SecuritySignal;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CLI-facing components of the report command.
 *
 * <p>Tests the public enums and renderer methods that form the CLI contract,
 * without requiring a full CLI stack or AI client.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/54">#54</a>
 */
class ReportCommandTest {

    // --- ReportTopic.fromString() ---

    @Test
    void reportTopic_fromString_parsesAllValidTopics() {
        assertEquals(ReportTopic.PIPELINE, ReportTopic.fromString("pipeline"));
        assertEquals(ReportTopic.ACTIVITIES, ReportTopic.fromString("activities"));
        assertEquals(ReportTopic.DECISIONS, ReportTopic.fromString("decisions"));
        assertEquals(ReportTopic.EXECUTIVE, ReportTopic.fromString("executive"));
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString("weekly"));
    }

    @Test
    void reportTopic_fromString_defaultsToWeeklyForUnknown() {
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString(null));
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString("unknown"));
    }

    // --- ReportTarget.fromStringStrict() ---

    @Test
    void reportTarget_fromStringStrict_throwsForInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportTarget.fromStringStrict("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> ReportTarget.fromStringStrict(null));
    }

    @Test
    void reportTarget_fromStringStrict_parsesAllValidTargets() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromStringStrict("ceo"));
        assertEquals(ReportTarget.BOARD, ReportTarget.fromStringStrict("board"));
        assertEquals(ReportTarget.INVESTOR, ReportTarget.fromStringStrict("investor"));
    }

    @Test
    void reportTarget_fromStringStrict_isCaseInsensitive() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromStringStrict("CEO"));
        assertEquals(ReportTarget.BOARD, ReportTarget.fromStringStrict("Board"));
    }

    // --- ReportRenderer.formatPeriod() ---

    @Test
    void reportRenderer_formatPeriod_formatsCorrectly() {
        assertEquals("Last 7 days", ReportRenderer.formatPeriod("1w"));
        assertEquals("Last 14 days", ReportRenderer.formatPeriod("2w"));
        assertEquals("Last 30 days", ReportRenderer.formatPeriod("1m"));
    }

    @Test
    void reportRenderer_formatPeriod_handlesUnknown() {
        // Unknown periods pass through as-is
        assertEquals("3m", ReportRenderer.formatPeriod("3m"));
    }

    // --- CLI value stability ---

    @Test
    void reportTopic_cliValues_areStable() {
        assertEquals("pipeline", ReportTopic.PIPELINE.cliValue());
        assertEquals("activities", ReportTopic.ACTIVITIES.cliValue());
        assertEquals("decisions", ReportTopic.DECISIONS.cliValue());
        assertEquals("client", ReportTopic.CLIENT.cliValue());
        assertEquals("product", ReportTopic.PRODUCT.cliValue());
    }

    @Test
    void reportTarget_cliValues_areStable() {
        assertEquals("ceo", ReportTarget.CEO.cliValue());
        assertEquals("board", ReportTarget.BOARD.cliValue());
        assertEquals("investor", ReportTarget.INVESTOR.cliValue());
    }

    // --- Security posture in executive report ---

    @TempDir
    Path tempDir;

    @Test
    void executiveReport_securitySection_includedInPrompt() {
        // Verify that the executivePass prompt includes security section when provided
        String securitySection = "## Security Posture\n| HIGH | 5 |\n| MEDIUM | 3 |";
        List<ReportDocument> docs = List.of();

        String prompt = ReportPrompts.executivePass(docs, ReportTarget.CEO,
                "(pipeline)", "(activities)", "(decisions)", "1w", securitySection);

        assertTrue(prompt.contains("SECURITY POSTURE"),
                "Executive pass prompt should include security section header");
        assertTrue(prompt.contains("Security Posture"),
                "Executive pass prompt should include the security section content");
        assertTrue(prompt.contains("HIGH | 5"),
                "Executive pass prompt should include the actual findings data");
    }

    @Test
    void executiveReport_noSecuritySection_whenNull() {
        List<ReportDocument> docs = List.of();

        String prompt = ReportPrompts.executivePass(docs, ReportTarget.CEO,
                "(pipeline)", "(activities)", "(decisions)", "1w", null);

        assertFalse(prompt.contains("SECURITY POSTURE"),
                "Executive pass prompt should NOT include security header when null");
    }

    @Test
    void executiveReport_securityPosture_queryAndFormat() throws Exception {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("report-test.db"));
        Connection conn = db.getConnection();
        SecurityRepository repo = new SecurityRepository();
        long now = Instant.now().getEpochSecond();

        // Insert test findings
        repo.upsertFinding(conn, "/test/ws", new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/Dao.java", 42, "Dao", "com.test",
                "SQL concat", null, "Use PreparedStatement", "direct"), now);
        repo.upsertFinding(conn, "/test/ws", new SecuritySignal(
                "S016_DIRECT_PROMPT_INJECTION", "HIGH", null,
                "src/Agent.java", 10, "Agent", "com.test",
                "Prompt injection", null, "Add boundary", "agentic"), now);

        SecurityPosture posture = SecurityPosture.query(conn, "/test/ws");
        String md = posture.formatMarkdown();

        assertTrue(md.contains("## Security Posture"));
        assertTrue(md.contains("| HIGH"));
        assertTrue(md.contains("Agentic AI risks"));

        db.close();
    }
}
