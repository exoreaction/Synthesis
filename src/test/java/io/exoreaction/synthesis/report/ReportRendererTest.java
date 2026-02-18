package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportRenderer — period formatting, size formatting, render() structure.
 */
class ReportRendererTest {

    // --- formatPeriod ---

    @Test
    void formatPeriod_null_returnsLast7Days() {
        assertEquals("Last 7 days", ReportRenderer.formatPeriod(null));
    }

    @Test
    void formatPeriod_1w_returnsLast7Days() {
        assertEquals("Last 7 days", ReportRenderer.formatPeriod("1w"));
    }

    @Test
    void formatPeriod_2w_returnsLast14Days() {
        assertEquals("Last 14 days", ReportRenderer.formatPeriod("2w"));
    }

    @Test
    void formatPeriod_1m_returnsLast30Days() {
        assertEquals("Last 30 days", ReportRenderer.formatPeriod("1m"));
    }

    @Test
    void formatPeriod_unknown_returnsInputAsIs() {
        assertEquals("3w", ReportRenderer.formatPeriod("3w"));
        assertEquals("custom", ReportRenderer.formatPeriod("custom"));
    }

    // --- periodToDescription ---

    @Test
    void periodToDescription_null_returnsDefaultPhrase() {
        assertEquals("the last 7 days", ReportRenderer.periodToDescription(null));
    }

    @Test
    void periodToDescription_1w_returnsPhrase() {
        assertEquals("the last 7 days", ReportRenderer.periodToDescription("1w"));
    }

    @Test
    void periodToDescription_2w_returnsPhrase() {
        assertEquals("the last 14 days", ReportRenderer.periodToDescription("2w"));
    }

    @Test
    void periodToDescription_1m_returnsPhrase() {
        assertEquals("the last 30 days", ReportRenderer.periodToDescription("1m"));
    }

    @Test
    void periodToDescription_unknown_returnsDefault() {
        // Unknown period defaults to "the last 7 days"
        assertEquals("the last 7 days", ReportRenderer.periodToDescription("3w"));
    }

    // --- render ---

    @Test
    void render_null_returnsEmpty() {
        assertEquals("", ReportRenderer.render(null));
    }

    @Test
    void render_containsTargetDisplayName() {
        ReportResult result = buildResult(ReportTarget.CEO, ReportTopic.PIPELINE, "report content");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains(ReportTarget.CEO.displayName()), "Rendered output should contain target display name");
    }

    @Test
    void render_containsTopicDisplayName() {
        ReportResult result = buildResult(ReportTarget.CEO, ReportTopic.PIPELINE, "report content");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains(ReportTopic.PIPELINE.displayName()), "Rendered output should contain topic display name");
    }

    @Test
    void render_containsPeriodFormatted() {
        ReportResult result = buildResult(ReportTarget.CEO, ReportTopic.PIPELINE, "report content");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains("Last 7 days"), "Rendered output should contain formatted period");
    }

    @Test
    void render_containsFinalReportBody() {
        ReportResult result = buildResult(ReportTarget.CEO, ReportTopic.PIPELINE, "Pipeline analysis here");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains("Pipeline analysis here"), "Rendered output should contain report body");
    }

    @Test
    void render_containsModelName() {
        ReportResult result = buildResult(ReportTarget.CEO, ReportTopic.PIPELINE, "content");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains("claude-test-model"), "Rendered output should contain model name");
    }

    @Test
    void render_containsTokenCount() {
        ReportResult result = new ReportResult(
                ReportTarget.CEO, ReportTopic.WEEKLY, List.of(), "report content",
                "claude-test", 5432, 0.01, Instant.now(), 1000L, false, "1w");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains("5,432") || output.contains("5432"),
                "Rendered output should contain token count");
    }

    @Test
    void render_fromCache_containsCacheIndicator() {
        ReportResult result = ReportResult.fromCache(
                ReportTarget.CEO, ReportTopic.WEEKLY,
                "cached report", "claude-test", 1000, 0.01, Instant.now(), "1w");
        String output = ReportRenderer.render(result);
        assertTrue(output.toLowerCase().contains("cache"), "Cached result should indicate cache source");
    }

    @Test
    void render_withDocuments_containsSourceDocumentsSection() {
        List<ReportDocument> docs = List.of(
                new ReportDocument(Path.of("/ws/PIPELINE.md"),
                        "PIPELINE.md", "pipeline", "content", Instant.now(), 2048L)
        );
        ReportResult result = new ReportResult(
                ReportTarget.CEO, ReportTopic.PIPELINE, docs, "report body",
                "claude-test", 1000, 0.01, Instant.now(), 500L, false, "1w");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains("Source Documents") || output.contains("source"),
                "Render with documents should include source documents section");
    }

    @Test
    void render_withDocuments_containsDocumentRelativePath() {
        List<ReportDocument> docs = List.of(
                new ReportDocument(Path.of("/ws/PIPELINE.md"),
                        "eXOReaction/PIPELINE.md", "pipeline", "content", Instant.now(), 2048L)
        );
        ReportResult result = new ReportResult(
                ReportTarget.CEO, ReportTopic.PIPELINE, docs, "body",
                "claude-test", 1000, 0.01, Instant.now(), 500L, false, "1w");
        String output = ReportRenderer.render(result);
        assertTrue(output.contains("eXOReaction/PIPELINE.md"),
                "Rendered output should include document relative path");
    }

    @Test
    void render_allTargets_returnsNonBlankOutput() {
        for (ReportTarget target : ReportTarget.values()) {
            ReportResult result = buildResult(target, ReportTopic.WEEKLY, "content");
            String output = ReportRenderer.render(result);
            assertFalse(output.isBlank(), "Rendered output should not be blank for target " + target);
        }
    }

    @Test
    void render_allTopics_returnsNonBlankOutput() {
        for (ReportTopic topic : ReportTopic.values()) {
            ReportResult result = buildResult(ReportTarget.CEO, topic, "content");
            String output = ReportRenderer.render(result);
            assertFalse(output.isBlank(), "Rendered output should not be blank for topic " + topic);
        }
    }

    // --- helpers ---

    private static ReportResult buildResult(ReportTarget target, ReportTopic topic, String report) {
        return new ReportResult(
                target, topic, List.of(), report,
                "claude-test-model", 1000, 0.01, Instant.now(), 500L, false, "1w");
    }
}
