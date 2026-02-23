package io.exoreaction.synthesis.report;

import io.exoreaction.synthesis.cli.ReportCommand;
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

    // --- Dynamic period formatting (#250) ---

    @Test
    void reportRenderer_formatPeriod_handlesDynamicDayPeriods() {
        assertEquals("Last 5 days", ReportRenderer.formatPeriod("5d"));
        assertEquals("Last 1 day", ReportRenderer.formatPeriod("1d"));
        assertEquals("Last 12 days", ReportRenderer.formatPeriod("12d"));
        assertEquals("Last 30 days", ReportRenderer.formatPeriod("30d"));
    }

    @Test
    void reportRenderer_periodToDescription_handlesDynamicDayPeriods() {
        assertEquals("the last 5 days", ReportRenderer.periodToDescription("5d"));
        assertEquals("the last 1 day", ReportRenderer.periodToDescription("1d"));
        assertEquals("the last 12 days", ReportRenderer.periodToDescription("12d"));
    }

    @Test
    void reportRenderer_formatPeriod_handlesNull() {
        assertEquals("Last 7 days", ReportRenderer.formatPeriod(null));
    }

    // --- ReportCommand.periodToDays() (#250) ---

    @Test
    void periodToDays_standardPeriods() {
        assertEquals(7, ReportCommand.periodToDays("1w"));
        assertEquals(14, ReportCommand.periodToDays("2w"));
        assertEquals(30, ReportCommand.periodToDays("1m"));
    }

    @Test
    void periodToDays_dynamicDayPeriods() {
        assertEquals(5, ReportCommand.periodToDays("5d"));
        assertEquals(1, ReportCommand.periodToDays("1d"));
        assertEquals(12, ReportCommand.periodToDays("12d"));
        assertEquals(90, ReportCommand.periodToDays("90d"));
    }

    @Test
    void periodToDays_nullDefaultsToSeven() {
        assertEquals(7, ReportCommand.periodToDays(null));
    }

    @Test
    void periodToDays_unknownDefaultsToSeven() {
        assertEquals(7, ReportCommand.periodToDays("xyz"));
    }

    // --- BusinessDocumentFinder.parsePeriodCutoff with dynamic periods (#250) ---

    @Test
    void parsePeriodCutoff_handlesDynamicDayPeriods() {
        Instant cutoff5d = BusinessDocumentFinder.parsePeriodCutoff("5d");
        Instant cutoff1w = BusinessDocumentFinder.parsePeriodCutoff("1w");
        Instant cutoff14d = BusinessDocumentFinder.parsePeriodCutoff("14d");
        Instant cutoff2w = BusinessDocumentFinder.parsePeriodCutoff("2w");

        // 5d cutoff should be more recent than 1w cutoff
        assertTrue(cutoff5d.isAfter(cutoff1w),
                "5d cutoff should be more recent than 1w cutoff");

        // 14d and 2w should be approximately equal (both 14 days)
        long diffMillis = Math.abs(cutoff14d.toEpochMilli() - cutoff2w.toEpochMilli());
        assertTrue(diffMillis < 86_400_000,
                "14d and 2w cutoffs should be within 1 day of each other");
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
