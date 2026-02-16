package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.staging.StagingManager;
import io.exoreaction.synthesis.staging.StagingManager.StagedFile;
import io.exoreaction.synthesis.staging.StagingManager.StagingSummary;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Manages the staging sub-workspace lifecycle: list, promote, expire, and ingest files.
 *
 * <p>Staging sub-workspaces are temporary holding areas for incoming files
 * that need to be classified, reviewed, and promoted to permanent locations.
 *
 * <p>Usage:
 * <pre>
 *   synthesis staging list                         # List staged files
 *   synthesis staging list --status pending        # Filter by status
 *   synthesis staging promote &lt;file&gt; --to &lt;sub-workspace&gt;  # Promote a file
 *   synthesis staging ingest                       # Ingest new files in staging areas
 *   synthesis staging expire                       # Process expired files
 *   synthesis staging stats                        # Show staging statistics
 * </pre>
 *
 * @since v1.4.0
 */
@Command(
        name = "staging",
        description = "Manage staging sub-workspace files (ingest, promote, expire)",
        mixinStandardHelpOptions = true,
        subcommands = {
                StagingCommand.ListSub.class,
                StagingCommand.PromoteSub.class,
                StagingCommand.IngestSub.class,
                StagingCommand.ExpireSub.class,
                StagingCommand.StatsSub.class
        }
)
public class StagingCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        // No subcommand given -- show help
        System.out.println("  Use 'synthesis staging <subcommand>' for staging operations.");
        System.out.println();
        System.out.println("  Subcommands:");
        System.out.println("    list      List staged files");
        System.out.println("    promote   Promote a file to a permanent sub-workspace");
        System.out.println("    ingest    Scan staging areas and register new files");
        System.out.println("    expire    Process expired files");
        System.out.println("    stats     Show staging statistics");
        System.out.println();
        return 0;
    }

    // -----------------------------------------------------------------------
    // Subcommand: list
    // -----------------------------------------------------------------------

    /**
     * Lists files in staging sub-workspaces.
     */
    @Command(name = "list", description = "List staged files",
            mixinStandardHelpOptions = true)
    static class ListSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--status"}, description = "Filter by status: pending, promoted, expired")
        private String statusFilter;

        @Option(names = {"-v", "--verbose"}, description = "Show detailed information",
                defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled. Add 'staging: { enabled: true }'"
                            + " to your config.yaml.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                List<StagedFile> files = staging.list(statusFilter);

                if (files.isEmpty()) {
                    System.out.println();
                    System.out.println("  No staged files"
                            + (statusFilter != null ? " with status '" + statusFilter + "'" : "")
                            + ".");
                    System.out.println();
                    return 0;
                }

                System.out.println();
                System.out.printf("  %s staged files%s:%n%n",
                        AnsiOutput.bold(String.valueOf(files.size())),
                        statusFilter != null ? " (status: " + statusFilter + ")" : "");

                for (int i = 0; i < files.size(); i++) {
                    StagedFile file = files.get(i);

                    String statusBadge = switch (file.status()) {
                        case "pending" -> AnsiOutput.yellow("[PENDING]");
                        case "promoted" -> AnsiOutput.green("[PROMOTED]");
                        case "expired" -> AnsiOutput.red("[EXPIRED]");
                        case "deleted" -> AnsiOutput.dim("[DELETED]");
                        default -> AnsiOutput.dim("[" + file.status() + "]");
                    };

                    System.out.printf("  %s %s %s%n",
                            AnsiOutput.dim(String.format("%2d.", i + 1)),
                            statusBadge,
                            AnsiOutput.bold(file.relativePath()));

                    StringBuilder meta = new StringBuilder();
                    meta.append(FileUtils.formatSize(file.fileSize()));
                    if (file.fileType() != null) {
                        meta.append(" | ").append(file.fileType());
                    }
                    meta.append(" | sub-ws: ").append(file.subWorkspace());

                    if (file.classifiedOrg() != null) {
                        meta.append(" | org: ").append(file.classifiedOrg());
                        meta.append(String.format(" (%.0f%%)", file.classificationConfidence() * 100));
                    }

                    if (file.isPending()) {
                        Duration timeLeft = Duration.between(Instant.now(), file.expiresAt());
                        if (timeLeft.isPositive()) {
                            meta.append(" | expires in ").append(formatDuration(timeLeft));
                        } else {
                            meta.append(" | ").append(AnsiOutput.red("EXPIRED"));
                        }
                    }

                    System.out.printf("     %s%n", AnsiOutput.dim(meta.toString()));

                    if (verbose) {
                        System.out.printf("     ingested: %s%n",
                                AnsiOutput.dim(formatInstant(file.ingestedAt())));
                        if (file.suggestedDestination() != null) {
                            System.out.printf("     suggested: %s%n",
                                    AnsiOutput.cyan(file.suggestedDestination()));
                        }
                        if (file.promotedTo() != null) {
                            System.out.printf("     promoted to: %s at %s%n",
                                    AnsiOutput.green(file.promotedTo()),
                                    formatInstant(file.promotedAt()));
                        }
                    }
                    System.out.println();
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Failed to list staged files: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: promote
    // -----------------------------------------------------------------------

    /**
     * Promotes a staged file to a permanent sub-workspace.
     */
    @Command(name = "promote", description = "Promote a staged file to a permanent sub-workspace",
            mixinStandardHelpOptions = true)
    static class PromoteSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Parameters(index = "0", description = "Relative path of the staged file to promote")
        private String filePath;

        @Option(names = {"--to"}, required = true,
                description = "Target sub-workspace name to promote to")
        private String targetSubWorkspace;

        @Option(names = {"--dest"},
                description = "Destination path within the target sub-workspace (default: auto)")
        private String destPath;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printError("Staging is not enabled.");
                    return 1;
                }

                // Find target sub-workspace
                SubWorkspaceConfig targetSw = null;
                for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
                    if (sw.getName().equals(targetSubWorkspace)) {
                        targetSw = sw;
                        break;
                    }
                }

                if (targetSw == null) {
                    AnsiOutput.printError("Target sub-workspace not found: " + targetSubWorkspace);
                    System.out.println("  Available sub-workspaces:");
                    for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
                        if (!sw.isStaging()) {
                            System.out.println("    - " + sw.getName() + " (" + sw.getPath() + ")");
                        }
                    }
                    return 1;
                }

                if (targetSw.isStaging()) {
                    AnsiOutput.printError("Cannot promote to another staging sub-workspace.");
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                // Find the staged file
                List<StagedFile> files = staging.list("pending");
                StagedFile targetFile = null;
                for (StagedFile f : files) {
                    if (f.relativePath().equals(filePath)) {
                        targetFile = f;
                        break;
                    }
                }

                if (targetFile == null) {
                    AnsiOutput.printError("Staged file not found (or not in 'pending' status): "
                            + filePath);
                    return 1;
                }

                // Compute destination path
                String destination = destPath;
                if (destination == null) {
                    // Auto-compute: targetSw.path + filename
                    String fileName = Path.of(targetFile.relativePath()).getFileName().toString();
                    destination = targetSw.getPath() + "/" + fileName;
                }

                boolean success = staging.promote(targetFile, targetSubWorkspace, destination);
                if (success) {
                    AnsiOutput.printSuccess("Promoted: " + filePath + " -> " + destination
                            + " [" + targetSubWorkspace + "]");
                } else {
                    AnsiOutput.printError("Promotion failed for: " + filePath);
                    return 1;
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Promotion failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: ingest
    // -----------------------------------------------------------------------

    /**
     * Scans staging areas and registers new files.
     */
    @Command(name = "ingest", description = "Scan staging areas and register new files",
            mixinStandardHelpOptions = true)
    static class IngestSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"-v", "--verbose"}, description = "Show detailed output",
                defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled.");
                    return 0;
                }

                // Find staging sub-workspaces
                List<SubWorkspaceConfig> stagingSubWorkspaces =
                        StagingManager.findStagingSubWorkspaces(config.getSubWorkspaces());

                if (stagingSubWorkspaces.isEmpty()) {
                    AnsiOutput.printWarning("No staging sub-workspaces configured. "
                            + "Add a sub-workspace with type: staging to your config.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                int totalIngested = 0;
                int totalClassified = 0;
                int totalErrors = 0;

                for (SubWorkspaceConfig swConfig : stagingSubWorkspaces) {
                    Path stagingDir = workspaceRoot.resolve(swConfig.getPath());
                    if (!Files.isDirectory(stagingDir)) {
                        if (verbose) {
                            System.out.println("  Staging directory does not exist: " + swConfig.getPath());
                        }
                        continue;
                    }

                    AnsiOutput.printInfo("Scanning staging area: " + swConfig.getName()
                            + " (" + swConfig.getPath() + ")");

                    // Get existing staged files for this sub-workspace
                    List<StagedFile> existing = staging.list(null);
                    java.util.Set<String> existingPaths = new java.util.HashSet<>();
                    for (StagedFile f : existing) {
                        if (f.subWorkspace().equals(swConfig.getName())) {
                            existingPaths.add(f.relativePath());
                        }
                    }

                    // Walk the staging directory and ingest new files
                    try (Stream<Path> files = Files.walk(stagingDir)) {
                        List<Path> newFiles = files
                                .filter(Files::isRegularFile)
                                .filter(p -> {
                                    String rel = workspaceRoot.relativize(p).toString();
                                    return !existingPaths.contains(rel);
                                })
                                .toList();

                        for (Path file : newFiles) {
                            try {
                                String relativePath = workspaceRoot.relativize(file).toString();
                                long size = Files.size(file);
                                String ext = getExtension(file.getFileName().toString());
                                String fileType = guessFileType(ext);

                                StagedFile ingested = staging.ingest(
                                        relativePath, swConfig.getName(),
                                        size, fileType, null);

                                totalIngested++;
                                if (verbose) {
                                    System.out.println("    + " + relativePath
                                            + " (" + FileUtils.formatSize(size) + ")");
                                }
                            } catch (Exception e) {
                                totalErrors++;
                                if (verbose) {
                                    System.err.println("    Error: " + file + ": " + e.getMessage());
                                }
                            }
                        }
                    }
                }

                System.out.println();
                AnsiOutput.printSuccess("Ingestion complete: " + totalIngested + " new files"
                        + (totalErrors > 0 ? ", " + totalErrors + " errors" : ""));

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Ingestion failed: " + e.getMessage());
                return 1;
            }
        }

        private String getExtension(String filename) {
            int dot = filename.lastIndexOf('.');
            return dot >= 0 ? filename.substring(dot) : "";
        }

        private String guessFileType(String ext) {
            return switch (ext.toLowerCase()) {
                case ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".cs",
                     ".rb", ".php", ".swift", ".kt", ".scala", ".sh" -> "CODE";
                case ".md", ".markdown" -> "MARKDOWN";
                case ".yaml", ".yml" -> "YAML";
                case ".json" -> "JSON";
                case ".xml", ".properties", ".cfg", ".conf", ".ini", ".toml" -> "CONFIG";
                case ".pdf" -> "PDF";
                case ".png", ".jpg", ".jpeg", ".gif", ".svg", ".bmp", ".webp" -> "IMAGE";
                case ".mp4", ".mov", ".avi", ".mkv", ".webm" -> "VIDEO";
                case ".mp3", ".wav", ".ogg", ".flac", ".aac" -> "AUDIO";
                case ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx" -> "DOCUMENT";
                default -> "OTHER";
            };
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: expire
    // -----------------------------------------------------------------------

    /**
     * Processes expired files in staging areas.
     */
    @Command(name = "expire", description = "Process expired staging files",
            mixinStandardHelpOptions = true)
    static class ExpireSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--dry-run"}, description = "Show what would be expired without acting",
                defaultValue = "false")
        private boolean dryRun;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                List<StagedFile> expired = staging.findExpired();

                if (expired.isEmpty()) {
                    System.out.println("  No expired files.");
                    return 0;
                }

                System.out.println();
                System.out.printf("  %s expired file(s):%n%n",
                        AnsiOutput.bold(String.valueOf(expired.size())));

                for (StagedFile file : expired) {
                    Duration age = Duration.between(file.ingestedAt(), Instant.now());
                    System.out.printf("  %s %s  %s  ingested %s ago%n",
                            AnsiOutput.red("[EXPIRED]"),
                            file.relativePath(),
                            AnsiOutput.dim(FileUtils.formatSize(file.fileSize())),
                            formatDuration(age));
                }

                if (dryRun) {
                    System.out.println();
                    AnsiOutput.printInfo("Dry run -- no changes made."
                            + (config.getStaging().isCleanupExpired()
                            ? " Would delete " + expired.size() + " file(s)." : ""));
                } else {
                    int processed = staging.processExpired();
                    System.out.println();
                    AnsiOutput.printSuccess("Processed " + processed + " expired file(s)"
                            + (config.getStaging().isCleanupExpired() ? " (deleted)" : " (marked)"));
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Expire processing failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: stats
    // -----------------------------------------------------------------------

    /**
     * Shows staging statistics.
     */
    @Command(name = "stats", description = "Show staging statistics",
            mixinStandardHelpOptions = true)
    static class StatsSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                StagingSummary stats = staging.getStats();

                System.out.println();
                AnsiOutput.printHeader("Staging Statistics");
                System.out.println();
                System.out.println("  Configuration:");
                System.out.println("    Retention:       " + config.getStaging().getRetentionDays() + " days");
                System.out.println("    Auto-classify:   " + (config.getStaging().isAutoClassify() ? "yes" : "no"));
                System.out.println("    Cleanup expired: " + (config.getStaging().isCleanupExpired() ? "yes" : "no"));
                System.out.println("    Threshold:       " + String.format("%.0f%%",
                        config.getStaging().getClassificationThreshold() * 100));
                System.out.println();
                System.out.println("  Status:");
                System.out.println("    Pending:   " + AnsiOutput.yellow(String.valueOf(stats.ingested())));
                System.out.println("    Promoted:  " + AnsiOutput.green(String.valueOf(stats.promoted())));
                System.out.println("    Expired:   " + AnsiOutput.red(String.valueOf(stats.expired())));
                System.out.println();

                // Show staging sub-workspaces
                List<SubWorkspaceConfig> stagingSws =
                        StagingManager.findStagingSubWorkspaces(config.getSubWorkspaces());
                if (!stagingSws.isEmpty()) {
                    System.out.println("  Staging areas:");
                    for (SubWorkspaceConfig sw : stagingSws) {
                        Path stagingDir = workspaceRoot.resolve(sw.getPath());
                        String dirStatus = Files.isDirectory(stagingDir)
                                ? AnsiOutput.green("exists") : AnsiOutput.red("missing");
                        System.out.printf("    %s (%s) - %s%n",
                                AnsiOutput.bold(sw.getName()), sw.getPath(), dirStatus);
                    }
                    System.out.println();
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Failed to get staging stats: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    static String formatInstant(Instant instant) {
        if (instant == null) return "never";
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    static String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        long minutes = duration.toMinutesPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
