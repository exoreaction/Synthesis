package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.ai.DirectedSynthesisEngine.AnalysisMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DirectedSynthesisEngine -- mode suggestion, perspective candidate detection,
 * and suggestion formatting.
 */
class DirectedSynthesisEngineTest {

    // --- suggestMode tests ---

    @Test
    void testSuggestModeComparison() {
        assertEquals(AnalysisMode.COMPARISON,
                DirectedSynthesisEngine.suggestMode("Spring Boot vs Quarkus"));
        assertEquals(AnalysisMode.COMPARISON,
                DirectedSynthesisEngine.suggestMode("What are the pros and cons of microservices?"));
        assertEquals(AnalysisMode.COMPARISON,
                DirectedSynthesisEngine.suggestMode("Compare REST versus GraphQL"));
        assertEquals(AnalysisMode.COMPARISON,
                DirectedSynthesisEngine.suggestMode("What's the difference between JWT and OAuth?"));
        assertEquals(AnalysisMode.COMPARISON,
                DirectedSynthesisEngine.suggestMode("Which is better: Kafka or RabbitMQ?"));
    }

    @Test
    void testSuggestModeImpact() {
        assertEquals(AnalysisMode.IMPACT,
                DirectedSynthesisEngine.suggestMode("What if we migrate to Kubernetes?"));
        assertEquals(AnalysisMode.IMPACT,
                DirectedSynthesisEngine.suggestMode("What would happen if we drop Java 11 support?"));
        assertEquals(AnalysisMode.IMPACT,
                DirectedSynthesisEngine.suggestMode("What's the impact of removing the legacy API?"));
        assertEquals(AnalysisMode.IMPACT,
                DirectedSynthesisEngine.suggestMode("Should we refactor the authentication module?"));
    }

    @Test
    void testSuggestModeGapAnalysis() {
        assertEquals(AnalysisMode.GAP_ANALYSIS,
                DirectedSynthesisEngine.suggestMode("What's missing in our test coverage?"));
        assertEquals(AnalysisMode.GAP_ANALYSIS,
                DirectedSynthesisEngine.suggestMode("Are there any gaps in our documentation?"));
        assertEquals(AnalysisMode.GAP_ANALYSIS,
                DirectedSynthesisEngine.suggestMode("What else do we need for production readiness?"));
    }

    @Test
    void testSuggestModeDefaultToPerspectives() {
        assertEquals(AnalysisMode.PERSPECTIVES,
                DirectedSynthesisEngine.suggestMode("How does our architecture handle scaling?"));
        assertEquals(AnalysisMode.PERSPECTIVES,
                DirectedSynthesisEngine.suggestMode("What's the best approach for the new feature?"));
    }

    // --- isPerspectivesCandidate tests ---

    @Test
    void testIsPerspectivesCandidateByKeywords() {
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Should we use microservices?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Which framework should we choose?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What are the pros and cons?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What's the best approach?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Is it worth it to migrate?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Can you compare these two approaches?"));
    }

    @Test
    void testIsPerspectivesCandidateByLength() {
        // Short questions should not trigger
        assertFalse(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What is this file?"));
        assertFalse(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Where is the config?"));

        // Long questions (> 15 words) should trigger
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Given our current architecture and the constraints we have on the team " +
                "what would be the optimal way to implement the new caching layer?"));
    }

    @Test
    void testIsPerspectivesCandidateSimpleQuestions() {
        // Simple factual questions should NOT trigger
        assertFalse(DirectedSynthesisEngine.isPerspectivesCandidate(
                "How many tests are there?"));
        assertFalse(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What language is this file?"));
        assertFalse(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Who wrote the FileUtils class?"));
    }

    @Test
    void testIsPerspectivesCandidateTradeOffs() {
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What are the trade-offs?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What tradeoff does this introduce?"));
    }

    @Test
    void testIsPerspectivesCandidateDecisionWords() {
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "How do we decide between these?"));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "Please evaluate these options."));
        assertTrue(DirectedSynthesisEngine.isPerspectivesCandidate(
                "What strategy works here?"));
    }

    // --- suggestPerspectives tests ---

    @Test
    void testSuggestPerspectivesShortQuestion() {
        String suggestion = DirectedSynthesisEngine.suggestPerspectives("Should we use React?");
        assertTrue(suggestion.contains("perspectives"));
        assertTrue(suggestion.contains("Should we use React?"));
    }

    @Test
    void testSuggestPerspectivesLongQuestionTruncated() {
        String longQuestion = "What would be the best approach for implementing a distributed " +
                "caching layer that can handle our current load while maintaining consistency?";
        String suggestion = DirectedSynthesisEngine.suggestPerspectives(longQuestion);
        assertTrue(suggestion.contains("..."), "Long questions should be truncated");
        assertTrue(suggestion.contains("perspectives"));
    }

    // --- PromptTemplates integration tests ---

    @Test
    void testBuildPerspectivesPrompt() {
        String prompt = PromptTemplates.buildPerspectivesPrompt(
                "Should we use microservices?", "Context here", 4);
        assertTrue(prompt.contains("Should we use microservices?"));
        assertTrue(prompt.contains("Context here"));
        assertTrue(prompt.contains("4"));
    }

    @Test
    void testBuildComparisonPrompt() {
        String prompt = PromptTemplates.buildComparisonPrompt(
                "REST vs GraphQL", "API context");
        assertTrue(prompt.contains("REST vs GraphQL"));
        assertTrue(prompt.contains("API context"));
        assertTrue(prompt.contains("Pros"));
        assertTrue(prompt.contains("Cons"));
    }

    @Test
    void testBuildImpactPrompt() {
        String prompt = PromptTemplates.buildImpactPrompt(
                "Remove legacy API", "Current system context");
        assertTrue(prompt.contains("Remove legacy API"));
        assertTrue(prompt.contains("Ripple Effects"));
    }

    @Test
    void testBuildGapAnalysisPrompt() {
        String prompt = PromptTemplates.buildGapAnalysisPrompt(
                "Testing strategy", "Test suite context");
        assertTrue(prompt.contains("Testing strategy"));
        assertTrue(prompt.contains("Gaps Identified"));
        assertTrue(prompt.contains("Opportunities"));
    }
}
