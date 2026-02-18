package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.report.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generates executive business reports from workspace business documents.
 *
 * <p>Discovers business documents (pipeline status, activity logs, events,
 * strategy files) in the workspace and uses AI to generate structured
 * executive reports. This is parallel to {@code synthesis research} but
 * for business documents, not code analysis.
 *
 * <p>Usage:
 * <pre>
 *   synthesis report                                    # Full executive report (default: --topic weekly --target ceo)
 *   synthesis report --topic pipeline                   # Pipeline status only
 *   synthesis report --topic activities                 # Recent activities summary
 *   synthesis report --topic executive                  # Full executive update
 *   synthesis report --topic decisions                  # Critical decisions needed
 *   synthesis report --product Synthesis                # Product status report (business + dev status)
 *   synthesis report --product lib-pcb                  # Product status for lib-pcb
 *   synthesis report --client Elprint                   # Client relationship health report
 *   synthesis report --client Mynder                    # Client/opportunity status (finds opportunity-Mynder)
 *   synthesis report --target ceo                       # CEO format (default)
 *   synthesis report --target board                     # Board format
 *   synthesis report --target investor                  # Investor format
 *   synthesis report --output report.md                 # Save to file
 *   synthesis report --period 2w                        # Coverage period (default: 1w)
 *   synthesis report --estimate                         # Show cost estimate, no AI call
 *   synthesis report --no-cache                         # Force fresh generation
 *   synthesis report --cache-stats                      # Cache statistics
 *   synthesis report --cache-clear                      # Clear cache
 * </pre>
 */
