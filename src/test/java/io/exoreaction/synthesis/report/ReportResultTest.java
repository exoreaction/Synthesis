package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportResult record — factory methods, cost estimation, fromCache flag.
 */
class ReportResultTest {

    private static final String MODEL_SONNET = "claude-sonnet-4-5";
    private static final String MODEL_OPUS   = "claude-opus-4-5";

    // --- fromGeneration ---

    @Test
    void fromGeneration_setsFromCacheFalse() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "Test report");
        assertFalse(result.fromCache());
    }

    @Test
    void fromGeneration_setsGeneratedAtNonNull() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "Test report");
        assertNotNull(result.generatedAt());
    }

    @Test
    void fromGeneration_preservesTarget() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "Test report");
        assertEquals(ReportTarget.CEO, result.target());
    }

    @Test
    void fromGeneration_preservesTopic() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "Test report");
        assertEquals(ReportTopic.PIPELINE, result.topic());
    }

    @Test
    void fromGeneration_preservesFinalReport() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "Report content here");
        assertEquals("Report content here", result.finalReport());
    }

    @Test
    void fromGeneration_preservesPeriod() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "content");
        assertEquals("1w", result.period());
    }

    @Test
    void fromGeneration_computesNonNegativeCost() {
        ReportResult result = fromGeneration(List.of(), MODEL_SONNET, "content");
        assertTrue(result.estimatedCostUsd() >= 0.0);
    }

    @Test
    void fromGeneration_withDocuments_preservesDocumentList() {
        List<ReportDocument> docs = List.of(
                new ReportDocument(java.nio.file.Path.of("/ws/doc.md"),
                        "doc.md", "pipeline", "content", Instant.now(), 100L)
        );
        ReportResult result = fromGeneration(docs, MODEL_SONNET, "Report");
        assertEquals(1, result.documents().size());
    }

    // --- fromCache ---

    @Test
    void fromCache_setsFromCacheTrue() {
        ReportResult result = fromCache();
        assertTrue(result.fromCache());
    }

    @Test
    void fromCache_generationTimeMsIsZero() {
        ReportResult result = fromCache();
        assertEquals(0L, result.generationTimeMs());
    }

    @Test
    void fromCache_documentsIsEmpty() {
        ReportResult result = fromCache();
        assertTrue(result.documents().isEmpty(),
                "fromCache creates result with empty documents list");
    }

    @Test
    void fromCache_preservesAllFields() {
        Instant now = Instant.now();
        ReportResult result = ReportResult.fromCache(
                ReportTarget.BOARD, ReportTopic.EXECUTIVE,
                "Board report content", MODEL_OPUS,
                5000, 0.05, now, "2w");

        assertEquals(ReportTarget.BOARD, result.target());
        assertEquals(ReportTopic.EXECUTIVE, result.topic());
        assertEquals("Board report content", result.finalReport());
        assertEquals(MODEL_OPUS, result.model());
        assertEquals(5000, result.totalTokenCount());
        assertEquals(0.05, result.estimatedCostUsd(), 0.0001);
        assertEquals(now, result.generatedAt());
        assertEquals("2w", result.period());
    }

    // --- estimateCost ---

    @Test
    void estimateCost_nullModel_returnsZero() {
        assertEquals(0.0, ReportResult.estimateCost(null, 1000));
    }

    @Test
    void estimateCost_sonnetModel_returnsPositive() {
        double cost = ReportResult.estimateCost(MODEL_SONNET, 10000);
        assertTrue(cost > 0.0);
    }

    @Test
    void estimateCost_opusModel_isHigherThanSonnet() {
        double sonnetCost = ReportResult.estimateCost(MODEL_SONNET, 10000);
        double opusCost   = ReportResult.estimateCost(MODEL_OPUS,   10000);
        assertTrue(opusCost > sonnetCost, "Opus should cost more than Sonnet");
    }

    @Test
    void estimateCost_zeroTokens_returnsZero() {
        assertEquals(0.0, ReportResult.estimateCost(MODEL_SONNET, 0));
    }

    @Test
    void estimateCost_moreTokens_higherCost() {
        double lowCost  = ReportResult.estimateCost(MODEL_SONNET, 100);
        double highCost = ReportResult.estimateCost(MODEL_SONNET, 10000);
        assertTrue(highCost > lowCost, "Higher token count should produce higher cost");
    }

    // --- helpers ---

    private static ReportResult fromGeneration(List<ReportDocument> docs, String model, String report) {
        return ReportResult.fromGeneration(
                ReportTarget.CEO, ReportTopic.PIPELINE,
                docs, report, model, 1000, 500L, "1w");
    }

    private static ReportResult fromCache() {
        return ReportResult.fromCache(
                ReportTarget.CEO, ReportTopic.PIPELINE,
                "cached report", MODEL_SONNET, 1000, 0.01, Instant.now(), "1w");
    }
}
