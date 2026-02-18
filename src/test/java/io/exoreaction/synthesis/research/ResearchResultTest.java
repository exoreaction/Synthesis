package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchResult record — factory methods, computed properties, cost estimation.
 */
class ResearchResultTest {

    private static final String MODEL_SONNET = "claude-sonnet-4-5";
    private static final String MODEL_OPUS   = "claude-opus-4-5";

    // --- fromGeneration factory ---

    @Test
    void fromGeneration_setsFromCacheFalse() {
        ResearchResult result = fromGeneration(List.of(), MODEL_SONNET);
        assertFalse(result.fromCache());
    }

    @Test
    void fromGeneration_setsGeneratedAtNonNull() {
        ResearchResult result = fromGeneration(List.of(), MODEL_SONNET);
        assertNotNull(result.generatedAt());
    }

    @Test
    void fromGeneration_sumsTotalTokenCount() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "content", 100),
                new ResearchPassResult("security", "content", 200),
                new ResearchPassResult("synthesis", "content", 300)
        );
        ResearchResult result = fromGeneration(passes, MODEL_SONNET);
        assertEquals(600, result.totalTokenCount());
    }

    @Test
    void fromGeneration_computesNonNegativeCost() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "content", 1000)
        );
        ResearchResult result = fromGeneration(passes, MODEL_SONNET);
        assertTrue(result.estimatedCostUsd() >= 0.0);
    }

    // --- fromCache factory ---

    @Test
    void fromCache_setsFromCacheTrue() {
        Instant now = Instant.now();
        ResearchResult result = ResearchResult.fromCache(
                ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS,
                List.of(), "report", MODEL_SONNET, 500, 0.01, now, 1000L);
        assertTrue(result.fromCache());
    }

    @Test
    void fromCache_preservesAllFields() {
        Instant now = Instant.now();
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch content", 150)
        );
        ResearchResult result = ResearchResult.fromCache(
                ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, ResearchTopic.ARCHITECTURE,
                passes, "rendered report", MODEL_OPUS, 750, 0.05, now, 2000L);

        assertEquals(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, result.target());
        assertEquals(ResearchTopic.ARCHITECTURE, result.topic());
        assertEquals(passes, result.passes());
        assertEquals("rendered report", result.finalReport());
        assertEquals(MODEL_OPUS, result.model());
        assertEquals(750, result.totalTokenCount());
        assertEquals(0.05, result.estimatedCostUsd(), 0.0001);
        assertEquals(now, result.generatedAt());
        assertEquals(2000L, result.generationTimeMs());
    }

    // --- allPassContent ---

    @Test
    void allPassContent_emptyPasses_returnsEmpty() {
        ResearchResult result = fromGeneration(List.of(), MODEL_SONNET);
        assertEquals("", result.allPassContent());
    }

    @Test
    void allPassContent_nullPasses_returnsEmpty() {
        ResearchResult result = new ResearchResult(
                ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS,
                null, "report", MODEL_SONNET, 0, 0.0, Instant.now(), 0, false);
        assertEquals("", result.allPassContent());
    }

    @Test
    void allPassContent_concatenatesPassContent() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch findings", 10),
                new ResearchPassResult("security", "security findings", 10)
        );
        ResearchResult result = fromGeneration(passes, MODEL_SONNET);
        String content = result.allPassContent();
        assertTrue(content.contains("arch findings"));
        assertTrue(content.contains("security findings"));
    }

    @Test
    void allPassContent_skipsNullPassContent() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", null, 0),
                new ResearchPassResult("security", "security findings", 10)
        );
        ResearchResult result = fromGeneration(passes, MODEL_SONNET);
        String content = result.allPassContent();
        assertTrue(content.contains("security findings"));
    }

    // --- passNames ---

    @Test
    void passNames_returnsNamesInOrder() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "a", 1),
                new ResearchPassResult("security", "s", 1),
                new ResearchPassResult("synthesis", "syn", 1)
        );
        ResearchResult result = fromGeneration(passes, MODEL_SONNET);
        assertEquals(List.of("architecture", "security", "synthesis"), result.passNames());
    }

    @Test
    void passNames_nullPasses_returnsEmptyList() {
        ResearchResult result = new ResearchResult(
                ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS,
                null, "report", MODEL_SONNET, 0, 0.0, Instant.now(), 0, false);
        assertEquals(List.of(), result.passNames());
    }

    // --- estimateCost ---

    @Test
    void estimateCost_nullModel_returnsZero() {
        assertEquals(0.0, ResearchResult.estimateCost(null, 1000));
    }

    @Test
    void estimateCost_sonnetModel_returnsPositiveCost() {
        double cost = ResearchResult.estimateCost(MODEL_SONNET, 10000);
        assertTrue(cost > 0.0, "Cost for sonnet should be positive");
    }

    @Test
    void estimateCost_opusModel_isHigherThanSonnet() {
        double sonnetCost = ResearchResult.estimateCost(MODEL_SONNET, 10000);
        double opusCost   = ResearchResult.estimateCost(MODEL_OPUS,   10000);
        assertTrue(opusCost > sonnetCost, "Opus cost should be higher than Sonnet");
    }

    @Test
    void estimateCost_zeroTokens_returnsZero() {
        assertEquals(0.0, ResearchResult.estimateCost(MODEL_SONNET, 0));
    }

    // --- helpers ---

    private ResearchResult fromGeneration(List<ResearchPassResult> passes, String model) {
        return ResearchResult.fromGeneration(
                ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTopic.FULL_ANALYSIS,
                passes, "final report", model, 1000L);
    }
}