@Command(
        name = "report",
        description = "Generate executive business reports from workspace documents",
        mixinStandardHelpOptions = true
)
public class ReportCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--target", "-t"},
            description = "Report audience: ceo (default), board, investor"
    )
    private String target = "ceo";

    @Option(
            names = {"--topic"},
            description = "Report focus: weekly (default), pipeline, activities, executive, decisions"
    )
    private String topic = "weekly";

    @Option(
            names = {"--period", "-p"},
            description = "Coverage period: 1w (default), 2w, 1m"
    )
    private String period = "1w";

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
            description = "Clear all cached reports for this workspace"
    )
    private boolean cacheClear = false;

    @Option(
            names = {"--product"},
            description = "Generate a product status report for a named product (e.g., Synthesis, lib-pcb)"
    )
    private String product;

    @Option(
            names = {"--client"},
            description = "Generate a client status report for a named client (e.g., Elprint, Mynder)"
    )
    private String client;

    @Option(
            names = {"--no-save"},
            description = "Print to stdout only, do not auto-save to workspace"
    )
    private boolean noSave = false;

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

            // Validate mutual exclusivity of --product and --client
            if (product != null && client != null) {
                AnsiOutput.printError("Cannot use --product and --client together. Choose one.");
                return 1;
            }

            // Parse target and topic
            ReportTarget reportTarget;
            try {
                reportTarget = ReportTarget.fromStringStrict(target);
            } catch (IllegalArgumentException e) {
                AnsiOutput.printError(e.getMessage());
                return 1;
            }

            // Entity mode overrides topic
            ReportTopic reportTopic;
            String entityName = null;
            if (product != null) {
                reportTopic = ReportTopic.PRODUCT;
                entityName = product;
            } else if (client != null) {
                reportTopic = ReportTopic.CLIENT;
                entityName = client;
            } else {
                reportTopic = ReportTopic.fromString(topic);
            }

            // Validate period
            if (!isValidPeriod(period)) {
                AnsiOutput.printError("Invalid period: '" + period + "'. Valid periods: 1w, 2w, 1m");
                return 1;
            }

            // Load config and create AI client
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());

            if (clientOpt.isEmpty() && !estimate) {
                AnsiOutput.printError("AI not configured. Set ai.enabled=true and ANTHROPIC_API_KEY.");
                AnsiOutput.printInfo("Use --estimate to preview cost without an AI key.");
                return 1;
            }

            // Build the engine
            ReportEngine engine = new ReportEngine(clientOpt.orElse(null), maxTokensPerPass);

            // Cost estimate mode
            if (estimate) {
                List<ReportDocument> estimateDocs;
                if (entityName != null) {
                    EntityDocumentFinder ef = new EntityDocumentFinder();
                    estimateDocs = reportTopic == ReportTopic.CLIENT
                            ? ef.discoverForClient(workspaceRoot, entityName)
                            : ef.discoverForProduct(workspaceRoot, entityName);
                } else {
                    estimateDocs = new BusinessDocumentFinder().discover(workspaceRoot, reportTopic);
                }
                if (!estimateDocs.isEmpty()) {
                    System.err.println("  Documents that will be analyzed:");
                    for (ReportDocument doc : estimateDocs) {
                        System.err.printf("    %-12s %s (%s)%n",
                                doc.category() + ":",
                                doc.relativePath(),
                                formatSize(doc.sizeBytes()));
                    }
                    System.err.println();
                }
                ReportEngine.CostEstimate costEstimate =
                        engine.estimateCost(workspaceRoot, reportTarget, reportTopic, period);
                System.out.println(costEstimate.format());
                return 0;
            }

            // Discover documents for fingerprinting
            List<ReportDocument> documents;
            String documentFingerprint;
            if (entityName != null) {
                EntityDocumentFinder ef = new EntityDocumentFinder();
                documents = reportTopic == ReportTopic.CLIENT
                        ? ef.discoverForClient(workspaceRoot, entityName)
                        : ef.discoverForProduct(workspaceRoot, entityName);
            } else {
                documents = new BusinessDocumentFinder().discover(workspaceRoot, reportTopic);
            }
            documentFingerprint = BusinessDocumentFinder.generateFingerprint(documents);

            ReportResult result = null;

            // Cache lookup
            if (!noCache) {
                try {
                    SynthesisDatabase db = SynthesisDatabase.getDefault();
                    Connection conn = db.getConnection();
                    ReportCache cache = new ReportCache(conn);
                    Optional<ReportResult> cached = cache.get(
                            workspaceRoot, reportTopic, reportTarget, period, documentFingerprint);
                    if (cached.isPresent()) {
                        result = cached.get();
                        if (outputFile == null) {
                            System.err.println("  " + AnsiOutput.dim("Loaded from cache (generated " +
                                    formatDuration(System.currentTimeMillis() -
                                            result.generatedAt().toEpochMilli()) + ")"));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Cache lookup failed: " + e.getMessage());
                }
            }

            // Generate if not cached
            if (result == null) {
                if (verbose || outputFile == null) {
                    System.err.println("  Generating business report...");
                    System.err.println("  Target: " + reportTarget.displayName());
                    if (entityName != null) {
                        System.err.println("  Entity: " + entityName + " (" + reportTopic.displayName() + ")");
                    } else {
                        System.err.println("  Topic:  " + reportTopic.displayName());
                    }
                    System.err.println("  Period: " + ReportRenderer.formatPeriod(period));
                }

                if (entityName != null) {
                    result = engine.generateForEntity(workspaceRoot, reportTarget, reportTopic,
                            entityName, period, verbose);
                } else {
                    result = engine.generate(workspaceRoot, reportTarget, reportTopic, period, verbose);
                }

                // Store in cache (always store, even with --no-cache, to refresh the entry)
                try {
                    SynthesisDatabase db = SynthesisDatabase.getDefault();
                    Connection conn = db.getConnection();
                    ReportCache cache = new ReportCache(conn);
                    cache.put(workspaceRoot, result, documentFingerprint);
                } catch (Exception e) {
                    System.err.println("Warning: Cache storage failed: " + e.getMessage());
                }
            }

            // Output
            String report = ReportRenderer.render(result);

            // Auto-save: co-locate report with its entity or save to .synthesis/reports/
            Optional<Path> autoSavePath = determineAutoSavePath(workspaceRoot, result, entityName);
            if (autoSavePath.isPresent()) {
                Path savePath = autoSavePath.get();
                Files.createDirectories(savePath.getParent());
                Files.writeString(savePath, report);
                System.err.println("  Saved: " + workspaceRoot.relativize(savePath));
            }

            if (outputFile != null) {
                Path outPath = Path.of(outputFile);
                Files.createDirectories(outPath.toAbsolutePath().getParent());
                Files.writeString(outPath, report);
                AnsiOutput.printSuccess("Business report saved to: " + outPath.toAbsolutePath());
                System.err.println("  Topic:   " + result.topic().displayName());
                System.err.println("  Target:  " + result.target().displayName());
                System.err.println("  Period:  " + ReportRenderer.formatPeriod(result.period()));
                System.err.println("  Sources: " + result.documents().size() + " documents");
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
            AnsiOutput.printError("Report generation failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Determines where to auto-save the report. Returns empty if auto-save is suppressed.
     *
     * <ul>
     *   <li>Entity reports (client/product): co-located under the entity's workspace directory</li>
     *   <li>Business topic reports: saved to {@code .synthesis/reports/}</li>
     *   <li>Suppressed when {@code --no-save} or {@code --output} is given</li>
     * </ul>
     */
    private Optional<Path> determineAutoSavePath(Path workspaceRoot, ReportResult result, String entityName) {
        if (noSave || outputFile != null) return Optional.empty();

        String filename = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "-" + result.topic().cliValue()
                + "-" + result.target().cliValue()
                + ".md";

        // Entity reports: co-locate with the entity's workspace directory
        if (entityName != null) {
            Optional<Path> entityRoot = new EntityDocumentFinder()
                    .findEntityRoot(workspaceRoot, entityName, result.topic());
            if (entityRoot.isPresent()) {
                return Optional.of(entityRoot.get().resolve("reports").resolve(filename));
            }
        }

        // Business topic reports (or entity not found in workspace): .synthesis/reports/
        return Optional.of(new WorkspaceManager(workspaceRoot).getReportsPath().resolve(filename));
    }

    private int showCacheStats(Path workspaceRoot) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            ReportCache cache = new ReportCache(conn);
            ReportCache.CacheStats stats = cache.getStats(workspaceRoot);

            System.out.println("Report Cache Statistics");
            System.out.println("========================================");
            System.out.println("Workspace:   " + workspaceRoot);
            System.out.println("Entries:     " + stats.entries());
            System.out.println("Total hits:  " + stats.totalHits());
            System.out.println("Total tokens: " + String.format("%,d", stats.totalTokens()));
            System.out.println("Total cost:  $" + String.format("%.4f", stats.totalCostUsd()));
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
            ReportCache cache = new ReportCache(conn);
            int removed = cache.clearWorkspace(workspaceRoot);
            AnsiOutput.printSuccess("Cleared " + removed + " report cache entries.");
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Cache clear failed: " + e.getMessage());
            return 1;
        }
    }

    private boolean isValidPeriod(String period) {
        return "1w".equals(period) || "2w".equals(period) || "1m".equals(period);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private String formatDuration(long millis) {
        if (millis < 60_000) return (millis / 1000) + "s ago";
        if (millis < 3_600_000) return (millis / 60_000) + "m ago";
        if (millis < 86_400_000) return (millis / 3_600_000) + "h ago";
        return (millis / 86_400_000) + "d ago";
    }
}
