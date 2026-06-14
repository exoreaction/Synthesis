package io.exoreaction.synthesis.research;

import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.summary.CodebaseProfile.Profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates multi-pass AI analysis for research-grade reports.
 *
 * <p>Runs 5 domain passes (architecture, security, quality, dependencies, evolution)
 * followed by a synthesis pass that receives ALL previous pass outputs as context.
 *
 * <p>Supports pass selection to run only a subset of passes, verbose progress
 * reporting, and cost estimation without actual AI calls.
 */
public class ResearchEngine {

    /** All domain pass names in execution order. */
    public static final List<String> ALL_PASSES = List.of(
            "architecture", "security", "quality", "dependencies", "evolution", "synthesis"
    );

    /** Domain-only passes (excluding synthesis). */
    public static final List<String> DOMAIN_PASSES = List.of(
            "architecture", "security", "quality", "dependencies", "evolution"
    );

    private final AiClient client;
    private final int maxTokensPerPass;

    /**
     * Creates a ResearchEngine.
     *
     * @param client           the AI client for generation
     * @param maxTokensPerPass maximum tokens per pass output
     */
    public ResearchEngine(AiClient client, int maxTokensPerPass) {
        this.client = client;
        this.maxTokensPerPass = maxTokensPerPass;
    }

    /**
     * Returns the model being used.
     */
    public String getModel() {
        return client != null ? client.getModel() : "none";
    }

    /**
     * Runs the full multi-pass analysis.
     *
     * @param profile  the codebase profile with metrics
     * @param target   the target AI tool
     * @param topic    the research topic
     * @param verbose  whether to print progress to stderr
     * @return the research result with all pass outputs
     */
    public ResearchResult analyze(Profile profile, ResearchTarget target,
                                   ResearchTopic topic, boolean verbose) {
        return analyze(profile, target, topic, null, verbose);
    }

    /**
     * Runs selected passes of the multi-pass analysis.
     *
     * @param profile        the codebase profile with metrics
     * @param target         the target AI tool
     * @param topic          the research topic
     * @param selectedPasses pass names to run (null = all passes)
     * @param verbose        whether to print progress to stderr
     * @return the research result
     */
    public ResearchResult analyze(Profile profile, ResearchTarget target,
                                   ResearchTopic topic, List<String> selectedPasses,
                                   boolean verbose) {
        long startTime = System.currentTimeMillis();

        // Determine which passes to run
        List<String> passesToRun = resolvePassList(topic, selectedPasses);

        if (verbose) {
            System.err.println("  Research analysis: " + passesToRun.size() + " passes planned");
            System.err.println("  Target: " + target.displayName());
            System.err.println("  Model: " + getModel());
        }

        // Run domain passes
        List<ResearchPassResult> passResults = new ArrayList<>();

        for (String passName : passesToRun) {
            if ("synthesis".equals(passName)) continue; // Run synthesis last

            if (verbose) {
                System.err.print("  Running " + passName + " pass...");
            }

            long passStart = System.currentTimeMillis();
            String prompt = getPassPrompt(passName, profile, target);
            String content = client.generate(prompt, maxTokensPerPass);
            int tokens = ResearchPassResult.estimateTokens(content);

            passResults.add(new ResearchPassResult(passName, content, tokens));

            if (verbose) {
                long passTime = System.currentTimeMillis() - passStart;
                System.err.println(" done (" + tokens + " tokens, " + passTime + "ms)");
            }
        }

        // Run synthesis pass if included
        if (passesToRun.contains("synthesis") && !passResults.isEmpty()) {
            if (verbose) {
                System.err.print("  Running synthesis pass...");
            }

            long synthStart = System.currentTimeMillis();
            String allContent = passResults.stream()
                    .map(p -> "=== " + p.passName().toUpperCase() + " PASS ===\n" + p.content())
                    .collect(Collectors.joining("\n\n"));

            String synthesisPrompt = ResearchPrompts.synthesisPass(allContent, target, topic);
            // Synthesis gets more tokens (it must weave everything together)
            int synthesisTokens = Math.min(maxTokensPerPass * 2, 16000);
            String synthesisContent = client.generate(synthesisPrompt, synthesisTokens);
            int tokens = ResearchPassResult.estimateTokens(synthesisContent);

            passResults.add(new ResearchPassResult("synthesis", synthesisContent, tokens));

            if (verbose) {
                long synthTime = System.currentTimeMillis() - synthStart;
                System.err.println(" done (" + tokens + " tokens, " + synthTime + "ms)");
            }
        }

        long generationTime = System.currentTimeMillis() - startTime;

        // Render the final report
        String finalReport = ResearchRenderer.render(
                new ResearchResult(target, topic, passResults, "", getModel(),
                        0, 0, null, 0, false));

        ResearchResult result = ResearchResult.fromGeneration(
                target, topic, passResults, finalReport, getModel(), generationTime);

        if (verbose) {
            System.err.println("  Total: " + result.totalTokenCount() + " tokens, " +
                    String.format("$%.4f", result.estimatedCostUsd()) + " estimated cost, " +
                    generationTime + "ms");
        }

        return result;
    }

