package io.exoreaction.synthesis.research;

import java.time.Instant;
import java.util.List;

/**
 * Complete result of a research report generation, containing all pass results,
 * the final rendered report, and metadata about the generation.
 *
 * @param target          the target AI tool (ChatGPT, NotebookLM, etc.)
 * @param topic           the research topic/focus
 * @param passes          list of pass results from the multi-pass analysis
 * @param finalReport     the rendered final report (formatted for the target)
 * @param model           the AI model used for generation
 * @param totalTokenCount total tokens across all passes
 * @param estimatedCostUsd estimated cost in USD
 * @param generatedAt     when the report was generated
 * @param generationTimeMs time taken to generate in milliseconds
 * @param fromCache       whether this result was loaded from cache
 */
public record ResearchResult(
    ResearchTarget target,
    ResearchTopic topic,
    List<ResearchPassResult> passes,
    String finalReport,
    String model,
    int totalTokenCount,
    double estimatedCostUsd,
    Instant generatedAt,
    long generationTimeMs,
    boolean fromCache
) {
    /**
     * Creates a new result from generation (not from cache).
     */
    public static ResearchResult fromGeneration(
            ResearchTarget target,
            ResearchTopic topic,
            List<ResearchPassResult> passes,
            String finalReport,
            String model,
            long generationTimeMs) {

        int totalTokens = passes.stream()
                .mapToInt(ResearchPassResult::tokenCount)
                .sum();

        double cost = estimateCost(model, totalTokens);

        return new ResearchResult(
                target, topic, passes, finalReport, model,
                totalTokens, cost, Instant.now(), generationTimeMs, false);
    }

    /**
     * Creates a cached result.
     */
    public static ResearchResult fromCache(
            ResearchTarget target,
            ResearchTopic topic,
            List<ResearchPassResult> passes,
            String finalReport,
            String model,
            int totalTokenCount,
            double estimatedCostUsd,
            Instant generatedAt,
            long generationTimeMs) {

        return new ResearchResult(
                target, topic, passes, finalReport, model,
                totalTokenCount, estimatedCostUsd, generatedAt, generationTimeMs, true);
    }

    /**
     * Returns the concatenated content from all passes.
     */
    public String allPassContent() {
        if (passes == null || passes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ResearchPassResult pass : passes) {
            if (pass.content() != null) {
                sb.append(pass.content()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Estimates the cost in USD based on model and token count.
     *
     * @param model      the model name
     * @param tokenCount total output tokens
     * @return estimated cost in USD
     */
    public static double estimateCost(String model, int tokenCount) {
        if (model == null) return 0.0;

        // Estimate input tokens as roughly equal to output tokens for research
        int estimatedInputTokens = tokenCount;

        if (model.contains("opus")) {
            // Opus 4: input $15/M tokens, output $75/M tokens
            return (estimatedInputTokens * 15.0 / 1_000_000) + (tokenCount * 75.0 / 1_000_000);
        } else {
            // Sonnet 4.5: input $3/M tokens, output $15/M tokens
            return (estimatedInputTokens * 3.0 / 1_000_000) + (tokenCount * 15.0 / 1_000_000);
        }
    }

    /**
     * Returns the list of pass names that were executed.
     */
    public List<String> passNames() {
        if (passes == null) return List.of();
        return passes.stream()
                .map(ResearchPassResult::passName)
                .toList();
    }
}
