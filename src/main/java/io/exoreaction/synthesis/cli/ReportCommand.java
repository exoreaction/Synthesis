package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.report.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 *   synthesis report --period 2w                        # Coverage period (explicit)
 *   synthesis report                                    # Auto-period: since last report, or 7d default
 *   synthesis report --history                          # Show report generation history
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
            description = "Coverage period: 1w, 2w, 1m (default: since last report, or 7d)"
    )
    private String period;

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
            names = {"--history"},
            description = "Show report generation history (when each target/topic was last generated)"
    )
    private boolean showHistory = false;

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
            if (showHistory) {
                return printHistory();
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

            // Resolve period: if not explicitly set, use history-based delta
            boolean periodFromHistory = false;
            if (period == null) {
                period = resolvePeriodFromHistory(reportTarget.cliValue(), reportTopic.cliValue());
                periodFromHistory = true;
            }

            // Validate period
            if (!isValidPeriod(period)) {
                AnsiOutput.printError("Invalid period: '" + period + "'. Valid periods: 1w, 2w, 1m, or Nd (e.g. 5d)");
                return 1;
            }

            // Load config and create AI client
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<AiClient> clientOpt = AiClient.create(config.getAi());

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
                    estimateDocs = new BusinessDocumentFinder().discover(workspaceRoot, reportTopic, period);
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
                documents = new BusinessDocumentFinder().discover(workspaceRoot, reportTopic, period);
            }
            documentFingerprint = BusinessDocumentFinder.generateFingerprint(documents);

            // Guard: no documents found for entity (#47)
            if (entityName != null && documents.isEmpty()) {
                String entityType = reportTopic == ReportTopic.CLIENT ? "client" : "product";
                AnsiOutput.printError("No documents found for " + entityType
                        + " \"" + entityName + "\".");
                List<String> suggestions = suggestSimilarEntities(workspaceRoot, entityName, reportTopic);
                if (!suggestions.isEmpty()) {
                    System.err.println("  Did you mean one of these?");
                    for (String s : suggestions) {
                        System.err.println("    --" + entityType + " " + s);
                    }
                } else {
                    System.err.println("  Expected: eXOReaction/"
                            + (reportTopic == ReportTopic.CLIENT ? "clients/" : "products/"));
                }
                return 1;
            }

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

            // Record in report history (#250)
            recordReportHistory(reportTarget, reportTopic, result, documents);

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
     *   <li>Business topic reports: saved to configured {@code report.outputDir} or {@code .synthesis/reports/}</li>
     *   <li>Suppressed when {@code --no-save} or {@code --output} is given</li>
     * </ul>
     */
    private Optional<Path> determineAutoSavePath(Path workspaceRoot, ReportResult result, String entityName) {
        if (noSave || outputFile != null) return Optional.empty();

        String filename = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "-" + result.topic().cliValue()
                + "-" + result.target().cliValue()
                + ".md";

        // Entity reports: always co-locate with entity directory (unaffected by outputDir)
        if (entityName != null) {
            Optional<Path> entityRoot = new EntityDocumentFinder()
                    .findEntityRoot(workspaceRoot, entityName, result.topic());
            if (entityRoot.isPresent()) {
                return Optional.of(entityRoot.get().resolve("reports").resolve(filename));
            }
        }

        // Business topic reports: use configured outputDir or default .synthesis/reports/
        SynthesisConfig config = loadConfigQuietly(workspaceRoot);
        return Optional.of(new WorkspaceManager(workspaceRoot).getReportsPath(config).resolve(filename));
    }

    private static SynthesisConfig loadConfigQuietly(Path workspaceRoot) {
        try {
            return ConfigLoader.load(workspaceRoot);
        } catch (IOException e) {
            return new SynthesisConfig();
        }
    }

    /**
     * Returns names of existing entities similar to the given name, for error suggestions (#47).
     */
    private List<String> suggestSimilarEntities(Path workspaceRoot, String entityName,
                                                 ReportTopic topic) {
        List<String> suggestions = new ArrayList<>();
        Path searchRoot = topic == ReportTopic.CLIENT
                ? workspaceRoot.resolve("eXOReaction/clients")
                : workspaceRoot.resolve("eXOReaction/products");
        if (!Files.isDirectory(searchRoot)) return suggestions;
        try (var stream = Files.list(searchRoot)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .map(name -> name.startsWith("opportunity-")
                            ? name.substring("opportunity-".length()) : name)
                    .distinct().sorted()
                    .forEach(suggestions::add);
        } catch (IOException e) { /* ignore */ }
        return suggestions;
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
        if ("1w".equals(period) || "2w".equals(period) || "1m".equals(period)) {
            return true;
        }
        // Accept dynamic day-based periods (e.g. "5d", "12d") from history delta
        if (period != null && period.endsWith("d")) {
            try {
                int days = Integer.parseInt(period.substring(0, period.length() - 1));
                return days > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Resolves the period from report history. If a previous report exists for this
     * (target, topic), computes the delta in days. Otherwise defaults to "1w" (7 days).
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/250">#250</a>
     */
    private String resolvePeriodFromHistory(String target, String topic) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            ReportHistoryRepository history = new ReportHistoryRepository(conn);
            Optional<Integer> daysSince = history.daysSinceLastReport(target, topic);

            if (daysSince.isPresent()) {
                int days = daysSince.get();
                Optional<Instant> lastGenerated = history.getLastGenerated(target, topic);
                String lastDate = lastGenerated.map(instant ->
                        instant.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MMM d"))).orElse("unknown");
                System.err.println("  " + AnsiOutput.dim("Using period since last report: "
                        + days + " day" + (days != 1 ? "s" : "")
                        + " (last generated: " + lastDate + ")"));
                return days + "d";
            } else {
                System.err.println("  " + AnsiOutput.dim(
                        "No previous report for " + target + "/" + topic + ", defaulting to 7 days"));
                return "1w";
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not query report history: " + e.getMessage());
            return "1w";
        }
    }

    /**
     * Records successful report generation in the history table.
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/250">#250</a>
     */
    private void recordReportHistory(ReportTarget reportTarget, ReportTopic reportTopic,
                                      ReportResult result, List<ReportDocument> documents) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            ReportHistoryRepository history = new ReportHistoryRepository(conn);
            history.recordGeneration(
                    reportTarget.cliValue(),
                    reportTopic.cliValue(),
                    result.generatedAt(),
                    periodToDays(result.period()),
                    documents != null ? documents.size() : null,
                    outputFile
            );
        } catch (Exception e) {
            System.err.println("Warning: Failed to record report history: " + e.getMessage());
        }
    }

    /**
     * Shows the report generation history table.
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/250">#250</a>
     */
    private int printHistory() {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            ReportHistoryRepository history = new ReportHistoryRepository(conn);
            List<ReportHistoryRepository.ReportHistoryEntry> entries = history.getAllHistory();

            if (entries.isEmpty()) {
                System.out.println("No report history found. Run 'synthesis report' to generate your first report.");
                return 0;
            }

            System.out.println("Report Generation History");
            System.out.println("=".repeat(78));
            System.out.printf("%-12s %-14s %-24s %-10s %-8s%n",
                    "Target", "Topic", "Last Generated", "Period", "Sources");
            System.out.println("-".repeat(78));

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
            for (ReportHistoryRepository.ReportHistoryEntry entry : entries) {
                System.out.printf("%-12s %-14s %-24s %-10s %-8s%n",
                        entry.target(),
                        entry.topic(),
                        formatter.format(entry.generatedAt()),
                        entry.periodDays() + "d",
                        entry.sourceDocuments() != null ? entry.sourceDocuments().toString() : "-");
            }
            System.out.println("=".repeat(78));
            System.out.println(entries.size() + " report combination" + (entries.size() != 1 ? "s" : "") + " tracked.");

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Failed to retrieve report history: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Converts a period string to a number of days.
     *
     * @param period the period string (e.g. "1w", "2w", "1m", "5d")
     * @return the number of days
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/250">#250</a>
     */
    public static int periodToDays(String period) {
        if (period == null) return 7;
        // Dynamic day-based period (e.g. "5d")
        if (period.endsWith("d")) {
            try {
                return Integer.parseInt(period.substring(0, period.length() - 1));
            } catch (NumberFormatException ignored) {
                // Fall through
            }
        }
        return switch (period) {
            case "1w" -> 7;
            case "2w" -> 14;
            case "1m" -> 30;
            default -> 7;
        };
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
