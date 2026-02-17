package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.research.*;
import io.exoreaction.synthesis.summary.CodebaseProfile;
import io.exoreaction.synthesis.summary.SummaryCache;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generates deep research reports using multi-pass AI analysis.
 *
 * <p>Runs 5 domain passes (architecture, security, quality, dependencies, evolution)
 * followed by a synthesis pass, producing research-grade reports optimised for
 * ChatGPT Deep Research, NotebookLM Infographic, or NotebookLM Presentation targets.
 *
 * <p>Usage:
 * <pre>
 *   synthesis research                                  # Full analysis → chatgpt target
 *   synthesis research --target notebooklm-infographic  # NotebookLM data dump
 *   synthesis research --target notebooklm-presentation # Chapter-based narrative
 *   synthesis research --topic security                 # Security pass + synthesis only
 *   synthesis research --passes architecture,quality    # Custom pass selection
 *   synthesis research --output report.md               # Save to file
 *   synthesis research --estimate                       # Show cost estimate, no AI call
 *   synthesis research --no-cache                       # Force fresh generation
 *   synthesis research --cache-stats                    # Show cache statistics
 *   synthesis research --cache-clear                    # Clear all cached reports
 * </pre>
 */
@Command(
        name = "research",
        description = "Generate deep research reports using multi-pass AI analysis",
        mixinStandardHelpOptions = true
)
public class ResearchCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--target", "-t"},
            description = "Output target: chatgpt (default), notebooklm-infographic, notebooklm-presentation"
    )
    private String target = "chatgpt";

    @Option(
            names = {"--topic"},
            description = "Research focus: full (default), architecture, security, quality, dependencies, evolution"
    )
    private String topic = "full";

    @Option(
            names = {"--passes"},
            description = "Comma-separated passes to run (e.g., architecture,security,synthesis). Overrides --topic."
    )
    private String passes;

    @Option(
            names = {"--output", "-o"},
            description = "Save report to file (default: print to stdout)"
    )
    private String outputFile;

    @Option(
            names = {"--estimate"},
            description = "Show cost estimate without running AI"
    )
    private boolean estimate = false;

    @Option(
            names = {"--no-cache"},
            description = "Skip cache lookup and force fresh generation"
    )
    private boolean noCache = false;

    @Option(
            names = {"--cache-stats"},
            description = "Show cache statistics for this workspace"
    )
    private boolean cacheStats = false;

    @Option(
            names = {"--cache-clear"},
            description = "Clear all cached research reports for this workspace"
    )
    private boolean cacheClear = false;

    @Option(
            names = {"--verbose", "-v"},
            description = "Print progress information to stderr during generation"
    )
    private boolean verbose = false;

    @Option(
            names = {"--max-tokens"},
            description = "Maximum tokens per pass (default: 4000)"
    )
    private int maxTokensPerPass = 4000;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Cache-only operations
            if (cacheStats) {
                return showCacheStats(workspaceRoot);
            }
            if (cacheClear) {
                return clearCache(workspaceRoot);
            }

            // Parse target and topic
            ResearchTarget researchTarget;
            try {
                researchTarget = ResearchTarget.fromStringStrict(target);
            } catch (IllegalArgumentException e) {
                AnsiOutput.printError(e.getMessage());
                return 1;
            }
            ResearchTopic researchTopic = ResearchTopic.fromString(topic);
            List<String> selectedPasses = ResearchEngine.parsePassList(passes);

            // Load config and create AI client
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());

            if (clientOpt.isEmpty() && !estimate) {
                AnsiOutput.printError("AI not configured. Set ai.enabled=true and ANTHROPIC_API_KEY.");
                AnsiOutput.printInfo("Use --estimate to preview cost without an AI key.");
                return 1;
            }

            // Build the engine (null client is fine for cost estimation)
            ResearchEngine engine = new ResearchEngine(clientOpt.orElse(null), maxTokensPerPass);

            // Build the codebase profile (always needed — fast, no AI)
            CodebaseProfile profiler = new CodebaseProfile();
            CodebaseProfile.Profile profile;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                profile = profiler.generate(index, workspaceRoot);
            }

            // Cost estimate mode — no AI call
            if (estimate) {
                ResearchEngine.CostEstimate costEstimate =
                        engine.estimateCost(profile, researchTarget, researchTopic, selectedPasses);
                System.out.println(costEstimate.format());
                return 0;
            }

            // Cache lookup
            String indexFingerprint = SummaryCache.generateIndexFingerprint(workspace.getIndexPath());
            String passesKey = selectedPasses != null
                    ? String.join(",", selectedPasses)
                    : String.join(",", resolveExpectedPasses(researchTopic, selectedPasses));
            ResearchResult result = null;

            if (!noCache) {
                try {
                    SynthesisDatabase db = SynthesisDatabase.getDefault();
                    Connection conn = db.getConnection();
                    ResearchCache cache = new ResearchCache(conn);
                    Optional<ResearchResult> cached = cache.get(
                            workspaceRoot, researchTarget, researchTopic, passesKey, indexFingerprint);
                    if (cached.isPresent()) {
                        result = cached.get();
                        if (outputFile == null) {
                            System.err.println("  " + AnsiOutput.dim("Loaded from cache (generated " +
                                    formatDuration(System.currentTimeMillis() -
                                            result.generatedAt().toEpochMilli()) + " ago)"));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Cache lookup failed: " + e.getMessage());
                }
            }

            // Generate if not cached
            if (result == null) {
                if (verbose || outputFile == null) {
                    System.err.println("  Generating research report...");
                    System.err.println("  Target: " + researchTarget.displayName());
                    System.err.println("  Topic:  " + researchTopic.displayName());
                }

                result = engine.analyze(profile, researchTarget, researchTopic, selectedPasses, verbose);

                // Store in cache
                if (!noCache) {
                    try {
                        SynthesisDatabase db = SynthesisDatabase.getDefault();
                        Connection conn = db.getConnection();
                        ResearchCache cache = new ResearchCache(conn);
                        cache.put(workspaceRoot, result, indexFingerprint);
                    } catch (Exception e) {
                        System.err.println("Warning: Cache storage failed: " + e.getMessage());
                    }
                }
            }

            // Output
            String report = result.finalReport();
            if (outputFile != null) {
                Path outPath = Path.of(outputFile);
                Files.writeString(outPath, report);
                AnsiOutput.printSuccess("Research report saved to: " + outPath.toAbsolutePath());
                System.err.println("  Passes:  " + result.passNames().size() + " (" +
                        String.join(", ", result.passNames()) + ")");
                System.err.println("  Tokens:  " + String.format("%,d", result.totalTokenCount()));
                System.err.println("  Cost:    $" + String.format("%.4f", result.estimatedCostUsd()));
                if (result.fromCache()) {
                    System.err.println("  Source:  cache");
                } else {
                    System.err.println("  Time:    " + result.generationTimeMs() + "ms");
                }
            } else {
                System.out.println(report);
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Research failed: " + e.getMessage());
            return 1;
        }
    }

    private int showCacheStats(Path workspaceRoot) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            ResearchCache cache = new ResearchCache(conn);
            ResearchCache.CacheStats stats = cache.getStats(workspaceRoot);

            System.out.println("Research Cache Statistics");
            System.out.println("═══════════════════════════════");
            System.out.println("Workspace: " + workspaceRoot);
            System.out.println("Entries:   " + stats.entries());
            System.out.println("Total hits: " + stats.totalHits());
            System.out.println("Total tokens: " + String.format("%,d", stats.totalTokens()));
            System.out.println("Total cost: $" + String.format("%.4f", stats.totalCostUsd()));
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Cache stats failed: " + e.getMessage());
            return 1;
        }
    }

    private int clearCache(Path workspaceRoot) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            ResearchCache cache = new ResearchCache(conn);
            int removed = cache.clearWorkspace(workspaceRoot);
            AnsiOutput.printSuccess("Cleared " + removed + " research cache entries.");
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Cache clear failed: " + e.getMessage());
            return 1;
        }
    }

    private List<String> resolveExpectedPasses(ResearchTopic topic, List<String> selectedPasses) {
        if (selectedPasses != null && !selectedPasses.isEmpty()) return selectedPasses;
        if (topic == ResearchTopic.FULL_ANALYSIS) return ResearchEngine.ALL_PASSES;
        String passName = ResearchPrompts.passNameFor(topic);
        return List.of(passName, "synthesis");
    }

    private String formatDuration(long millis) {
        if (millis < 60_000) return (millis / 1000) + "s ago";
        if (millis < 3_600_000) return (millis / 60_000) + "m ago";
        if (millis < 86_400_000) return (millis / 3_600_000) + "h ago";
        return (millis / 86_400_000) + "d ago";
    }
}
