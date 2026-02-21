package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.changelog.ActivityLogUpdater;
import io.exoreaction.synthesis.changelog.ChangeEvent;
import io.exoreaction.synthesis.changelog.SnapshotManager;
import io.exoreaction.synthesis.changelog.WorkspaceSnapshot;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.*;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch;
import io.exoreaction.synthesis.search.WorkspaceDiscoveryConfig;
import io.exoreaction.synthesis.tracking.FileMovementTracker;
import io.exoreaction.synthesis.tracking.FileTrackingDatabase;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.util.MediaTypes;
import io.exoreaction.synthesis.util.ProgressReporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Maintains the workspace index by detecting and applying incremental changes.
 *
 * <p>Compares the current filesystem state against the last scan state
 * ({@code .synthesis/scan-state.json}) to find new, modified, and deleted files.
 * Updates the index incrementally rather than rescanning everything.
 *
 * <p>Usage:
 * <pre>
 *   synthesis maintain              # Detect changes and update index
 *   synthesis maintain --report     # Generate report without changing index
 *   synthesis maintain --verbose    # Show detailed change list
 * </pre>
 */
@Command(
        name = "maintain",
        description = "Detect changes and update the search index incrementally",
        mixinStandardHelpOptions = true
)
public class MaintainCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--report"},
            description = "Generate report only (don't modify index)",
            defaultValue = "false"
    )
    private boolean reportOnly;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed output",
            defaultValue = "false"
    )
    private boolean verbose;

    @Option(
            names = {"--skip-git-fetch"},
            description = "Skip fetching remote changes for client codebases",
            defaultValue = "false"
    )
    private boolean skipGitFetch;

    @Option(
            names = {"--update-activity-log"},
            description = "Auto-append a draft activity log entry for today from change-tracking data",
            defaultValue = "false"
    )
    private boolean updateActivityLog;

    @Option(
            names = {"--sync"},
            description = "Run directory identity sync after maintenance (discover .synthesis.md files)",
            defaultValue = "false"
    )
    private boolean sync;

    @Option(
            names = {"--rebalance"},
            description = "Move archive files that score >= 0.7 against a directory identity back to active directories",
            defaultValue = "false"
    )
    private boolean rebalance;

    @Option(
            names = {"--dry-run"},
            description = "Preview all 9 maintenance phases without making changes",
            defaultValue = "false"
    )
    private boolean dryRun;

    @Option(
            names = {"--skip-downloads"},
            description = "Skip phases 1 and 2 (ingest + route from staging areas)",
            defaultValue = "false"
    )
    private boolean skipDownloads;

    @Option(
            names = {"--quiet"},
            description = "Show summary line only (for cron jobs)",
            defaultValue = "false"
    )
    private boolean quiet;

    @Option(
            names = {"--json"},
            description = "Machine-readable JSON output (for monitoring)",
            defaultValue = "false"
    )
    private boolean json;

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

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            // --- New 9-phase orchestrator path (unless --report is set) ---
            if (!reportOnly) {
                return runOrchestrator(workspaceRoot, config, startMs);
            }

            // --- Legacy report-only path (kept for backward compat) ---
            AnsiOutput.printHeader("Synthesis - Maintain Workspace");

            // Load config and previous scan state
            Path scanStatePath = workspace.getScanStatePath();

            if (!ScanState.exists(scanStatePath)) {
                AnsiOutput.printWarning("No previous scan state found. Run 'synthesis scan' first.");
                AnsiOutput.printInfo("Running full scan instead...");
                System.out.println();
                return runFullScan(workspace, config, workspaceRoot);
            }

            ScanState previousState = ScanState.load(scanStatePath);
            AnsiOutput.printInfo("Workspace: " + config.getWorkspace().getName());
            AnsiOutput.printInfo("Last scan: " + formatInstant(previousState.getLastScanTime()));
            AnsiOutput.printInfo("Previously indexed: " + previousState.getFileCount() + " files");
            System.out.println();

            // Scan current state
            AnsiOutput.printInfo("Scanning for changes...");
            DirectoryScanner scanner = new DirectoryScanner(workspaceRoot, config.getScan(), verbose);
            ScanResult freshScan = scanner.scan();
            System.out.println();

            // Compute changes
            ScanState.ChangeSet changes = previousState.computeChanges(freshScan);

            if (!changes.hasChanges()) {
                AnsiOutput.printSuccess("Workspace is up to date. No changes detected.");
                System.out.println();
                return 0;
            }

            // Report changes
            printChangeSummary(changes);

            if (verbose) {
                printChangeDetails(changes);
            }

            // Generate report
            if (reportOnly || verbose) {
                Path reportPath = generateReport(workspace, config, previousState, freshScan, changes);
                AnsiOutput.printInfo("Report saved: " + workspaceRoot.relativize(reportPath));
            }

            // Apply changes to index
            if (!reportOnly) {
                System.out.println();
                AnsiOutput.printInfo("Updating index...");
                int updated = applyChanges(workspace, changes);
                AnsiOutput.printSuccess("Index updated: " + updated + " documents changed.");

                // Save new scan state
                ScanState newState = ScanState.fromScanResult(freshScan);
                newState.save(scanStatePath);
                AnsiOutput.printInfo("Scan state saved.");

                // --- Integration: File Movement Tracking ---
                try {
                    SynthesisDatabase synthDb = SynthesisDatabase.getDefault();
                    FileTrackingDatabase trackingDb = new FileTrackingDatabase(synthDb);
                    FileMovementTracker tracker = new FileMovementTracker(trackingDb, 7);

                    // Detect intra-workspace movements using previous state's hashes
                    int movements = tracker.detectMovementsWithHistory(
                            previousState.getEntries(),
                            changes.deleted(),
                            changes.added(),
                            workspaceRoot.toString(),
                            workspaceRoot.toString()
                    );

                    // Resolve any pending cross-workspace deletions
                    int resolved = tracker.resolvePendingDeletions(workspaceRoot.toString(), changes);

                    // Process expired safety periods
                    int eligible = tracker.processExpiredSafetyPeriods();

                    if (movements > 0 || resolved > 0 || eligible > 0) {
                        AnsiOutput.printInfo("Tracking: " + movements + " movements detected, "
                                + resolved + " pending resolved, " + eligible + " cleanup-eligible");
                    }
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("  Warning: File tracking update failed: " + e.getMessage());
                    }
                }

                // --- Integration: Auto-snapshot for changelog ---
                try {
                    SynthesisDatabase synthDb = SynthesisDatabase.getDefault();
                    SnapshotManager snapshots = new SnapshotManager(synthDb);
                    long snapshotId = snapshots.takeSnapshotFromScanResult(
                            workspaceRoot.toString(),
                            config.getWorkspace().getName(),
                            freshScan, "maintain");

                    // Compare with previous snapshot
                    java.util.List<WorkspaceSnapshot> recent = snapshots.getSnapshots(workspaceRoot.toString(), 2);
                    if (recent.size() >= 2) {
                        snapshots.compareSnapshots(recent.get(1).id(), snapshotId);
                    }
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("  Warning: Changelog snapshot failed: " + e.getMessage());
                    }
                }

                // --- Integration: Activity log update ---
                if (updateActivityLog) {
                    try {
                        SynthesisDatabase synthDb = SynthesisDatabase.getDefault();
                        SnapshotManager snapshots = new SnapshotManager(synthDb);
                        List<ChangeEvent> events = snapshots.getChangesForWorkspace(
                                workspaceRoot.toString(), previousState.getLastScanTime());
                        Optional<ClaudeClient> aiClient = ClaudeClient.createIfApiKeyAvailable(
                                config.getAi().getModel());
                        ActivityLogUpdater updater = new ActivityLogUpdater();
                        boolean written = updater.update(workspaceRoot, events,
                                config.getWorkspace().getName(), aiClient);
                        if (written) {
                            System.out.println("  \u2713 Activity log updated");
                        } else {
                            System.out.println("  \u00b7 Activity log already up to date for today");
                        }
                    } catch (Exception e) {
                        System.err.println("  \u26a0\ufe0f  Could not update activity log: " + e.getMessage());
                    }
                }
            }

            // --- Integration: Directory identity sync ---
            if (sync) {
                try {
                    SyncCommand syncCmd = new SyncCommand();
                    syncCmd.setParent(parent);
                    syncCmd.setVerbose(false);
                    syncCmd.syncWorkspace(workspaceRoot);
                } catch (Exception e) {
                    System.err.println("  Warning: Could not sync directory identities: " + e.getMessage());
                }
            }

            // --- Integration: Archive rebalance ---
            if (rebalance) {
                try {
                    Path archiveDir = workspaceRoot.resolve("archive");
                    if (Files.isDirectory(archiveDir)) {
                        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspaceRoot, null);
                        int moved = rebalanceArchive(archiveDir, router, workspaceRoot);
                        if (moved > 0) {
                            System.out.println("  Rebalance: " + moved + " file(s) moved from archive to active directories");
                        } else {
                            System.out.println("  Rebalance: no archive files qualify for active directory routing");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("  Warning: Archive rebalance failed: " + e.getMessage());
                }

                // Transient directory rebalance (issue #203)
                try {
                    int transientMoved = rebalanceTransient(workspaceRoot);
                    if (transientMoved > 0) {
                        System.out.println("  Rebalance: " + transientMoved
                                + " file(s) moved from transient directories to permanent homes");
                    }
                } catch (Exception e) {
                    System.err.println("  Warning: Transient rebalance failed: " + e.getMessage());
                }
            }

            // --- Integration: Knowledge Edge scanning ---
            try {
                List<Path> skillDirs = new java.util.ArrayList<>();
                Path skillsDir = workspaceRoot.resolve(".claude").resolve("skills");
                Path docsDir = workspaceRoot.resolve("docs");
                if (Files.isDirectory(skillsDir)) skillDirs.add(skillsDir);
                if (Files.isDirectory(docsDir)) skillDirs.add(docsDir);
                if (!skillDirs.isEmpty()) {
                    io.exoreaction.synthesis.graph.KnowledgeEdgeScanner keScanner =
                        new io.exoreaction.synthesis.graph.KnowledgeEdgeScanner();
                    try (SearchIndex keIndex = new SearchIndex(workspace.getIndexPath())) {
                        List<io.exoreaction.synthesis.graph.KnowledgeEdge> keEdges =
                            keScanner.scan(skillDirs, keIndex, workspaceRoot);
                        if (!keEdges.isEmpty()) {
                            SynthesisDatabase keDb = SynthesisDatabase.getDefault();
                            keScanner.persist(keEdges, keDb.getConnection());
                        }
                        System.out.println("  Knowledge edges: " + keEdges.size() + " doc->source link(s) found");
                    }
                }
            } catch (Exception e) {
                if (verbose) {
                    System.err.println("  Warning: Knowledge edge scan: " + e.getMessage());
                }
            }

            // --- Integration: Knowledge Edge Reconciliation ---
            try {
                List<String> changedPaths = new ArrayList<>();
                for (var fm : changes.added())    changedPaths.add(fm.relativePath());
                for (var fm : changes.modified()) changedPaths.add(fm.relativePath());
                if (!changedPaths.isEmpty()) {
                    SynthesisDatabase reconcileDb = SynthesisDatabase.getDefault();
                    io.exoreaction.synthesis.graph.KnowledgeReconciler reconciler =
                        new io.exoreaction.synthesis.graph.KnowledgeReconciler();
                    List<io.exoreaction.synthesis.graph.KnowledgeReconciler.ReconcileResult> degraded =
                        reconciler.reconcile(changedPaths, reconcileDb.getConnection(), workspaceRoot);
                    for (var r : degraded) {
                        System.out.println("  \u26a0 Knowledge edge degraded: "
                            + r.sourcePath() + " [" + r.oldConfidence() + " -> " + r.newConfidence()
                            + ", " + r.driftDays() + "d drift] — update " + r.skillPath());
                    }
                }
            } catch (Exception e) {
                if (verbose) {
                    System.err.println("  Warning: Knowledge edge reconciliation: " + e.getMessage());
                }
            }

            // --- Git Fetch for client codebases ---
            if (!skipGitFetch) {
                fetchClientCodebases(workspaceRoot);
            }

            // --- Discover: warn about unindexed git repos in search paths ---
            try {
                Set<Path> knownWorkspaces = new HashSet<>(MultiWorkspaceSearch.discoverAllWorkspaces());
                List<Path> unindexed = DiscoverCommand.findUnindexedGitRepos(knownWorkspaces);
                if (!unindexed.isEmpty()) {
                    System.out.println("  INFO: " + unindexed.size()
                            + " git repo(s) in search paths are not indexed by Synthesis:");
                    for (Path repo : unindexed) {
                        System.out.println("    " + repo);
                    }
                    System.out.println("  Run 'synthesis discover' to review and initialize them.");
                    System.out.println();
                }
            } catch (Exception e) {
                // Never fail maintain due to discover check
            }

            System.out.println();
            metricsSuccess = true;
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Maintain failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        } finally {
            long elapsed = (System.nanoTime() - startMs) / 1_000_000;
            parent.getMetrics().recordMcpInvocation("maintain", metricsWs, elapsed, null, metricsSuccess, null);
        }
    }

    // =========================================================================
    // Orchestrator integration
    // =========================================================================

    /**
     * Runs the 9-phase orchestrator and prints formatted results.
     *
     * @return exit code (0 = success, 1 = any phase failed)
     */
    private Integer runOrchestrator(Path workspaceRoot, SynthesisConfig config,
                                     long startNanos) {
        try {
            MaintainOptions opts = new MaintainOptions(
                    dryRun, verbose, skipDownloads, skipGitFetch,
                    quiet, json, updateActivityLog, sync, rebalance);

            MaintainOrchestrator orchestrator =
                    new MaintainOrchestrator(workspaceRoot, opts, config);

            // In quiet/json mode, suppress stdout from orchestrator phases
            // (e.g. SyncCommand, DirectoryScanner progress bars) so only our
            // formatted output reaches the caller.
            MaintainResult result;
            if (quiet || json) {
                java.io.PrintStream saved = System.out;
                System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                try {
                    result = orchestrator.run();
                } finally {
                    System.setOut(saved);
                }
            } else {
                result = orchestrator.run();
            }

            // Print results and get exit code
            return printMaintainResult(result, workspaceRoot);
        } catch (Exception e) {
            AnsiOutput.printError("Maintain failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        } finally {
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
            parent.getMetrics().recordMcpInvocation(
                    "maintain", workspaceRoot.toString(), elapsed, null, true, null);
        }
    }

    /**
     * Formats and prints the 9-phase result table.
     *
     * <pre>
     *   [1/9] Ingest ......................... 3 new files ingested
     *   [2/9] Route .......................... 2 file(s) routed
     *   ...
     *   Done in 4.2s  |  3 changes applied
     * </pre>
     *
     * <p>When {@code --quiet} is set, prints exactly one line:
     * <pre>
     *   2026-02-20T14:32:01Z  OK  9 phases, 17 changes, 4.2s  health=-1
     * </pre>
     *
     * <p>When {@code --json} is set, prints a single JSON object to stdout.
     */
    private int printMaintainResult(MaintainResult result, Path workspaceRoot) {
        // --json mode: machine-readable output
        if (json) {
            return printJsonResult(result, workspaceRoot);
        }

        // --quiet mode: single summary line
        if (quiet) {
            return printQuietResult(result);
        }

        // Normal table output
        System.out.println();
        AnsiOutput.printHeader("Synthesis - Maintain Workspace");
        AnsiOutput.printInfo("Workspace: " + workspaceRoot);
        System.out.println();

        for (PhaseResult phase : result.phases()) {
            String prefix = dryRun ? "(preview) " : "";
            String phaseName = prefix + phase.name();
            String tag = String.format("  [%d/9] %-" + (dryRun ? "20" : "10") + "s", phase.phaseNumber(), phaseName);

            // Pad with dots to align the summary
            int targetWidth = 45;
            int dotsNeeded = Math.max(3, targetWidth - tag.length());
            String dots = ".".repeat(dotsNeeded);

            String status;
            if (!phase.succeeded()) {
                status = AnsiOutput.red("FAILED: " + phase.error());
            } else if (phase.summary().startsWith("skipped")) {
                status = AnsiOutput.dim(phase.summary());
            } else {
                status = phase.summary();
            }

            System.out.println(tag + " " + dots + " " + status);

            // Verbose details
            if (verbose && !phase.details().isEmpty()) {
                for (String detail : phase.details()) {
                    System.out.println("         " + AnsiOutput.dim(detail));
                }
            }
        }

        System.out.println();
        double elapsedSec = result.elapsedMs() / 1000.0;
        String changeSuffix = result.totalChanges() == 1 ? " change" : " changes";
        System.out.printf("  Done in %.1fs  |  %d%s applied%n",
                elapsedSec, result.totalChanges(), changeSuffix);
        System.out.println();

        // --dry-run footer
        if (dryRun) {
            System.out.println("  " + "─".repeat(55));
            System.out.println("  No changes made. Remove --dry-run to apply.");
            System.out.println();
        }

        return result.allSucceeded() ? 0 : 1;
    }

    /**
     * Prints a single summary line for {@code --quiet} mode.
     * Format: {@code {ISO_INSTANT}  {OK|ERROR}  {N} phases, {C} changes, {T}s  health=-1}
     */
    private int printQuietResult(MaintainResult result) {
        String status = result.allSucceeded() ? "OK" : "ERROR";
        String summary = String.format("%d phases, %d changes, %.1fs  health=-1",
                result.phases().size(),
                result.totalChanges(),
                result.elapsedMs() / 1000.0);
        System.out.println(Instant.now().toString() + "  " + status + "  " + summary);
        return dryRun ? 0 : (result.allSucceeded() ? 0 : 1);
    }

    /**
     * Prints a JSON object for {@code --json} mode.
     */
    private int printJsonResult(MaintainResult result, Path workspaceRoot) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("timestamp", Instant.now().toString());
            output.put("workspace", workspaceRoot.getFileName().toString());
            output.put("status", result.allSucceeded() ? "OK" : "ERROR");
            output.put("durationMs", result.elapsedMs());
            output.put("health", -1);

            List<Map<String, Object>> phases = new ArrayList<>();
            for (PhaseResult phase : result.phases()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", phase.name());
                p.put("status", phase.succeeded() ? "OK" : "ERROR");
                p.put("changes", phase.changeCount());
                if (!phase.succeeded() && phase.error() != null) {
                    p.put("error", phase.error());
                }
                phases.add(p);
            }
            output.put("phases", phases);
            output.put("pending", 0);

            System.out.println(mapper.writeValueAsString(output));
        } catch (Exception e) {
            System.err.println("{\"error\":\"" + e.getMessage() + "\"}");
        }
        return result.allSucceeded() ? 0 : 1;
    }

    // =========================================================================
    // Rebalance (used by orchestrator phase 5 and legacy --rebalance flag)
    // =========================================================================

    /**
     * Walks transient directories and moves media files that have a strong identity
     * match (score >= 0.5) to the matching permanent directory.
     *
     * <p>Uses the unified {@link DirectoryIdentityRouter} with {@code skipTransient=true}
     * to avoid routing files back into other transient directories.
     *
     * <p><b>Threshold mapping (P1-05):</b> The old SubjectBasedRouter used threshold 0.7
     * (pure token overlap * confidence). The DirectoryScorer provides richer scoring
     * (type + format + pattern + token match), so an equivalent rebalance threshold
     * is 0.5 (MODERATE confidence).
     *
     * @param workspaceRoot the workspace root directory
     * @return the number of files moved
     * @since v1.9.9 (issue #203), unified routing in P1-05
     */
    public int rebalanceTransient(Path workspaceRoot) throws IOException {
        DirectoryIdentityParser idParser = new DirectoryIdentityParser();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspaceRoot, null);
        int moved = 0;

        // Find all transient directories with .synthesis.md
        List<Path> transientDirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workspaceRoot, 6)) {
            walk.filter(Files::isDirectory)
                .filter(dir -> !dir.equals(workspaceRoot))
                .filter(dir -> !dir.getFileName().toString().startsWith("."))
                .filter(dir -> Files.exists(dir.resolve(".synthesis.md")))
                .forEach(dir -> {
                    DirectoryIdentity identity = idParser.parse(dir.resolve(".synthesis.md"));
                    if (identity.transient_()) {
                        transientDirs.add(dir);
                    }
                });
        }

        for (Path dir : transientDirs) {
            List<Path> mediaFiles;
            try (Stream<Path> files = Files.list(dir)) {
                mediaFiles = files
                        .filter(Files::isRegularFile)
                        .filter(this::isMediaFile)
                        .toList();
            }

            for (Path file : mediaFiles) {
                // Use unified router with skipTransient=true, threshold 0.5
                Optional<DirectoryIdentityRouter.RouteResult> match =
                        router.route(file, 0.5, true);
                if (match.isPresent() && !match.get().ambiguous()) {
                    Path dest = match.get().directory();
                    Files.createDirectories(dest);
                    try {
                        Files.move(file, dest.resolve(file.getFileName()));
                        if (verbose) {
                            System.out.println("    " + workspaceRoot.relativize(file)
                                    + " → " + workspaceRoot.relativize(dest)
                                    + " (score " + String.format("%.2f", match.get().score()) + ")");
                        }
                        moved++;

                        // Issue #204: record forwarding pointer in source directory's .synthesis.md
                        recordForwardingPointer(
                                dir, file.getFileName().toString(),
                                workspaceRoot.relativize(dest).toString(),
                                match.get().score(), idParser);

                    } catch (IOException e) {
                        if (verbose) {
                            System.err.println("    Could not move " + file.getFileName()
                                    + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        return moved;
    }

    /**
     * Records a forwarding pointer in the source directory's {@code .synthesis.md}
     * when a file is moved during rebalance.
     *
     * <p>The pointer captures: which file moved, where it went, when, by what
     * operation, and the routing score as a reason string.
     *
     * @param sourceDir the directory the file was moved from
     * @param fileName  the name of the file that was moved
     * @param movedTo   the workspace-relative path of the destination directory
     * @param score     the routing score that triggered the move
     * @param parser    the parser to use for read/write
     * @since v1.9.9 (issue #204)
     */
    void recordForwardingPointer(Path sourceDir, String fileName,
                                  String movedTo, double score,
                                  DirectoryIdentityParser parser) {
        try {
            Path synthesisFile = sourceDir.resolve(".synthesis.md");
            DirectoryIdentity existing = parser.parse(synthesisFile);

            ForwardingPointer pointer = new ForwardingPointer(
                    fileName,
                    movedTo,
                    java.time.Instant.now(),
                    "rebalance",
                    "score " + String.format("%.2f", score)
            );

            // Append the new pointer to the existing moved_files list
            List<ForwardingPointer> updatedPointers = new ArrayList<>(existing.movedFiles());
            updatedPointers.add(pointer);

            DirectoryIdentity updated = new DirectoryIdentity(
                    existing.acceptsTypes(),
                    existing.acceptsFormats(),
                    existing.acceptsPatterns(),
                    existing.scopeLevel(),
                    existing.scopeOrganization(),
                    existing.scopeEntity(),
                    existing.confidence(),
                    existing.lastSynced(),
                    existing.source(),
                    existing.description(),
                    existing.rejectsTypes(),
                    existing.aliases(),
                    existing.transient_(),
                    updatedPointers
            );

            parser.write(synthesisFile, updated);
        } catch (Exception e) {
            if (verbose) {
                System.err.println("    Warning: Could not record forwarding pointer for "
                        + fileName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Returns true if the file is a media file (video, audio, image).
     */
    private boolean isMediaFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return MediaTypes.MEDIA_EXTENSIONS.contains(ext);
    }

    /**
     * Walks the archive directory and moves any file that scores >= 0.7 against
     * a non-ambiguous identity-declared directory back into that active directory.
     *
     * <p>Excludes files inside {@code .git} directories and frozen snapshot subtrees
     * (top-level archive subdirectories starting with {@code old-}, {@code snapshot-},
     * or {@code frozen-}).
     *
     * @return the number of files moved
     */
    public int rebalanceArchive(Path archiveDir, DirectoryIdentityRouter router, Path workspaceRoot)
            throws IOException {
        int moved = 0;
        List<Path> archiveFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(archiveDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !MaintainOrchestrator.isInsideGitDir(p))
                .filter(p -> !MaintainOrchestrator.isFrozenSubtree(p, archiveDir))
                .forEach(archiveFiles::add);
        }
        for (Path file : archiveFiles) {
            Optional<DirectoryIdentityRouter.RouteResult> routed = router.route(file, 0.7);
            if (routed.isPresent() && !routed.get().ambiguous()) {
                Path dest = routed.get().directory();
                Files.createDirectories(dest);
                try {
                    Files.move(file, dest.resolve(file.getFileName()));
                    if (verbose) {
                        System.out.println("    " + workspaceRoot.relativize(file)
                                + " → " + workspaceRoot.relativize(dest));
                    }
                    moved++;
                } catch (IOException e) {
                    if (verbose) {
                        System.err.println("    Could not move " + file.getFileName()
                                + ": " + e.getMessage());
                    }
                }
            }
        }
        return moved;
    }

    private int runFullScan(WorkspaceManager workspace, SynthesisConfig config, Path workspaceRoot) throws IOException {
        DirectoryScanner scanner = new DirectoryScanner(workspaceRoot, config.getScan(), verbose);
        ScanResult scanResult = scanner.scan();

        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        FileIndexer fileIndexer = new FileIndexer();

        int indexed = 0;
        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            index.deleteAll();

            ProgressReporter progress = new ProgressReporter("Indexing", scanResult.fileCount());
            for (FileMetadata metadata : scanResult.files()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(metadata);
                    var doc = fileIndexer.createDocument(metadata, analysis);
                    index.addDocument(doc);
                    indexed++;
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("  Warning: " + metadata.relativePath() + ": " + e.getMessage());
                    }
                }
                progress.tick();
            }
            index.commit();
            progress.complete();
        }

        // Save scan state
        ScanState state = ScanState.fromScanResult(scanResult);
        state.save(workspace.getScanStatePath());

        AnsiOutput.printSuccess("Full scan complete: " + indexed + " files indexed.");
        System.out.println();
        return 0;
    }

    private void printChangeSummary(ScanState.ChangeSet changes) {
        System.out.println("  Changes detected:");
        if (!changes.added().isEmpty()) {
            System.out.println("    " + AnsiOutput.green("+ " + changes.added().size() + " new files"));
        }
        if (!changes.modified().isEmpty()) {
            System.out.println("    " + AnsiOutput.yellow("~ " + changes.modified().size() + " modified files"));
        }
        if (!changes.deleted().isEmpty()) {
            System.out.println("    " + AnsiOutput.red("- " + changes.deleted().size() + " deleted files"));
        }
        System.out.println("    " + AnsiOutput.bold("  " + changes.totalChanges() + " total changes"));
    }

    private void printChangeDetails(ScanState.ChangeSet changes) {
        System.out.println();
        if (!changes.added().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("New files:"));
            for (FileMetadata fm : changes.added()) {
                System.out.println("    " + AnsiOutput.green("+") + " " + fm.relativePath()
                        + " (" + FileUtils.formatSize(fm.sizeBytes()) + ")");
            }
        }
        if (!changes.modified().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Modified files:"));
            for (FileMetadata fm : changes.modified()) {
                System.out.println("    " + AnsiOutput.yellow("~") + " " + fm.relativePath()
                        + " (" + FileUtils.formatSize(fm.sizeBytes()) + ")");
            }
        }
        if (!changes.deleted().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Deleted files:"));
            for (String path : changes.deleted()) {
                System.out.println("    " + AnsiOutput.red("-") + " " + path);
            }
        }
    }

    private int applyChanges(WorkspaceManager workspace, ScanState.ChangeSet changes) throws IOException {
        SynthesisConfig config = ConfigLoader.load(workspace.getWorkspaceRoot());
        SubWorkspaceResolver subWsResolver = new SubWorkspaceResolver(config);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        FileIndexer fileIndexer = new FileIndexer();
        int updated = 0;

        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            // Add new files
            for (FileMetadata fm : changes.added()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(fm);
                    String subWorkspace = subWsResolver.resolve(fm.relativePath());
                    index.addDocument(fileIndexer.createDocument(fm, analysis,
                            null, null, null, subWorkspace));
                    updated++;
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("  Warning: Failed to index " + fm.relativePath() + ": " + e.getMessage());
                    }
                }
            }

            // Re-index modified files
            for (FileMetadata fm : changes.modified()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(fm);
                    String subWorkspace = subWsResolver.resolve(fm.relativePath());
                    index.addDocument(fileIndexer.createDocument(fm, analysis,
                            null, null, null, subWorkspace));
                    updated++;
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("  Warning: Failed to re-index " + fm.relativePath() + ": " + e.getMessage());
                    }
                }
            }

            // Remove deleted files
            for (String path : changes.deleted()) {
                index.deleteByRelativePath(path);
                updated++;
            }

            index.commit();
        }

        return updated;
    }

    private Path generateReport(WorkspaceManager workspace, SynthesisConfig config,
                                ScanState previousState, ScanResult freshScan,
                                ScanState.ChangeSet changes) throws IOException {
        Path reportsDir = workspace.getReportsPath();
        Files.createDirectories(reportsDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path reportPath = reportsDir.resolve("maintain-report-" + timestamp + ".md");

        try (Writer writer = Files.newBufferedWriter(reportPath)) {
            writer.write("# Synthesis Maintenance Report\n\n");
            writer.write("**Workspace:** " + config.getWorkspace().getName() + "\n");
            writer.write("**Generated:** " + formatInstant(Instant.now()) + "\n");
            writer.write("**Previous scan:** " + formatInstant(previousState.getLastScanTime()) + "\n");
            writer.write("**Time since last scan:** " + formatDuration(
                    Duration.between(previousState.getLastScanTime(), Instant.now())) + "\n\n");

            writer.write("## Summary\n\n");
            writer.write("| Metric | Value |\n");
            writer.write("|--------|-------|\n");
            writer.write("| Previously indexed | " + previousState.getFileCount() + " files |\n");
            writer.write("| Currently found | " + freshScan.fileCount() + " files |\n");
            writer.write("| New files | " + changes.added().size() + " |\n");
            writer.write("| Modified files | " + changes.modified().size() + " |\n");
            writer.write("| Deleted files | " + changes.deleted().size() + " |\n");
            writer.write("| Total changes | " + changes.totalChanges() + " |\n\n");

            if (!changes.added().isEmpty()) {
                writer.write("## New Files\n\n");
                for (FileMetadata fm : changes.added()) {
                    writer.write("- `" + fm.relativePath() + "` ("
                            + FileUtils.formatSize(fm.sizeBytes()) + ", " + fm.fileType() + ")\n");
                }
                writer.write("\n");
            }

            if (!changes.modified().isEmpty()) {
                writer.write("## Modified Files\n\n");
                for (FileMetadata fm : changes.modified()) {
                    writer.write("- `" + fm.relativePath() + "` ("
                            + FileUtils.formatSize(fm.sizeBytes()) + ", " + fm.fileType() + ")\n");
                }
                writer.write("\n");
            }

            if (!changes.deleted().isEmpty()) {
                writer.write("## Deleted Files\n\n");
                for (String path : changes.deleted()) {
                    writer.write("- `" + path + "`\n");
                }
                writer.write("\n");
            }
        }

        return reportPath;
    }

    /**
     * Fetches remote changes for all client codebases discovered via OrganizationRegistry.
     * Also writes .synthesis-last-activity files for dashboard consumption.
     */
    private void fetchClientCodebases(Path workspaceRoot) {
        OrganizationRegistry registry = loadOrgRegistryForMaintain(workspaceRoot);
        if (registry == null || !registry.hasOrganizations()) {
            if (verbose) {
                AnsiOutput.printInfo("No organization registry found -- skipping git fetch.");
            }
            return;
        }

        // Collect all unique codebase paths
        Set<String> seen = new HashSet<>();
        List<CodebaseFetchTarget> targets = new ArrayList<>();
        for (Organization org : registry.getOrganizations()) {
            for (Client client : org.getClients()) {
                for (String codebasePath : client.getCodebases()) {
                    if (seen.add(codebasePath)) {
                        targets.add(new CodebaseFetchTarget(client.getName(), codebasePath));
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            if (verbose) {
                AnsiOutput.printInfo("No client codebases configured -- skipping git fetch.");
            }
            return;
        }

        System.out.println();
        AnsiOutput.printInfo("Fetching remote changes for client codebases...");

        for (CodebaseFetchTarget target : targets) {
            Path cbPath = Path.of(target.path);
            String label = String.format("  %-16s ", target.clientName + ":");

            if (!Files.isDirectory(cbPath)) {
                System.out.println(label + AnsiOutput.dim("skipped (directory not found)"));
                continue;
            }

            // Check if it's a git repo
            if (!Files.isDirectory(cbPath.resolve(".git"))) {
                System.out.println(label + AnsiOutput.dim("skipped (not a git repo)"));
                continue;
            }

            // Write last activity file
            writeLastActivity(cbPath);

            // Run git fetch
            try {
                ProcessBuilder fetchPb = new ProcessBuilder(
                        "git", "fetch", "--all", "--quiet");
                fetchPb.directory(cbPath.toFile());
                fetchPb.redirectErrorStream(true);
                Process fetchProcess = fetchPb.start();
                // Drain output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(fetchProcess.getInputStream()))) {
                    while (reader.readLine() != null) { /* consume */ }
                }
                int exitCode = fetchProcess.waitFor();

                if (exitCode != 0) {
                    System.out.println(label + AnsiOutput.yellow("git fetch failed (exit " + exitCode + ")"));
                    continue;
                }

                // Check for new remote commits
                int newCommits = countRemoteNewCommits(cbPath);
                String lastActivity = getRelativeLastCommit(cbPath);
                if (newCommits > 0) {
                    System.out.println(label + AnsiOutput.green("new commits (" + newCommits + " new)")
                            + AnsiOutput.dim(" -- last: " + lastActivity));
                } else {
                    System.out.println(label + "up to date"
                            + AnsiOutput.dim(" (" + lastActivity + ")"));
                }

            } catch (Exception e) {
                System.out.println(label + AnsiOutput.dim("skipped (" + e.getMessage() + ")"));
            }
        }
    }

    /**
     * Writes the most recent commit date to .synthesis-last-activity in a codebase.
     */
    private void writeLastActivity(Path cbPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "-1", "--format=%aI");
            pb.directory(cbPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String dateStr = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                dateStr = reader.readLine();
            }
            process.waitFor();
            if (dateStr != null && !dateStr.isBlank()) {
                // Extract just the date part (YYYY-MM-DD)
                String isoDate = dateStr.trim();
                if (isoDate.length() >= 10) {
                    isoDate = isoDate.substring(0, 10);
                }
                Files.writeString(cbPath.resolve(".synthesis-last-activity"), isoDate);
            }
        } catch (Exception e) {
            if (verbose) {
                System.err.println("  Warning: Could not write last-activity for " + cbPath + ": " + e.getMessage());
            }
        }
    }

    /**
     * Counts new commits on the remote that aren't in the local branch.
     */
    private int countRemoteNewCommits(Path cbPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--oneline", "HEAD..@{u}");
            pb.directory(cbPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int count = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    count++;
                }
            }
            process.waitFor();
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Gets a relative time label for the last commit (e.g., "2d ago").
     */
    private String getRelativeLastCommit(Path cbPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "-1", "--format=%ar");
            pb.directory(cbPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String result = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                result = reader.readLine();
            }
            process.waitFor();
            return result != null ? result.trim() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Loads the OrganizationRegistry for maintain operations.
     * Searches workspace root and common locations.
     */
    private OrganizationRegistry loadOrgRegistryForMaintain(Path workspaceRoot) {
        // Try the workspace root
        Path orgsFile = workspaceRoot.resolve(".synthesis").resolve("organizations.json");
        if (Files.exists(orgsFile)) {
            try {
                OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
                registry.load();
                if (registry.hasOrganizations()) return registry;
            } catch (Exception ignored) {}
        }

        // Try ~/Documents
        Path docsPath = Path.of(System.getProperty("user.home"), "Documents");
        orgsFile = docsPath.resolve(".synthesis").resolve("organizations.json");
        if (Files.exists(orgsFile)) {
            try {
                OrganizationRegistry registry = new OrganizationRegistry(docsPath);
                registry.load();
                if (registry.hasOrganizations()) return registry;
            } catch (Exception ignored) {}
        }

        // Try all discovered workspaces
        try {
            WorkspaceDiscoveryConfig config = WorkspaceDiscoveryConfig.load();
            for (Path searchPath : config.getSearchPaths()) {
                if (!Files.exists(searchPath)) continue;
                orgsFile = searchPath.resolve(".synthesis").resolve("organizations.json");
                if (Files.exists(orgsFile)) {
                    try {
                        OrganizationRegistry registry = new OrganizationRegistry(searchPath);
                        registry.load();
                        if (registry.hasOrganizations()) return registry;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private record CodebaseFetchTarget(String clientName, String path) {}

    private String formatInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 24) {
            long days = hours / 24;
            return days + " day" + (days > 1 ? "s" : "") + ", " + (hours % 24) + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + " minute" + (minutes != 1 ? "s" : "");
    }
}
