package io.exoreaction.synthesis.research;

/**
 * Result from a single research pass (one AI call).
 *
 * @param passName   the name of the pass (e.g., "architecture", "security", "synthesis")
 * @param content    the AI-generated content for this pass
 * @param tokenCount estimated token count of the output
 */
public record ResearchPassResult(
    String passName,
    String content,
    int tokenCount
) {
    /**
     * Estimates token count from content length.
     * Rough estimate: ~4 characters per token for English text.
     */
    public static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.length() / 4;
    }
}
