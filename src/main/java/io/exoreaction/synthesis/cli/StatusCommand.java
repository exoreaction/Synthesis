package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.ScanState;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Shows workspace health and index status.
 *
 * <p>Usage: {@code synthesis status}
 */
@Command(
        name = "status",
        description = "Show workspace status and index health",
        mixinStandardHelpOptions = true
)
public class StatusCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

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

            System.out.printf("  %-20s %s%n", "Workspace:", AnsiOutput.bold(config.getWorkspace().getName()));
            System.out.printf("  %-20s %s%n", "Type:", config.getWorkspace().getType());
            System.out.printf("  %-20s %s%n", "Root:", workspaceRoot);
            System.out.println();

            // Index status
            Path indexPath = workspace.getIndexPath();
            if (Files.exists(indexPath) && hasIndexFiles(indexPath)) {
                try (SearchIndex index = new SearchIndex(indexPath)) {
                    int docCount = index.documentCount();
                    long indexSize = getDirectorySize(indexPath);

                    System.out.printf("  %-20s %s%n", "Index status:", AnsiOutput.success("Active"));
                    System.out.printf("  %-20s %s%n", "Documents indexed:", AnsiOutput.bold(String.valueOf(docCount)));
                    System.out.printf("  %-20s %s%n", "Index size:", FileUtils.formatSize(indexSize));
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

            // AI status
            System.out.println();
            if (config.getAi().isEnabled()) {
                System.out.printf("  %-20s %s%n", "AI features:", AnsiOutput.success("Enabled"));
                System.out.printf("  %-20s %s%n", "Model:", config.getAi().getModel());
            } else {
                System.out.printf("  %-20s %s%n", "AI features:", AnsiOutput.dim("Disabled"));
                System.out.println("  Set ai.enabled=true and ANTHROPIC_API_KEY to enable.");
            }

            System.out.println();
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Status check failed: " + e.getMessage());
            return 1;
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
}
