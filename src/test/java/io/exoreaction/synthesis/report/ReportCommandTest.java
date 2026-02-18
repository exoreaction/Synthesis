package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;

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
}
