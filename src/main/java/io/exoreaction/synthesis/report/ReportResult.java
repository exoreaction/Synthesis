package io.exoreaction.synthesis.report;

import java.time.Instant;
import java.util.List;

/**
 * Complete result of a business report generation, containing the rendered
 * report, metadata about the generation, and the documents that were analyzed.
 *
 * @param target           the report target audience (CEO, board, investor)
 * @param topic            the report topic/focus
 * @param documents        list of business documents that were analyzed
 * @param finalReport      the rendered final report markdown
 * @param model            the AI model used for generation
 * @param totalTokenCount  total estimated tokens across all passes
 * @param estimatedCostUsd estimated cost in USD
 * @param generatedAt      when the report was generated
 * @param generationTimeMs time taken to generate in milliseconds
 * @param fromCache        whether this result was loaded from cache
 * @param period           the coverage period (1w, 2w, 1m)
 */
public record ReportResult(
    ReportTarget target,
    ReportTopic topic,
    List<ReportDocument> documents,
    String finalReport,
    String model,
    int totalTokenCount,
    double estimatedCostUsd,
    Instant generatedAt,
    long generationTimeMs,
    boolean fromCache,
    String period
) {
    /**
     * Creates a new result from generation (not from cache).
     */
    public static ReportResult fromGeneration(
            ReportTarget target,
            ReportTopic topic,
            List<ReportDocument> documents,
            String finalReport,
            String model,
            int totalTokenCount,
            long generationTimeMs,
            String period) {

        double cost = estimateCost(model, totalTokenCount);

        return new ReportResult(
                target, topic, documents, finalReport, model,
                totalTokenCount, cost, Instant.now(), generationTimeMs, false, period);
    }

    /**
     * Creates a cached result.
     */
    public static ReportResult fromCache(
            ReportTarget target,
            ReportTopic topic,
            String finalReport,
            String model,
            int totalTokenCount,
            double estimatedCostUsd,
            Instant generatedAt,
            String period) {

        return new ReportResult(
                target, topic, List.of(), finalReport, model,
                totalTokenCount, estimatedCostUsd, generatedAt, 0, true, period);
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

        // Estimate input tokens as roughly 2x output tokens for report (lots of doc context)
        int estimatedInputTokens = tokenCount * 2;

        if (model.contains("opus")) {
            // Opus 4: input $15/M tokens, output $75/M tokens
            return (estimatedInputTokens * 15.0 / 1_000_000) + (tokenCount * 75.0 / 1_000_000);
        } else {
            // Sonnet 4.5: input $3/M tokens, output $15/M tokens
            return (estimatedInputTokens * 3.0 / 1_000_000) + (tokenCount * 15.0 / 1_000_000);
        }
    }
}
