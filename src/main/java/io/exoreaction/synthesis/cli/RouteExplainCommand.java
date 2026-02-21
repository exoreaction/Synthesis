package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Read-only CLI command that shows how the unified router scores a file
 * against all candidate directories.
 *
 * <p>Usage:
 * <pre>
 *   synthesis route-explain path/to/file.pdf
 *   synthesis route-explain --top 10 file.mp4
 *   synthesis route-explain --threshold 0.1 file.md
 * </pre>
 *
 * <p>Shows: top N candidates with scores, confidence levels, individual
 * scoring components, and a recommendation (route/hold/orphan).
 *
 * @since v1.13.0 (P1-07)
 */
@Command(
        name = "route-explain",
        description = "Show how the router would score a file against all candidate directories",
        mixinStandardHelpOptions = true
)
public class RouteExplainCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "File to explain routing for"
    )
    private Path filePath;

    @Option(
            names = {"--top", "-n"},
            description = "Number of top candidates to show (default: 5)",
            defaultValue = "5"
    )
    private int topN;

    @Option(
            names = {"--threshold", "-t"},
            description = "Minimum score to display (default: 0.0, show all)",
            defaultValue = "0.0"
    )
    private double displayThreshold;

    @Option(
            names = {"--skip-transient"},
            description = "Exclude transient directories from candidates",
            defaultValue = "false"
    )
    private boolean skipTransient;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Resolve the file path relative to workspace root
            Path resolvedFile = filePath.isAbsolute() ? filePath : workspaceRoot.resolve(filePath);

            // Print header
            AnsiOutput.printHeader("Route Explain");
            System.out.println();

            // File info
            String fileName = resolvedFile.getFileName().toString();
            System.out.println("  File: " + AnsiOutput.bold(fileName));
            if (Files.exists(resolvedFile)) {
                String relPath = workspaceRoot.relativize(resolvedFile).toString();
                System.out.println("  Path: " + AnsiOutput.dim(relPath));
            } else {
                System.out.println("  Path: " + AnsiOutput.dim("(file does not exist -- routing by name only)"));
            }

            // Check if file is in a transient directory
            checkTransientStatus(resolvedFile, workspaceRoot);
            System.out.println();

            // Create router and score all candidates
            DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspaceRoot, null);
            List<DirectoryScorer.ScoredCandidate> allScored = router.scoreAll(resolvedFile);

            if (allScored.isEmpty()) {
                AnsiOutput.printWarning("No candidate directories found in workspace.");
                AnsiOutput.printInfo("Run 'synthesis sync' to discover directory identities.");
                return 0;
            }

            // Get the routing decision
            RoutingContext context = new RoutingContext(0.0, skipTransient, false, false);
            Optional<RoutingDecision> decision = router.route(resolvedFile, context);

            // Print candidate table
            System.out.println("  " + AnsiOutput.bold("Top " + Math.min(topN, allScored.size())
                    + " candidates") + " (of " + allScored.size() + " total):");
            System.out.println();

            int shown = 0;
            for (DirectoryScorer.ScoredCandidate candidate : allScored) {
                if (shown >= topN) break;
                if (candidate.totalScore() < displayThreshold && shown > 0) break;

                String dirName = workspaceRoot.relativize(candidate.directory()).toString();
                RoutingConfidence conf = RoutingConfidence.fromScore(candidate.totalScore());
                String confLabel = formatConfidence(conf);

                // Rank indicator
                String rank = String.format("  %d.", shown + 1);

                // Score and confidence
                String scoreStr = String.format("%.3f", candidate.totalScore());

                // Blocked indicator
                if (candidate.blocked()) {
                    System.out.printf("%s %s %s %s%n",
                            rank,
                            AnsiOutput.dim(dirName),
                            AnsiOutput.red("BLOCKED"),
                            AnsiOutput.dim("(scope-incompatible)"));
                } else {
                    System.out.printf("%s %s  %s  %s%n",
                            rank,
                            AnsiOutput.bold(dirName),
                            scoreStr,
                            confLabel);
                }

                // Scoring breakdown
                if (!candidate.reasons().isEmpty()) {
                    String reasons = String.join(", ", candidate.reasons());
                    System.out.println("     " + AnsiOutput.dim(reasons));
                }

                // Content vs scope breakdown
                if (candidate.scopeBonus() > 0.0) {
                    System.out.printf("     %s%n",
                            AnsiOutput.dim(String.format("content=%.3f + scope=%.3f",
                                    candidate.contentScore(), candidate.scopeBonus())));
                }

                System.out.println();
                shown++;
            }

            // Print recommendation
            printRecommendation(decision, allScored, workspaceRoot);

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Route explain failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Checks if the file is currently in a transient directory and notes it.
     */
    private void checkTransientStatus(Path file, Path workspaceRoot) {
        Path parentDir = file.getParent();
        if (parentDir == null) return;

        Path synthesisFile = parentDir.resolve(".synthesis.md");
        if (!Files.exists(synthesisFile)) return;

        try {
            DirectoryIdentityParser parser = new DirectoryIdentityParser();
            DirectoryIdentity identity = parser.parse(synthesisFile);
            if (identity.transient_()) {
                String dirName = workspaceRoot.relativize(parentDir).toString();
                System.out.println("  " + AnsiOutput.yellow("NOTE")
                        + ": File is in transient directory '" + dirName + "'");
            }
        } catch (Exception e) {
            // Skip silently
        }
    }

    /**
     * Prints the routing recommendation based on the decision and candidates.
     */
    private void printRecommendation(Optional<RoutingDecision> decision,
                                      List<DirectoryScorer.ScoredCandidate> allScored,
                                      Path workspaceRoot) {
        System.out.println("  " + AnsiOutput.bold("Recommendation:"));

        if (decision.isEmpty()) {
            // No match above any threshold
            if (allScored.isEmpty() || allScored.get(0).totalScore() < 0.1) {
                System.out.println("    " + AnsiOutput.dim("ORPHAN")
                        + " -- no candidate directories match this file");
            } else {
                System.out.printf("    %s -- best score %.3f is below auto-route threshold%n",
                        AnsiOutput.yellow("HOLD"),
                        allScored.get(0).totalScore());
            }
            return;
        }

        RoutingDecision d = decision.get();
        String destName = workspaceRoot.relativize(d.destination()).toString();

        if (d.ambiguous()) {
            System.out.printf("    %s -- top candidates have similar scores%n",
                    AnsiOutput.yellow("AMBIGUOUS"));
            System.out.printf("    Best match: %s (%.3f, %s)%n",
                    destName, d.score(), d.confidence().name());
            return;
        }

        switch (d.confidence()) {
            case CERTAIN -> System.out.printf("    %s -> %s (%.3f, %s)%n",
                    AnsiOutput.green("ROUTE"), destName, d.score(), d.confidence().name());
            case HIGH -> System.out.printf("    %s -> %s (%.3f, %s) -- confirm recommended%n",
                    AnsiOutput.green("ROUTE"), destName, d.score(), d.confidence().name());
            case MODERATE -> System.out.printf("    %s -> %s (%.3f, %s) -- review reasoning%n",
                    AnsiOutput.yellow("SUGGEST"), destName, d.score(), d.confidence().name());
            case LOW -> System.out.printf("    %s -> %s (%.3f, %s) -- weak match%n",
                    AnsiOutput.dim("POSSIBLE"), destName, d.score(), d.confidence().name());
            case NONE -> System.out.printf("    %s -- score %.3f is too low%n",
                    AnsiOutput.dim("HOLD"), d.score());
        }
    }

    /**
     * Formats a confidence level with color coding.
     */
    private String formatConfidence(RoutingConfidence confidence) {
        return switch (confidence) {
            case CERTAIN -> AnsiOutput.green(confidence.name());
            case HIGH -> AnsiOutput.green(confidence.name());
            case MODERATE -> AnsiOutput.yellow(confidence.name());
            case LOW -> AnsiOutput.dim(confidence.name());
            case NONE -> AnsiOutput.dim(confidence.name());
        };
    }
}
