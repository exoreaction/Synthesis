package io.exoreaction.synthesis.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptTemplates.
 */
class PromptTemplatesTest {

    @Test
    void buildAskPromptIncludesQuestionAndContext() {
        String prompt = PromptTemplates.buildAskPrompt("How does auth work?", "File: auth.java\ncode here");

        assertNotNull(prompt);
        assertTrue(prompt.contains("How does auth work?"), "Should include the question");
        assertTrue(prompt.contains("File: auth.java"), "Should include the context");
        assertTrue(prompt.contains("code here"), "Should include the code");
    }

    @Test
    void buildAskPromptIncludesInstructions() {
        String prompt = PromptTemplates.buildAskPrompt("question", "context");

        assertTrue(prompt.contains("Cite specific files"), "Should include citation instructions");
        assertTrue(prompt.contains("line numbers"), "Should mention line numbers");
    }

    @Test
    void buildAnalyzePromptIncludesStatisticsAndSamples() {
        String prompt = PromptTemplates.buildAnalyzePrompt("50 files total", "Main.java: class Main {}");

        assertNotNull(prompt);
        assertTrue(prompt.contains("50 files total"), "Should include statistics");
        assertTrue(prompt.contains("Main.java: class Main {}"), "Should include samples");
        assertTrue(prompt.contains("Issues Found"), "Should ask for issues");
        assertTrue(prompt.contains("Recommendations"), "Should ask for recommendations");
    }

    @Test
    void buildArchitectureDocPromptIncludesWorkspaceInfo() {
        String prompt = PromptTemplates.buildArchitectureDocPrompt(
                "MyProject", "monorepo", "/workspace", "src/Main.java [CODE]");

        assertNotNull(prompt);
        assertTrue(prompt.contains("MyProject"), "Should include workspace name");
        assertTrue(prompt.contains("monorepo"), "Should include workspace type");
        assertTrue(prompt.contains("/workspace"), "Should include root path");
        assertTrue(prompt.contains("src/Main.java"), "Should include file index");
        assertTrue(prompt.contains("Key Components"), "Should ask for components");
    }

    @Test
    void buildOnboardingGuidePromptIncludesWorkspaceInfo() {
        String prompt = PromptTemplates.buildOnboardingGuidePrompt(
                "MyProject", "general", "/workspace", "README.md [MARKDOWN]");

        assertNotNull(prompt);
        assertTrue(prompt.contains("MyProject"), "Should include workspace name");
        assertTrue(prompt.contains("Getting Started"), "Should ask for getting started");
        assertTrue(prompt.contains("Common Tasks"), "Should ask for common tasks");
        assertTrue(prompt.contains("README.md"), "Should include file index");
    }

    @Test
    void readmeGenerationTemplateExists() {
        assertNotNull(PromptTemplates.README_GENERATION, "README template should exist");
        assertTrue(PromptTemplates.README_GENERATION.contains("README.md"),
                "Should reference README.md");
    }

    @Test
    void contentSummaryTemplateExists() {
        assertNotNull(PromptTemplates.CONTENT_SUMMARY, "Content summary template should exist");
        assertTrue(PromptTemplates.CONTENT_SUMMARY.contains("Summarize"),
                "Should contain summarize instruction");
    }
}
