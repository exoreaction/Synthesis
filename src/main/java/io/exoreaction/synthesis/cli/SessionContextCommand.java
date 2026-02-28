package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.changelog.ChangeEvent;
import io.exoreaction.synthesis.changelog.SnapshotManager;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.ScanState;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.SecurityPosture;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates a compact codebase context summary for injection into Claude Code sessions.
 *
 * <p>Complements the KCP export by providing a dynamic freshness snapshot rather than
 * a static manifest. Designed to be fast (under 2 seconds) and requires no AI.
 *
 * <p>Usage:
 * <pre>
 *   synthesis session-context                        # Multi-line summary
 *   synthesis session-context --compact              # Single-line output for hook injection
 *   synthesis session-context --since 7d             # Look back 7 days for changes
 *   synthesis session-context --no-security          # Skip security posture line
 * </pre>
 */
@Command(
        name = "session-context",
        description = "Generate codebase context summary for Claude Code sessions",
        mixinStandardHelpOptions = true
)
public class SessionContextCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--since"}, description = "How far back to look for changes (default: 24h; supports 1h, 48h, 7d)")
    private String since;

    @Option(names = {"-c", "--compact"}, description = "Single-line output for hook injection", defaultValue = "false")
    private boolean compact;

    @Option(names = {"--no-security"}, description = "Skip security posture line", defaultValue = "false")
    private boolean noSecurity;

    // Package-private setters for testing
    void setParent(SynthesisApp parent) { this.parent = parent; }
    void setCompact(boolean compact) { this.compact = compact; }
    void setSince(String since) { this.since = since; }
    void setNoSecurity(boolean noSecurity) { this.noSecurity = noSecurity; }

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                System.err.println("Error: " + validation.get());
                return 1;
            }

            // Gather stats
            int fileCount = 0;
            long indexSize = 0;
            Path indexPath = workspace.getIndexPath();
            if (Files.exists(indexPath) && hasIndexFiles(indexPath)) {
                try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                    fileCount = index.documentCount();
                }
                indexSize = getDirectorySize(indexPath);
            }

            // Last scan time
            String lastScanStr = null;
            String lastScanAgo = null;
            Path scanStatePath = workspace.getScanStatePath();
            if (ScanState.exists(scanStatePath)) {
                ScanState scanState = ScanState.load(scanStatePath);
                Instant scanTime = scanState.getLastScanTime();
                lastScanStr = LocalDateTime.ofInstant(scanTime, ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                Duration elapsed = Duration.between(scanTime, Instant.now());
                lastScanAgo = formatDuration(elapsed);
            }

            // Changes since duration
            Instant sinceInstant = parseSince(since != null ? since : "24h");
            String sinceDuration = since != null ? since : "24h";
            List<ChangeEvent> changes = List.of();
            try {
                SynthesisDatabase db = SynthesisDatabase.getDefault();
                SnapshotManager snapshots = new SnapshotManager(db);
                changes = snapshots.getChangesForWorkspace(
                        workspaceRoot.toString(), sinceInstant);
            } catch (Exception e) {
                // No snapshot data available -- not critical
            }

            // Security posture
            SecurityPosture security = SecurityPosture.empty();
            if (!noSecurity) {
                try {
                    SynthesisDatabase db = SynthesisDatabase.getDefault();
                    security = SecurityPosture.query(
                            db.getConnection(), workspaceRoot.toString());
                } catch (Exception e) {
                    // No security data -- skip
                }
            }

            // Hot packages (most active directories)
            Map<String, Integer> packageChanges = new LinkedHashMap<>();
            for (ChangeEvent e : changes) {
                String path = e.relativePath();
                String pkg = extractPackage(path);
                packageChanges.merge(pkg, 1, Integer::sum);
            }
            List<Map.Entry<String, Integer>> hotPackages = packageChanges.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .toList();

            // Output
            if (compact) {
                printCompact(workspaceRoot, fileCount, indexSize, changes.size(),
                        sinceDuration, security, hotPackages);
            } else {
                printDefault(workspaceRoot, fileCount, indexSize, lastScanStr,
                        lastScanAgo, changes, sinceDuration, security, hotPackages);
            }

            return 0;

        } catch (Exception e) {
            System.err.println("session-context failed: " + e.getMessage());
            return 1;
        }
    }

    private void printCompact(Path workspaceRoot, int fileCount, long indexSize,
                               int changeCount, String sinceDuration,
                               SecurityPosture security,
                               List<Map.Entry<String, Integer>> hotPackages) {
        StringBuilder sb = new StringBuilder();

        // Workspace section
        sb.append("workspace:").append(fileCount).append("files");
        sb.append("\u00B7").append(FileUtils.formatSize(indexSize).replace(" ", ""));

        // Changes section
        sb.append(" | changed:").append(changeCount).append("files(").append(sinceDuration).append(")");

        // Security section
        if (!noSecurity && !security.noData() && security.totalCount() > 0) {
            sb.append(" | security:");
            sb.append(security.highCount()).append("HIGH");
            sb.append("\u00B7").append(security.mediumCount()).append("MEDIUM");
        }

        // Active packages
        if (!hotPackages.isEmpty()) {
            sb.append(" | active:");
            sb.append(hotPackages.stream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(",")));
        }

        System.out.print(sb);
    }

    private void printDefault(Path workspaceRoot, int fileCount, long indexSize,
                               String lastScanStr, String lastScanAgo,
                               List<ChangeEvent> changes, String sinceDuration,
                               SecurityPosture security,
                               List<Map.Entry<String, Integer>> hotPackages) {
        System.out.println("=== Synthesis Session Context ===");
        System.out.println("Workspace: " + workspaceRoot + " (" +
                fileCount + " files, " + FileUtils.formatSize(indexSize) + " index)");

        if (lastScanStr != null) {
            System.out.println("Last scan: " + lastScanStr + " (" + lastScanAgo + " ago)");
        }

        System.out.println();
        System.out.println("Changed since " + sinceDuration + ": " + changes.size() + " files");

        // Show up to 5 changes
        int shown = 0;
        for (ChangeEvent e : changes) {
            if (shown >= 5) break;
            String label = switch (e.changeType()) {
                case ADDED -> "Added";
                case MODIFIED -> "Modified";
                case DELETED -> "Deleted";
                case MOVED -> "Moved";
            };
            System.out.println("  " + label + ": " + e.relativePath());
            shown++;
        }
        if (changes.size() > 5) {
            System.out.println("  (and " + (changes.size() - 5) + " more)");
        }

        // Security
        if (!noSecurity && !security.noData() && security.totalCount() > 0) {
            System.out.println();
            System.out.println("Security posture: " + security.highCount() + " HIGH \u00B7 " +
                    security.mediumCount() + " MEDIUM \u00B7 " + security.lowCount() + " LOW (" +
                    security.fileCount() + " files)");
        }

        // Hot packages
        if (!hotPackages.isEmpty()) {
            System.out.println();
            System.out.print("Hot packages (most active): ");
            System.out.println(hotPackages.stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + " changes)")
                    .collect(Collectors.joining(", ")));
        }
    }

    /**
     * Extracts the top-level package/directory from a relative file path.
     */
    static String extractPackage(String path) {
        if (path == null || path.isEmpty()) return "(root)";
        // Find the last directory component before the file name
        int lastSep = path.lastIndexOf('/');
        if (lastSep < 0) lastSep = path.lastIndexOf('\\');
        if (lastSep < 0) return "(root)";
        String dir = path.substring(0, lastSep);
        // Take just the last directory name for readability
        int prevSep = dir.lastIndexOf('/');
        if (prevSep < 0) prevSep = dir.lastIndexOf('\\');
        return prevSep >= 0 ? dir.substring(prevSep + 1) : dir;
    }

    private Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Instant.now().minus(24, ChronoUnit.HOURS);
        }
        try {
            String value = since.substring(0, since.length() - 1);
            char unit = since.charAt(since.length() - 1);
            int amount = Integer.parseInt(value);
            return switch (unit) {
                case 'h' -> Instant.now().minus(amount, ChronoUnit.HOURS);
                case 'd' -> Instant.now().minus(amount, ChronoUnit.DAYS);
                case 'w' -> Instant.now().minus(amount * 7L, ChronoUnit.DAYS);
                default -> Instant.now().minus(24, ChronoUnit.HOURS);
            };
        } catch (Exception e) {
            return Instant.now().minus(24, ChronoUnit.HOURS);
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
        return days + " day" + (days > 1 ? "s" : "");
    }

    private boolean hasIndexFiles(Path indexPath) {
        try (Stream<Path> stream = Files.list(indexPath)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
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
