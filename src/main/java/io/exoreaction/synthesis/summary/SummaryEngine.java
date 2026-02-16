package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.summary.CodebaseProfile.Profile;

/**
 * Generates AI-enhanced summaries by combining rule-based metrics
 * with perspective-specific AI analysis.
 *
 * <p>Phase 2: Adds intelligent interpretation to Phase 1's raw metrics.
 */
public class SummaryEngine {

    private final ClaudeClient client;

    public SummaryEngine(ClaudeClient client) {
        this.client = client;
    }

    /**
     * Generates an AI summary based on the profile, level, and perspective.
     *
     * @param profile the codebase profile with metrics
     * @param level detail level (executive/manager/developer)
     * @param perspective role-based perspective
     * @return AI-generated summary text
     */
    public String generateSummary(Profile profile,
                                 SummaryLevel level,
                                 SummaryPerspective perspective) {
        // Generate perspective-specific prompt
        String prompt = SummaryPrompts.generatePrompt(profile, level, perspective);

        // Determine token limit based on level
        int maxTokens = switch (level) {
            case EXECUTIVE -> 300;   // 4-6 sentences
            case MANAGER -> 600;     // 2-3 paragraphs
            case DEVELOPER -> 1000;  // 3-5 paragraphs with details
        };

        // Generate AI summary
        try {
            return client.generate(prompt, maxTokens);
        } catch (Exception e) {
            // Fallback to error message if AI generation fails
            return "AI summary generation failed: " + e.getMessage() +
                   "\n\nFalling back to metrics-only summary. " +
                   "Use --no-ai to skip AI enhancement.";
        }
    }

    /**
     * Validates that AI summary can be generated.
     *
     * @return true if client is available and configured
     */
    public boolean isAvailable() {
        return client != null;
    }

    /**
     * Returns the model being used for summaries.
     */
    public String getModel() {
        return client != null ? client.getModel() : "none";
    }
}
