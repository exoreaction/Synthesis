package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchRenderer — target-specific output formatting.
 */
class ResearchRendererTest {

    // --- render dispatch ---

    @Test
    void render_null_returnsEmpty() {
        assertEquals("", ResearchRenderer.render(null));
    }

    @Test
    void render_chatGptTarget_containsResearchReportHeader() {
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, List.of());
        String output = ResearchRenderer.render(result);
        assertTrue(output.contains("Codebase Research Report"), "ChatGPT output should have research report header");
    }

    @Test
    void render_notebooklmInfographic_containsCompleteAnalysisHeader() {
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, List.of());
        String output = ResearchRenderer.render(result);
        assertTrue(output.contains("Complete Codebase Analysis Data"), "Infographic output should have complete data header");
    }

    @Test
    void render_notebooklmPresentation_containsPresentationHeader() {
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_PRESENTATION, List.of());
        String output = ResearchRenderer.render(result);
        assertTrue(output.contains("Codebase Analysis Presentation"), "Presentation output should have presentation header");
    }

    // --- ChatGPT rendering ---

    @Test
    void renderForChatGpt_containsResearchQuestionsSection() {
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, List.of());
        String output = ResearchRenderer.renderForChatGpt(result);
        assertTrue(output.contains("Research Questions for Further Investigation"),
                "ChatGPT output should always have research questions section");
    }

    @Test
    void renderForChatGpt_withSynthesisPass_usesSynthesisAsBody() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch analysis", 10),
                new ResearchPassResult("synthesis", "synthesized insights here", 20)
        );
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, passes);
        String output = ResearchRenderer.renderForChatGpt(result);
        assertTrue(output.contains("synthesized insights here"),
                "ChatGPT render should include synthesis pass content as main body");
    }

    @Test
    void renderForChatGpt_withSynthesisPass_includesAppendixForDomainPasses() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch details", 10),
                new ResearchPassResult("synthesis", "synthesis", 20)
        );
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, passes);
        String output = ResearchRenderer.renderForChatGpt(result);
        assertTrue(output.contains("Appendix"), "Should include appendix for domain passes when synthesis present");
    }

    @Test
    void renderForChatGpt_withoutSynthesisPass_rendersDomainPassesDirectly() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch content", 10),
                new ResearchPassResult("security", "security content", 10)
        );
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, passes);
        String output = ResearchRenderer.renderForChatGpt(result);
        assertTrue(output.contains("arch content"), "Without synthesis pass, domain passes rendered directly");
        assertTrue(output.contains("security content"));
    }

    @Test
    void renderForChatGpt_containsModelInfo() {
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, List.of());
        String output = ResearchRenderer.renderForChatGpt(result);
        assertTrue(output.contains("claude-test-model"), "Output should contain model name");
    }

    @Test
    void renderForChatGpt_containsTopicDisplayName() {
        ResearchResult result = buildResult(ResearchTarget.CHATGPT_DEEP_RESEARCH, List.of());
        String output = ResearchRenderer.renderForChatGpt(result);
        assertTrue(output.contains(ResearchTopic.FULL_ANALYSIS.displayName()), "Output should contain topic display name");
    }

    // --- NotebookLM Infographic rendering ---

    @Test
    void renderForNotebookLmInfographic_containsMetadataTable() {
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, List.of());
        String output = ResearchRenderer.renderForNotebookLmInfographic(result);
        assertTrue(output.contains("| Property | Value |"), "Infographic should have metadata table");
    }

    @Test
    void renderForNotebookLmInfographic_includesDomainPasses() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch data", 10),
                new ResearchPassResult("security", "security data", 10)
        );
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, passes);
        String output = ResearchRenderer.renderForNotebookLmInfographic(result);
        assertTrue(output.contains("arch data"), "Infographic should include architecture pass data");
        assertTrue(output.contains("security data"), "Infographic should include security pass data");
    }

    @Test
    void renderForNotebookLmInfographic_withSynthesisPass_includesSynthesizedOverview() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("synthesis", "synthesized overview", 20)
        );
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, passes);
        String output = ResearchRenderer.renderForNotebookLmInfographic(result);
        assertTrue(output.contains("Synthesized Overview"), "Infographic should include synthesized overview section");
    }

    @Test
    void renderForNotebookLmInfographic_formatsTokenCountWithCommas() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "a".repeat(40000), 10000)
        );
        ResearchResult result = buildResultWithTokens(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC, passes, 10000);
        String output = ResearchRenderer.renderForNotebookLmInfographic(result);
        // 10,000 tokens should be formatted with comma
        assertTrue(output.contains("10,000"), "Token count should be formatted with comma separators");
    }

    // --- NotebookLM Presentation rendering ---

    @Test
    void renderForNotebookLmPresentation_withDomainPasses_buildsChapters() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch content", 10),
                new ResearchPassResult("security", "security content", 10)
        );
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_PRESENTATION, passes);
        String output = ResearchRenderer.renderForNotebookLmPresentation(result);
        assertTrue(output.contains("## Chapter 1:"), "Presentation should build chapters from domain passes");
        assertTrue(output.contains("## Chapter 2:"), "Presentation should have multiple chapters");
    }

    @Test
    void renderForNotebookLmPresentation_containsSlideMarkers() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch content", 10)
        );
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_PRESENTATION, passes);
        String output = ResearchRenderer.renderForNotebookLmPresentation(result);
        assertTrue(output.contains("<!-- SLIDE -->"), "Presentation should contain slide markers");
    }

    @Test
    void renderForNotebookLmPresentation_withSynthesisChapterContent_usesSynthesisDirectly() {
        String synthesisWithChapters = "## Chapter 1: Overview\nSynthesis content";
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("synthesis", synthesisWithChapters, 20)
        );
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_PRESENTATION, passes);
        String output = ResearchRenderer.renderForNotebookLmPresentation(result);
        assertTrue(output.contains(synthesisWithChapters), "If synthesis has chapters, use it directly");
    }

    @Test
    void renderForNotebookLmPresentation_containsSpeakerNotes() {
        List<ResearchPassResult> passes = List.of(
                new ResearchPassResult("architecture", "arch content", 10)
        );
        ResearchResult result = buildResult(ResearchTarget.NOTEBOOKLM_PRESENTATION, passes);
        String output = ResearchRenderer.renderForNotebookLmPresentation(result);
        assertTrue(output.contains("Speaker Notes"), "Presentation should contain speaker notes");
    }

    // --- helpers ---

    private static ResearchResult buildResult(ResearchTarget target, List<ResearchPassResult> passes) {
        return new ResearchResult(
                target, ResearchTopic.FULL_ANALYSIS, passes, "final report",
                "claude-test-model", passes.stream().mapToInt(ResearchPassResult::tokenCount).sum(),
                0.01, Instant.now(), 1000L, false);
    }

    private static ResearchResult buildResultWithTokens(ResearchTarget target,
                                                         List<ResearchPassResult> passes,
                                                         int tokenCount) {
        return new ResearchResult(
                target, ResearchTopic.FULL_ANALYSIS, passes, "final report",
                "claude-test-model", tokenCount, 0.01, Instant.now(), 1000L, false);
    }
}
