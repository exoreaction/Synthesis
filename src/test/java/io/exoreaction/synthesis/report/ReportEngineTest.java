package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportEngine — non-AI methods only (cost estimation, model getter).
 * AI-dependent tests are in ReportAiIntegrationTest (@Tag("ai-integration")).
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/53">#53</a>
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/54">#54</a>
 */
class ReportEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void getModel_returnsNoneWhenClientIsNull() {
        ReportEngine engine = new ReportEngine(null, 4000);
        assertEquals("none", engine.getModel(),
                "Should return 'none' when client is null");
    }

    @Test
    void estimateCost_pipelineTopicReturnsOnePass() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

        assertEquals(1, estimate.passCount(), "PIPELINE topic should use 1 pass");
    }

    @Test
    void estimateCost_activitiesTopicReturnsOnePass() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.ACTIVITIES, "1w");

        assertEquals(1, estimate.passCount(), "ACTIVITIES topic should use 1 pass");
    }

    @Test
    void estimateCost_decisionsTopicReturnsOnePass() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.DECISIONS, "1w");

        assertEquals(1, estimate.passCount(), "DECISIONS topic should use 1 pass");
    }

    @Test
    void estimateCost_executiveTopicReturnsFourPasses() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.EXECUTIVE, "1w");

        assertEquals(4, estimate.passCount(),
                "EXECUTIVE topic should use 4 passes (pipeline + activities + decisions + synthesis)");
    }

    @Test
    void estimateCost_weeklyTopicReturnsFourPasses() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.WEEKLY, "1w");

        assertEquals(4, estimate.passCount(), "WEEKLY topic should use 4 passes");
    }

    @Test
    void estimateCost_emptyWorkspaceReturnsZeroDocs() {
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

        assertEquals(0, estimate.documentCount(), "Empty workspace should have 0 documents");
    }

    @Test
    void estimateCost_formatProducesReadableOutput() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

        String formatted = estimate.format();
        assertTrue(formatted.contains("Business Report Cost Estimate"), "Should contain header");
        assertTrue(formatted.contains("Passes:"), "Should include pass count");
        assertTrue(formatted.contains("Documents:"), "Should include document count");
    }

    @Test
    void estimateCost_costIsNonNegative() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.EXECUTIVE, "1w");

        assertTrue(estimate.totalCostUsd() >= 0, "Total cost should be non-negative");
        assertTrue(estimate.inputCostUsd() >= 0, "Input cost should be non-negative");
        assertTrue(estimate.outputCostUsd() >= 0, "Output cost should be non-negative");
    }

    @Test
    void estimateCost_inputTokensScaleWithDocumentSize_issue53() throws IOException {
        // Small document
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
                "# Pipeline\n" + "x".repeat(500));
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate smallEstimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

        // Large document (overwrite)
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
                "# Pipeline\n" + "x".repeat(6000));
        ReportEngine.CostEstimate largeEstimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

        assertTrue(largeEstimate.estimatedInputTokens() > smallEstimate.estimatedInputTokens(),
                "Larger documents should produce higher input token estimates (#53)");
    }

    @Test
    void estimateCost_multiPassHigherInputThanSinglePass_issue53() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);

        ReportEngine.CostEstimate single =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");
        ReportEngine.CostEstimate multi =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.EXECUTIVE, "1w");

        assertTrue(multi.estimatedInputTokens() > single.estimatedInputTokens(),
                "Multi-pass EXECUTIVE should have more input tokens than single-pass PIPELINE (#53)");
    }

    private void createSampleDocs() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
                "# Pipeline\n\nActive deals: 3\nTotal: 500K NOK");
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"),
                "# Activity Log\n\n## Feb 18\n- Meeting with client");
        Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
        Files.createDirectories(strategyDir);
        Files.writeString(strategyDir.resolve("EXECUTIVE-SUMMARY.md"),
                "# Executive Summary\n\nQ1 revenue on track");
    }
}
