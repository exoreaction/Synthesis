package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Engine for directed synthesis -- generating multiple analytical perspectives
 * on a question using workspace context.
 *
 * <p>Instead of asking a single question and getting a single answer,
 * directed synthesis examines a question through multiple analytical lenses:
 * <ul>
 *   <li><b>Perspectives:</b> 3-5 distinct viewpoints on the same question</li>
 *   <li><b>Comparison:</b> Structured comparison of options/approaches</li>
 *   <li><b>Impact:</b> Ripple-effect analysis of a proposed change</li>
 *   <li><b>Gap Analysis:</b> Identify what's missing or incomplete</li>
 * </ul>
 *
 * <p>This approach reveals insights that a single answer might miss,
 * particularly for complex, ambiguous, or strategic questions.
 */
public class DirectedSynthesisEngine {

    private final ClaudeClient client;
    private final int maxTokens;

    /**
     * Analysis mode for directed synthesis.
     */
    public enum AnalysisMode {
        /** Multiple perspectives on the same question. */
        PERSPECTIVES,
        /** Structured comparison of options. */
        COMPARISON,
        /** Impact/ripple-effect analysis. */
        IMPACT,
        /** Gap analysis -- what's missing. */
        GAP_ANALYSIS
    }

    /**
     * Result of a directed synthesis operation.
     */
    public record SynthesisResult(
            String question,
            AnalysisMode mode,
            String analysis,
            String context,
            int contextDocuments,
            long durationMs
    ) {}

    public DirectedSynthesisEngine(ClaudeClient client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    /**
     * Performs directed synthesis using multiple perspectives.
     *
     * @param question       the question to analyze
     * @param index          the search index for context retrieval
     * @param mode           the analysis mode
     * @param numPerspectives number of perspectives (for PERSPECTIVES mode)
     * @return the synthesis result
     * @throws IOException if search or AI fails
     */
    public SynthesisResult analyze(String question, SearchIndex index,
                                    AnalysisMode mode, int numPerspectives) throws IOException {
        long startTime = System.currentTimeMillis();

        // Gather context from the workspace
        String context = gatherContext(question, index);
        int contextDocs = countContextDocuments(context);

        // Build the appropriate prompt
        String prompt = switch (mode) {
            case PERSPECTIVES -> PromptTemplates.buildPerspectivesPrompt(
                    question, context, numPerspectives);
            case COMPARISON -> PromptTemplates.buildComparisonPrompt(question, context);
            case IMPACT -> PromptTemplates.buildImpactPrompt(question, context);
            case GAP_ANALYSIS -> PromptTemplates.buildGapAnalysisPrompt(question, context);
        };

        // Generate analysis
        String analysis = client.generate(prompt, maxTokens);

        long duration = System.currentTimeMillis() - startTime;

        return new SynthesisResult(question, mode, analysis, context, contextDocs, duration);
    }

    /**
     * Determines the best analysis mode for a given question.
     * Uses heuristics based on question structure and keywords.
     *
     * @param question the user's question
     * @return the suggested analysis mode
     */
    public static AnalysisMode suggestMode(String question) {
        String lower = question.toLowerCase();

        // Comparison indicators
        if (lower.contains(" vs ") || lower.contains(" versus ")
                || lower.contains("compare") || lower.contains("difference between")
                || lower.contains("which is better") || lower.contains("pros and cons")
                || lower.contains("trade-off") || lower.contains("tradeoff")) {
            return AnalysisMode.COMPARISON;
        }

        // Impact indicators
        if (lower.contains("what if") || lower.contains("impact of")
                || lower.contains("what would happen") || lower.contains("consequence")
                || lower.contains("effect of") || lower.contains("if we change")
                || lower.contains("migrate") || lower.contains("refactor")) {
            return AnalysisMode.IMPACT;
        }

        // Gap analysis indicators
        if (lower.contains("missing") || lower.contains("what's lacking")
                || lower.contains("gaps") || lower.contains("incomplete")
                || lower.contains("what do we need") || lower.contains("what else")) {
            return AnalysisMode.GAP_ANALYSIS;
        }

        // Default to perspectives for complex/ambiguous questions
        return AnalysisMode.PERSPECTIVES;
    }

    /**
     * Checks if a question is complex enough to benefit from directed synthesis.
     *
     * <p>Triggers:
     * <ul>
     *   <li>Questions longer than 15 words</li>
     *   <li>Questions containing "should", "which", "pros/cons", "trade-offs"</li>
     *   <li>Questions with comparative or evaluative language</li>
     * </ul>
     *
     * @param question the user's question
     * @return true if the question might benefit from perspectives
     */
    public static boolean isPerspectivesCandidate(String question) {
        String lower = question.toLowerCase().trim();

        // Word count check
        int wordCount = lower.split("\\s+").length;
        if (wordCount > 15) return true;

        // Keyword triggers
        String[] triggers = {
                "should", "which", "pros", "cons", "trade-off", "tradeoff",
                "best approach", "best way", "recommend", "better",
                "advantages", "disadvantages", "worth it", "feasible",
                "strategy", "compare", "choose", "decide", "evaluate",
                "vs", "versus", "alternative"
        };

        for (String trigger : triggers) {
            if (lower.contains(trigger)) return true;
        }

        return false;
    }

    /**
     * Generates a suggestion message for the perspectives command.
     *
     * @param question the original question
     * @return the suggestion message
     */
    public static String suggestPerspectives(String question) {
        return String.format(
                "  This question might benefit from multiple perspectives. Try:\n" +
                "  synthesis perspectives '%s'", truncateForSuggestion(question));
    }

    /**
     * Gathers context from the search index for the given question.
     */
    String gatherContext(String question, SearchIndex index) throws IOException {
        // Search for relevant documents
        List<SearchResult> results = index.search(question, 10);

        if (results.isEmpty()) {
            return "No relevant workspace documents found for this question.";
        }

        StringBuilder context = new StringBuilder();
        context.append("Relevant workspace files (").append(results.size()).append(" found):\n\n");

        for (SearchResult result : results) {
            context.append("File: ").append(result.relativePath());
            if (result.fileType() != null) {
                context.append(" (").append(result.fileType()).append(")");
            }
            context.append("\n");

            if (!result.summary().isEmpty()) {
                context.append("Summary: ").append(result.summary()).append("\n");
            }
            if (!result.headings().isEmpty()) {
                context.append("Headings: ").append(result.headings()).append("\n");
            }
            context.append("\n");
        }

        return context.toString();
    }

    private int countContextDocuments(String context) {
        if (context == null || context.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = context.indexOf("File: ", idx)) >= 0) {
            count++;
            idx += 6;
        }
        return count;
    }

    private static String truncateForSuggestion(String s) {
        if (s.length() <= 60) return s;
        return s.substring(0, 57) + "...";
    }
}
