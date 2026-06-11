package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.DirectedSynthesisEngine;
import io.exoreaction.synthesis.ai.DirectedSynthesisEngine.AnalysisMode;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generates multiple analytical perspectives on a complex question.
 *
 * <p>Unlike the simple {@code ask} command which gives a single answer,
 * {@code perspectives} examines a question through multiple lenses:
 * <ul>
 *   <li><b>perspectives</b> (default): 3-5 distinct analytical viewpoints</li>
 *   <li><b>comparison</b>: Structured comparison of options</li>
 *   <li><b>impact</b>: Ripple-effect analysis</li>
 *   <li><b>gaps</b>: Identify missing pieces and opportunities</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   synthesis perspectives "Should we migrate to microservices?"
 *   synthesis perspectives --mode comparison "Spring Boot vs Quarkus for our use case"
 *   synthesis perspectives --mode impact "What if we drop Java 11 support?"
 *   synthesis perspectives --mode gaps "What's missing in our testing strategy?"
 *   synthesis perspectives -n 5 "How should we approach the refactoring?"
 * </pre>
 */
@Command(
        name = "perspectives",
        description = "Analyze a question from multiple perspectives (directed synthesis)",
        mixinStandardHelpOptions = true
)
public class PerspectivesCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "The question to analyze from multiple perspectives"
    )
    private String question;

    @Option(
            names = {"-m", "--mode"},
            description = "Analysis mode: perspectives (default), comparison, impact, gaps",
            defaultValue = "auto"
    )
    private String mode;

    @Option(
            names = {"-n", "--num-perspectives"},
            description = "Number of perspectives to generate (default: 4, range: 2-7)",
            defaultValue = "4"
    )
    private int numPerspectives;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed output including context used",
            defaultValue = "false"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        long startMs = System.nanoTime();
        boolean metricsSuccess = false;
        String metricsWs = "unknown";
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            metricsWs = workspaceRoot.toString();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load config and create AI client
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<AiClient> clientOpt = AiClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                AnsiOutput.printError("AI not configured. Set ai.enabled=true and ANTHROPIC_API_KEY.");
                AnsiOutput.printInfo("The 'perspectives' command requires AI to generate analysis.");
                return 1;
            }

            // Validate perspective count
            numPerspectives = Math.max(2, Math.min(7, numPerspectives));

            // Determine analysis mode
            AnalysisMode analysisMode = resolveMode(mode, question);

            // Print header
            System.out.println();
            String modeLabel = switch (analysisMode) {
                case PERSPECTIVES -> "Multi-Perspective Analysis";
                case COMPARISON -> "Comparative Analysis";
                case IMPACT -> "Impact Analysis";
                case GAP_ANALYSIS -> "Gap Analysis";
            };
            AnsiOutput.printHeader("Synthesis - " + modeLabel);
            System.out.println("  " + AnsiOutput.bold("Question: ") + question);
            System.out.println("  " + AnsiOutput.dim("Mode: " + analysisMode.name().toLowerCase()
                    + (analysisMode == AnalysisMode.PERSPECTIVES ?
                    " (" + numPerspectives + " perspectives)" : "")));
            System.out.println();
            AnsiOutput.printInfo("Gathering context and generating analysis...");
            System.out.println();

            // Run directed synthesis
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                DirectedSynthesisEngine engine = new DirectedSynthesisEngine(
                        clientOpt.get(), config.getAi().getMaxTokens());

                DirectedSynthesisEngine.SynthesisResult result =
                        engine.analyze(question, index, analysisMode, numPerspectives);

                // Print analysis
                System.out.println(result.analysis());
                System.out.println();

                // Print metadata
                System.out.println(AnsiOutput.dim("---"));
                System.out.printf("  %s context documents | %s%n",
                        AnsiOutput.bold(String.valueOf(result.contextDocuments())),
                        AnsiOutput.dim(String.format("%.1fs", result.durationMs() / 1000.0)));

                if (verbose) {
                    System.out.println();
                    AnsiOutput.printInfo("Context used:");
                    System.out.println(AnsiOutput.dim(result.context()));
                }

                System.out.println();
            }

            metricsSuccess = true;
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Analysis failed: " + e.getMessage());
            return 1;
        } finally {
            long elapsed = (System.nanoTime() - startMs) / 1_000_000;
            parent.getMetrics().recordAiFeature("perspectives", metricsWs, elapsed, 0, metricsSuccess, false);
        }
    }

    /**
     * Resolves the analysis mode from user input or auto-detection.
     */
    private AnalysisMode resolveMode(String modeStr, String question) {
        if (modeStr == null || modeStr.equalsIgnoreCase("auto")) {
            return DirectedSynthesisEngine.suggestMode(question);
        }
        return switch (modeStr.toLowerCase()) {
            case "comparison", "compare" -> AnalysisMode.COMPARISON;
            case "impact" -> AnalysisMode.IMPACT;
            case "gaps", "gap" -> AnalysisMode.GAP_ANALYSIS;
            default -> AnalysisMode.PERSPECTIVES;
        };
    }
}
