package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.changelog.*;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.*;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for cross-workspace change reporting.
 *
 * <p>Usage:
 * <pre>
 *   synthesis changelog                          # Changes since last snapshot
 *   synthesis changelog --since 7d              # Changes in last 7 days
 *   synthesis changelog --weekly                # Weekly executive report
 *   synthesis changelog --snapshot              # Take a snapshot now
 *   synthesis changelog --significance notable  # Filter by significance
 *   synthesis changelog --format json           # Output as JSON
 *   synthesis changelog --output report.md      # Write to file
 * </pre>
 */
@Command(
        name = "changelog",
        description = "Cross-workspace change reporting and snapshots",
        mixinStandardHelpOptions = true
)
public class ChangelogCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--since"}, description = "Duration: 7d, 24h, 2w, 30d (default: 7d)")
    private String since;

    @Option(names = {"--weekly"}, description = "Generate weekly executive report", defaultValue = "false")
    private boolean weekly;

    @Option(names = {"--snapshot"}, description = "Take a snapshot now", defaultValue = "false")
    private boolean takeSnapshot;

    @Option(names = {"--significance"}, description = "Minimum significance: noise, normal, notable, critical")
    private String significance;

    @Option(names = {"-f", "--format"}, description = "Output format: text, markdown, json", defaultValue = "text")
    private String format;

    @Option(names = {"-o", "--output"}, description = "Output file (default: stdout)")
    private Path output;

    @Option(names = {"-v", "--verbose"}, description = "Show detailed output", defaultValue = "false")
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Change Report");

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            SnapshotManager snapshots = new SnapshotManager(db);

            // Handle snapshot action
            if (takeSnapshot) {
                return handleTakeSnapshot(workspace, config, snapshots, workspaceRoot);
            }

            // Handle weekly report
            if (weekly) {
                since = "7d";
            }

            // Default: show changes
            Instant sinceInstant = parseSince(since != null ? since : "7d");
            ChangeSignificance minSig = significance != null
                    ? ChangeSignificance.fromDbValue(significance)
                    : ChangeSignificance.NORMAL;

            // Check if we need to take a snapshot first to detect changes
            WorkspaceSnapshot latest = snapshots.getLatestSnapshot(workspaceRoot.toString());
            if (latest == null) {
                AnsiOutput.printWarning("No snapshots found. Taking initial snapshot...");
                handleTakeSnapshot(workspace, config, snapshots, workspaceRoot);
                AnsiOutput.printInfo("Run 'synthesis changelog' again after changes occur.");
                return 0;
            }

            // Get change events
            List<ChangeEvent> events = snapshots.getChangesForWorkspace(
                    workspaceRoot.toString(), sinceInstant);

            if (events.isEmpty()) {
                AnsiOutput.printInfo("No changes detected since " + sinceInstant);
                AnsiOutput.printInfo("Tip: Run 'synthesis changelog --snapshot' to take a new snapshot,");
                AnsiOutput.printInfo("     then 'synthesis changelog' to see changes since then.");
                return 0;
            }

            // Filter by significance
            List<ChangeEvent> filtered = events.stream()
                    .filter(e -> e.significance().isAtLeast(minSig))
                    .toList();

            // Generate output
            ChangeReportGenerator generator = new ChangeReportGenerator();

            if ("markdown".equals(format) || "md".equals(format) || output != null) {
                String report = generator.generateReport(events, sinceInstant, Instant.now(), minSig);
                if (output != null) {
                    try (Writer writer = Files.newBufferedWriter(output)) {
                        writer.write(report);
                    }
                    AnsiOutput.printSuccess("Report written to: " + output);
                } else {
                    System.out.println(report);
                }
            } else {
                // Text/CLI format
                String summary = generator.generateSummary(events);
                AnsiOutput.printInfo("Period: " + sinceInstant + " to now");
                System.out.println("  " + summary);
                System.out.println();

                // Show notable+ changes inline
                for (ChangeEvent e : filtered) {
                    String icon = switch (e.changeType()) {
                        case ADDED -> AnsiOutput.green("+");
                        case MODIFIED -> AnsiOutput.yellow("~");
                        case DELETED -> AnsiOutput.red("-");
                        case MOVED -> AnsiOutput.blue(">");
                    };
                    String sigLabel = e.significance() == ChangeSignificance.CRITICAL
                            ? AnsiOutput.red("[CRITICAL]") : "";
                    System.out.println("  " + icon + " " + e.relativePath() + " " + sigLabel);
                }

                int noiseCount = events.size() - filtered.size();
                if (noiseCount > 0) {
                    System.out.println();
                    AnsiOutput.printInfo(noiseCount + " noise events filtered. Use --significance noise to see all.");
                }
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Changelog failed: " + e.getMessage());
            if (verbose) e.printStackTrace();
            return 1;
        }
    }

    private int handleTakeSnapshot(WorkspaceManager workspace, SynthesisConfig config,
                                    SnapshotManager snapshots, Path workspaceRoot) throws Exception {
        AnsiOutput.printInfo("Scanning workspace for snapshot...");

        DirectoryScanner scanner = new DirectoryScanner(workspaceRoot, config.getScan(), verbose);
        ScanResult scanResult = scanner.scan();

        long snapshotId = snapshots.takeSnapshotFromScanResult(
                workspaceRoot.toString(),
                config.getWorkspace().getName(),
                scanResult, "manual");

        // Compare with previous snapshot if one exists
        WorkspaceSnapshot previous = null;
        List<WorkspaceSnapshot> allSnapshots = snapshots.getSnapshots(workspaceRoot.toString(), 2);
        if (allSnapshots.size() >= 2) {
            previous = allSnapshots.get(1); // second most recent = the one before this new one
        }

        AnsiOutput.printSuccess("Snapshot #" + snapshotId + " taken: " + scanResult.fileCount() + " files");

        if (previous != null) {
            List<ChangeEvent> changes = snapshots.compareSnapshots(previous.id(), snapshotId);
            if (changes.isEmpty()) {
                AnsiOutput.printInfo("No changes since previous snapshot.");
            } else {
                ChangeReportGenerator generator = new ChangeReportGenerator();
                String summary = generator.generateSummary(changes);
                AnsiOutput.printInfo("Changes since last snapshot: " + summary);
            }
        }

        return 0;
    }

    private Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Instant.now().minus(7, ChronoUnit.DAYS);
        }
        try {
            String value = since.substring(0, since.length() - 1);
            char unit = since.charAt(since.length() - 1);
            int amount = Integer.parseInt(value);
            return switch (unit) {
                case 'h' -> Instant.now().minus(amount, ChronoUnit.HOURS);
                case 'd' -> Instant.now().minus(amount, ChronoUnit.DAYS);
                case 'w' -> Instant.now().minus(amount * 7L, ChronoUnit.DAYS);
                default -> Instant.now().minus(7, ChronoUnit.DAYS);
            };
        } catch (Exception e) {
            return Instant.now().minus(7, ChronoUnit.DAYS);
        }
    }
}
