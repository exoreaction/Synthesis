package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.changelog.SnapshotManager;
import io.exoreaction.synthesis.changelog.WorkspaceSnapshot;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.*;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.tracking.FileMovementTracker;
import io.exoreaction.synthesis.tracking.FileTrackingDatabase;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.util.ProgressReporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

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

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Maintain Workspace");

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load config and previous scan state
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
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
            }

            System.out.println();
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Maintain failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
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
        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        FileIndexer fileIndexer = new FileIndexer();
        int updated = 0;

        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            // Add new files
            for (FileMetadata fm : changes.added()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(fm);
                    index.addDocument(fileIndexer.createDocument(fm, analysis));
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
                    index.addDocument(fileIndexer.createDocument(fm, analysis));
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
