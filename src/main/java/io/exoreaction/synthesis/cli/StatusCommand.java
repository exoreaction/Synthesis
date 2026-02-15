package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.RepositoryManager;
import io.exoreaction.synthesis.core.ScanState;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.metrics.MetricsDatabase;
import io.exoreaction.synthesis.telemetry.ApprovalService;
import io.exoreaction.synthesis.telemetry.ClientUUID;
import io.exoreaction.synthesis.telemetry.TelemetryConfig;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FfprobeDetector;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.workspace.WorkspaceMetadata;
import io.exoreaction.synthesis.workspace.WorkspaceType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shows workspace health and index status.
 *
 * <p>Usage:
 * <pre>
 *   synthesis status
 *   synthesis status --per-repo   # Show stats per repository
 * </pre>
 */
@Command(
        name = "status",
        description = "Show workspace status and index health",
        mixinStandardHelpOptions = true
)
public class StatusCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--per-repo"},
            description = "Show statistics per repository (multi-repo workspaces)",
            defaultValue = "false"
    )
    private boolean perRepo;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Workspace Status");

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load config
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            // Workspace info with type badge
            WorkspaceType wsType = config.getWorkspace().getWorkspaceType();
            String typeBadge = switch (wsType) {
                case SOURCE_CODE -> AnsiOutput.blue("[source]");
                case DOCUMENTS -> AnsiOutput.green("[docs]  ");
                case MIXED -> AnsiOutput.yellow("[mixed] ");
            };

            System.out.println("  " + typeBadge + " " + AnsiOutput.bold(config.getWorkspace().getName()));
            System.out.printf("  %-20s %s%n", "Root:", workspaceRoot);

            // Workspace metadata
            WorkspaceMetadata metadata = config.getWorkspace().getMetadata();
            if (metadata != null) {
                if (metadata.getCompany() != null) {
                    System.out.printf("  %-20s %s%n", "Company:", metadata.getCompany());
                }
                if (metadata.getPrimaryLanguage() != null) {
                    System.out.printf("  %-20s %s%n", "Language:", AnsiOutput.cyan(metadata.getPrimaryLanguage()));
                }
                if (metadata.getRepoCount() > 0) {
                    System.out.printf("  %-20s %d repositories%n", "Scope:", metadata.getRepoCount());
                }
            }
            System.out.println();

            // Multi-repo status
            RepositoryManager repoManager = new RepositoryManager(workspaceRoot);
            repoManager.load();
            if (repoManager.hasRepos()) {
                System.out.printf("  %-20s %s%n", "Repositories:", AnsiOutput.bold(
                        String.valueOf(repoManager.getRepositories().size())));
                for (RepositoryManager.RepoEntry entry : repoManager.getRepositories()) {
                    String scanInfo = entry.lastScanTime() != null ?
                            " (scanned: " + LocalDateTime.ofInstant(entry.lastScanTime(), ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ")" :
                            " (not scanned)";
                    System.out.println("    " + AnsiOutput.bold(entry.name()) + " -> " + entry.path() +
                            AnsiOutput.dim(scanInfo));
                }
                System.out.println();
            }

            // Index status
            Path indexPath = workspace.getIndexPath();
            if (Files.exists(indexPath) && hasIndexFiles(indexPath)) {
                try (SearchIndex index = new SearchIndex(indexPath)) {
                    int docCount = index.documentCount();
                    long indexSize = getDirectorySize(indexPath);

                    System.out.printf("  %-20s %s%n", "Index status:", AnsiOutput.success("Active"));
                    System.out.printf("  %-20s %s%n", "Documents indexed:", AnsiOutput.bold(String.valueOf(docCount)));
                    System.out.printf("  %-20s %s%n", "Index size:", FileUtils.formatSize(indexSize));

                    // Per-repo breakdown
                    if (perRepo && repoManager.hasRepos()) {
                        System.out.println();
                        System.out.println("  " + AnsiOutput.bold("Per-repository breakdown:"));
                        for (RepositoryManager.RepoEntry entry : repoManager.getRepositories()) {
                            List<SearchResult> repoFiles = index.listAll(null, entry.name(), 50000);
                            long repoSize = repoFiles.stream().mapToLong(SearchResult::sizeBytes).sum();
                            Map<String, Long> byType = repoFiles.stream()
                                    .filter(r -> r.fileType() != null)
                                    .collect(Collectors.groupingBy(SearchResult::fileType, Collectors.counting()));
                            System.out.printf("    %-20s %d files (%s)%n",
                                    AnsiOutput.bold(entry.name() + ":"),
                                    repoFiles.size(),
                                    FileUtils.formatSize(repoSize));
                            byType.entrySet().stream()
                                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                                    .forEach(e -> System.out.printf("      %-15s %d files%n",
                                            e.getKey(), e.getValue()));
                        }
                    }
                }
            } else {
                System.out.printf("  %-20s %s%n", "Index status:", AnsiOutput.warning("Not built"));
                System.out.println();
                System.out.println("  Run " + AnsiOutput.cyan("synthesis scan") + " to build the index.");
            }

            // Scan state
            Path scanStatePath = workspace.getScanStatePath();
            if (ScanState.exists(scanStatePath)) {
                System.out.println();
                ScanState scanState = ScanState.load(scanStatePath);
                String lastScan = LocalDateTime.ofInstant(scanState.getLastScanTime(), ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                Duration elapsed = Duration.between(scanState.getLastScanTime(), Instant.now());
                System.out.printf("  %-20s %s%n", "Last scan:", lastScan + " (" + formatDuration(elapsed) + " ago)");
                System.out.printf("  %-20s %s%n", "Files tracked:", AnsiOutput.bold(String.valueOf(scanState.getFileCount())));
            }

            // Media type breakdown (if available)
            Path indexPath2 = workspace.getIndexPath();
            if (Files.exists(indexPath2) && hasIndexFiles(indexPath2)) {
                try (SearchIndex index = new SearchIndex(indexPath2)) {
                    showMediaStats(index);
                } catch (Exception ignored) {
                    // Media stats are informational -- don't fail status
                }
            }

            // Watch daemon status
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Real-time Monitoring:"));
            boolean watchDaemonRunning = isWatchDaemonRunning(config.getWorkspace().getName());
            System.out.printf("    %-15s %s%n", "Watch daemon:",
                    watchDaemonRunning
                        ? AnsiOutput.success("✓ Active")
                        : AnsiOutput.dim("✗ Not running"));
            if (!watchDaemonRunning) {
                System.out.println("      " + AnsiOutput.dim("Run: systemctl --user start synthesis-watch-<workspace>.service"));
            }

            // Recent metrics summary (last 24h)
            try {
                Path metricsDbPath = MetricsDatabase.getDefaultPath();
                if (Files.exists(metricsDbPath)) {
                    try (MetricsDatabase db = new MetricsDatabase(metricsDbPath)) {
                        showMetricsSummary(db, workspaceRoot);
                    }
                }
            } catch (Exception e) {
                // Metrics are optional, don't fail status
            }

            // External Tools
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("External Tools:"));
            if (FfprobeDetector.isAvailable()) {
                String statusDisplay = FfprobeDetector.getStatusDisplay();
                System.out.printf("    %-15s %s%n", "ffprobe:",
                        AnsiOutput.success(statusDisplay));
            } else {
                System.out.printf("    %-15s %s%n", "ffprobe:",
                        AnsiOutput.dim("Not installed (optional, " + FfprobeDetector.getInstallHint() + ")"));
            }

            // AI status
            System.out.println();
            if (config.getAi().isEnabled()) {
                System.out.printf("  %-20s %s%n", "AI features:", AnsiOutput.success("Enabled"));
                System.out.printf("  %-20s %s%n", "Model:", config.getAi().getModel());
                // Vision status
                if (config.getAi().getVision().isEnabled()) {
                    System.out.printf("  %-20s %s%n", "Vision analysis:",
                            AnsiOutput.success("Enabled (default)"));
                    System.out.println("    Use --no-vision to disable during scan.");
                } else {
                    System.out.printf("  %-20s %s%n", "Vision analysis:",
                            AnsiOutput.dim("Disabled"));
                }
                System.out.printf("  %-20s %s%n", "Directed synthesis:",
                        AnsiOutput.success("Available"));
                System.out.println("    Use " + AnsiOutput.cyan("synthesis perspectives <question>")
                        + " for multi-perspective analysis.");
            } else {
                System.out.printf("  %-20s %s%n", "AI features:", AnsiOutput.dim("Disabled"));
                System.out.println("  Set ai.enabled=true and ANTHROPIC_API_KEY to enable.");
            }

            // Pilot status
            System.out.println();
            String uuid = ClientUUID.read();
            System.out.printf("  %-20s %s%n", "Telemetry:",
                    AnsiOutput.success("Active (mandatory)"));
            if (uuid != null) {
                System.out.printf("  %-20s %s%n", "Client UUID:", AnsiOutput.dim(uuid));
            }

            // Approval status
            ApprovalService approvalService = ApprovalService.create();
            Boolean approvalStatus = approvalService.getCachedApproval();
            if (approvalStatus != null) {
                System.out.printf("  %-20s %s%n", "Pilot Status:",
                        approvalStatus
                                ? AnsiOutput.success("Approved")
                                : AnsiOutput.warning("Pending Approval (UUID: " + uuid + ")"));
            } else {
                System.out.printf("  %-20s %s%n", "Pilot Status:",
                        AnsiOutput.dim("Not checked yet"));
            }

            System.out.println();
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Status check failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Shows media file statistics from the index.
     */
    private void showMediaStats(SearchIndex index) throws IOException {
        // Count media files by type
        long imageCount = index.listAll("IMAGE", 50000).size();
        long videoCount = index.listAll("VIDEO", 50000).size();
        long audioCount = index.listAll("AUDIO", 50000).size();
        long pdfCount = index.listAll("PDF", 50000).size();

        long mediaTotal = imageCount + videoCount + audioCount;

        if (mediaTotal > 0 || pdfCount > 0) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Media & Documents:"));
            if (imageCount > 0) {
                System.out.printf("    %-15s %d files%n", "Images:", imageCount);
            }
            if (videoCount > 0) {
                System.out.printf("    %-15s %d files%n", "Videos:", videoCount);
            }
            if (audioCount > 0) {
                System.out.printf("    %-15s %d files%n", "Audio:", audioCount);
            }
            if (pdfCount > 0) {
                // Count presentations vs documents
                List<SearchResult> pdfs = index.listAll("PDF", 50000);
                long presentations = pdfs.stream()
                        .filter(r -> r.summary().contains("presentation"))
                        .count();
                long documents = pdfCount - presentations;

                System.out.printf("    %-15s %d files", "PDFs:", pdfCount);
                if (presentations > 0) {
                    System.out.printf(" (%d presentations, %d documents)", presentations, documents);
                }
                System.out.println();
            }
        }
    }

    private boolean hasIndexFiles(Path indexPath) {
        try (Stream<Path> stream = Files.list(indexPath)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = duration.toMinutes();
        if (minutes < 60) return minutes + " min";
        long hours = duration.toHours();
        if (hours < 24) return hours + "h " + (minutes % 60) + "m";
        long days = hours / 24;
        return days + " day" + (days > 1 ? "s" : "") + " ago";
    }

    private long getDirectorySize(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try { return Files.size(path); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Checks if watch daemon is running for this workspace.
     */
    private boolean isWatchDaemonRunning(String workspaceName) {
        try {
            // Normalize workspace name for service name
            String normalized = workspaceName
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]", "")
                    .replaceAll("\"", "");

            // Try common service name patterns
            String[] servicePatterns = {
                "synthesis-watch-" + normalized + ".service",
                "synthesis-watch-" + workspaceName.toLowerCase() + ".service"
            };

            for (String serviceName : servicePatterns) {
                Process process = new ProcessBuilder("systemctl", "--user", "is-active", serviceName)
                        .redirectErrorStream(true)
                        .start();

                process.waitFor();
                if (process.exitValue() == 0) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shows metrics summary for last 24 hours.
     */
    private void showMetricsSummary(MetricsDatabase db, Path workspaceRoot) {
        try {
            // Query last 24h metrics for this workspace
            long since = Instant.now().minusSeconds(24 * 60 * 60).getEpochSecond();
            String workspacePath = workspaceRoot.toAbsolutePath().normalize().toString();

            // Get MCP tool stats
            var stats = db.getToolStats(workspacePath, since);

            if (!stats.isEmpty()) {
                System.out.println();
                System.out.println("  " + AnsiOutput.bold("MCP Activity (Last 24h):"));

                long totalCalls = 0;
                double totalTime = 0;

                for (var entry : stats.entrySet()) {
                    String tool = entry.getKey();
                    var toolStats = entry.getValue();
                    int calls = toolStats.invocationCount();
                    double avgTime = toolStats.avgExecutionTimeMs();

                    totalCalls += calls;
                    totalTime += avgTime * calls;

                    System.out.printf("    %-15s %d calls (avg %.2fs)%n",
                            tool + ":", calls, avgTime / 1000.0);
                }

                if (totalCalls > 0) {
                    System.out.printf("    %-15s %d calls (avg %.2fs)%n",
                            AnsiOutput.bold("Total:"), totalCalls, (totalTime / totalCalls) / 1000.0);
                }
            }
        } catch (Exception e) {
            // Metrics are optional, silently skip
        }
    }
}
