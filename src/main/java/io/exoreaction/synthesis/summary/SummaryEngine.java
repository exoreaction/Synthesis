package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.summary.CodebaseProfile.Profile;

/**
 * Generates AI-enhanced summaries by combining rule-based metrics
 * with perspective-specific AI analysis.
 *
 * <p>Phase 2: Adds intelligent interpretation to Phase 1's raw metrics.
 */
public class SummaryEngine {

    private final AiClient client;

    public SummaryEngine(AiClient client) {
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
        return generateSummary(profile, level, perspective, null);
    }

    /**
     * Generates an AI summary, optionally grounded in recent change data.
     *
     * @param profile the codebase profile with metrics
     * @param level detail level (executive/manager/developer)
     * @param perspective role-based perspective
     * @param temporalContext compact change summary (e.g. "7 changes (3 added, ...)")
     * @return AI-generated summary text
     */
    public String generateSummary(Profile profile,
                                 SummaryLevel level,
                                 SummaryPerspective perspective,
                                 String temporalContext) {
        // Generate perspective-specific prompt with optional temporal context
        String prompt = SummaryPrompts.generatePrompt(profile, level, perspective, temporalContext);

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