    /**
     * Estimates the cost of running the analysis without actually calling the AI.
     *
     * @param profile        the codebase profile
     * @param target         the target AI tool
     * @param topic          the research topic
     * @param selectedPasses pass names to run (null = all)
     * @return cost estimate information
     */
    public CostEstimate estimateCost(Profile profile, ResearchTarget target,
                                      ResearchTopic topic, List<String> selectedPasses) {
        List<String> passesToRun = resolvePassList(topic, selectedPasses);

        // Estimate input tokens per pass (prompt + context metrics)
        int avgInputTokensPerPass = 2000; // Rough estimate for prompt + metrics
        int avgOutputTokensPerPass = maxTokensPerPass;

        // Count domain passes and synthesis pass
        int domainPassCount = (int) passesToRun.stream()
                .filter(p -> !"synthesis".equals(p))
                .count();
        boolean hasSynthesis = passesToRun.contains("synthesis");

        int totalInputTokens = domainPassCount * avgInputTokensPerPass;
        int totalOutputTokens = domainPassCount * avgOutputTokensPerPass;

        if (hasSynthesis) {
            // Synthesis gets all previous output as input
            totalInputTokens += totalOutputTokens + 1000; // all previous output + synthesis prompt
            totalOutputTokens += Math.min(maxTokensPerPass * 2, 16000); // synthesis output
        }

        String model = getModel();
        double inputCostPerMToken;
        double outputCostPerMToken;

        if (model.contains("opus")) {
            inputCostPerMToken = 15.0;
            outputCostPerMToken = 75.0;
        } else {
            inputCostPerMToken = 3.0;
            outputCostPerMToken = 15.0;
        }

        double inputCost = totalInputTokens * inputCostPerMToken / 1_000_000;
        double outputCost = totalOutputTokens * outputCostPerMToken / 1_000_000;
        double totalCost = inputCost + outputCost;

        return new CostEstimate(
                passesToRun, domainPassCount + (hasSynthesis ? 1 : 0),
                totalInputTokens, totalOutputTokens,
                inputCost, outputCost, totalCost, model);
    }

    /**
     * Resolves the list of passes to run based on topic and user selection.
     */
    List<String> resolvePassList(ResearchTopic topic, List<String> selectedPasses) {
        if (selectedPasses != null && !selectedPasses.isEmpty()) {
            // User-selected passes: validate and preserve order
            return selectedPasses.stream()
                    .map(String::toLowerCase)
                    .filter(ALL_PASSES::contains)
                    .toList();
        }

        if (topic == ResearchTopic.FULL_ANALYSIS) {
            return new ArrayList<>(ALL_PASSES);
        }

        // Single topic: run that pass + synthesis
        String passName = ResearchPrompts.passNameFor(topic);
        return List.of(passName, "synthesis");
    }

    /**
     * Returns the prompt for a given pass name.
     */
    private String getPassPrompt(String passName, Profile profile, ResearchTarget target) {
        return switch (passName) {
            case "architecture" -> ResearchPrompts.architecturePass(profile, target);
            case "security" -> ResearchPrompts.securityPass(profile, target);
            case "quality" -> ResearchPrompts.qualityPass(profile, target);
            case "dependencies" -> ResearchPrompts.dependenciesPass(profile, target);
            case "evolution" -> ResearchPrompts.evolutionPass(profile, target);
            default -> throw new IllegalArgumentException("Unknown pass: " + passName);
        };
    }

    /**
     * Parses a comma-separated pass list string into individual pass names.
     */
    public static List<String> parsePassList(String passesStr) {
        if (passesStr == null || passesStr.isBlank()) return null;
        return Arrays.stream(passesStr.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Cost estimation result.
     */
    public record CostEstimate(
            List<String> passes,
            int passCount,
            int estimatedInputTokens,
            int estimatedOutputTokens,
            double inputCostUsd,
            double outputCostUsd,
            double totalCostUsd,
            String model
    ) {
        /**
         * Formats the cost estimate for display.
         */
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("Research Report Cost Estimate\n");
            sb.append("═══════════════════════════════════════\n\n");
            sb.append("Model:          ").append(model).append("\n");
            sb.append("Passes:         ").append(passCount).append(" (").append(String.join(", ", passes)).append(")\n");
            sb.append("Input tokens:   ~").append(String.format("%,d", estimatedInputTokens)).append("\n");
            sb.append("Output tokens:  ~").append(String.format("%,d", estimatedOutputTokens)).append("\n");
            sb.append("\n");
            sb.append("Input cost:     $").append(String.format("%.4f", inputCostUsd)).append("\n");
            sb.append("Output cost:    $").append(String.format("%.4f", outputCostUsd)).append("\n");
            sb.append("Total cost:     $").append(String.format("%.4f", totalCostUsd)).append("\n");
            sb.append("\n");
            sb.append("Note: This is an estimate. Actual cost depends on prompt size and response length.\n");
            return sb.toString();
        }
    }
}
