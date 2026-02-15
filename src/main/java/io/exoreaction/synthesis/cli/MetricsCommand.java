package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.metrics.MetricsDatabase;
import io.exoreaction.synthesis.metrics.MetricsEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Command to view Synthesis metrics and performance statistics.
 *
 * <p>Displays operational metrics about MCP tool usage, search performance,
 * and AI feature adoption. Helps quantify the productivity impact of
 * Synthesis + Claude Code integration.
 *
 * <p>Usage:
 * <pre>
 *   synthesis metrics                    Show metrics for last 7 days
 *   synthesis metrics --period 30        Show metrics for last 30 days
 *   synthesis metrics --format json      Export as JSON
 *   synthesis metrics --workspace ~/docs Filter by workspace
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@Command(
        name = "metrics",
        description = "View Synthesis metrics and performance statistics"
)
public class MetricsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--period"},
            description = "Number of days to query (0 = all data, default: 7)",
            defaultValue = "7"
    )
    private int periodDays;

    @Option(
            names = {"--workspace"},
            description = "Filter by workspace path"
    )
    private String workspaceFilter;

    @Option(
            names = {"--format"},
            description = "Output format: table (default), json",
            defaultValue = "table"
    )
    private String format;

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String BLUE = "\u001B[34m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String DIM = "\u001B[2m";

    @Override
    public Integer call() throws Exception {
        Path dbPath = MetricsDatabase.getDefaultPath();

        if (!Files.exists(dbPath)) {
            System.err.println("No metrics database found. MCP server must be used to collect metrics.");
            return 1;
        }

        try (MetricsDatabase db = new MetricsDatabase(dbPath)) {
            if ("json".equalsIgnoreCase(format)) {
                outputJson(db);
            } else {
                outputTable(db);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error reading metrics: " + e.getMessage());
            return 1;
        }
    }

    private void outputTable(MetricsDatabase db) throws Exception {
        List<MetricsEvent> events = db.queryEvents(periodDays);
        Map<String, MetricsDatabase.ToolStats> toolStats = db.getToolStats(periodDays);

        if (workspaceFilter != null && !workspaceFilter.isBlank()) {
            events = events.stream()
                    .filter(e -> e.mcpWorkspace() != null && e.mcpWorkspace().contains(workspaceFilter))
                    .toList();
        }

        // Header
        System.out.println(BOLD + BLUE + "========================================" + RESET);
        System.out.println(BOLD + BLUE + "  Synthesis Metrics Report" + RESET);
        System.out.println(BOLD + BLUE + "========================================" + RESET);
        System.out.println();

        String periodLabel = periodDays == 0 ? "All time" : "Last " + periodDays + " days";
        System.out.println("  " + BOLD + "Period:" + RESET + " " + periodLabel);
        System.out.println("  " + BOLD + "Total Records:" + RESET + " " + db.getRecordCount());
        System.out.println("  " + BOLD + "Database Size:" + RESET + " " + formatSize(db.getDatabaseSize()));
        if (workspaceFilter != null) {
            System.out.println("  " + BOLD + "Workspace Filter:" + RESET + " " + workspaceFilter);
        }
        System.out.println();

        if (events.isEmpty()) {
            System.out.println(DIM + "  No metrics data for the specified period." + RESET);
            return;
        }

        // MCP Tool Usage
        System.out.println(BOLD + "MCP Tool Usage:" + RESET);
        System.out.println();

        if (toolStats.isEmpty()) {
            System.out.println(DIM + "  No MCP tool invocations recorded." + RESET);
        } else {
            for (var entry : toolStats.entrySet()) {
                String tool = entry.getKey();
                MetricsDatabase.ToolStats stats = entry.getValue();

                System.out.printf("  %s%-10s%s %d invocations (avg %.2fs, p95 %.2fs, success %.1f%%)%n",
                        GREEN, tool + ":", RESET,
                        stats.invocationCount(),
                        stats.avgExecutionTimeMs() / 1000.0,
                        stats.maxExecutionTimeMs() / 1000.0,
                        stats.successRate());
            }
        }
        System.out.println();

        // Search Performance (if search events exist)
        List<MetricsEvent> searchEvents = events.stream()
                .filter(e -> "search".equals(e.mcpTool()))
                .toList();

        if (!searchEvents.isEmpty()) {
            System.out.println(BOLD + "Search Performance:" + RESET);
            System.out.println();

            double avgTime = searchEvents.stream()
                    .mapToLong(e -> e.executionTimeMs() != null ? e.executionTimeMs() : 0)
                    .average()
                    .orElse(0.0);

            long maxTime = searchEvents.stream()
                    .mapToLong(e -> e.executionTimeMs() != null ? e.executionTimeMs() : 0)
                    .max()
                    .orElse(0);

            double avgResults = searchEvents.stream()
                    .mapToInt(e -> e.resultCount() != null ? e.resultCount() : 0)
                    .average()
                    .orElse(0.0);

            System.out.printf("  Avg query time:    %.2fs%n", avgTime / 1000.0);
            System.out.printf("  Max query time:    %.2fs%n", maxTime / 1000.0);
            System.out.printf("  Avg results:       %.1f per query%n", avgResults);
            System.out.printf("  Total queries:     %d%n", searchEvents.size());
            System.out.println();
        }

        // AI Feature Usage (if AI events exist)
        List<MetricsEvent> aiEvents = events.stream()
                .filter(e -> e.aiFeature() != null)
                .toList();

        if (!aiEvents.isEmpty()) {
            System.out.println(BOLD + "AI Feature Usage:" + RESET);
            System.out.println();

            int totalApiCalls = aiEvents.size();
            int totalTokens = aiEvents.stream()
                    .mapToInt(e -> e.aiTokensUsed() != null ? e.aiTokensUsed() : 0)
                    .sum();

            double avgTokens = totalTokens > 0 ? (double) totalTokens / totalApiCalls : 0.0;

            // Estimated cost (rough approximation: $3 per 1M tokens for Claude)
            double estimatedCost = (totalTokens / 1_000_000.0) * 3.0;

            System.out.printf("  Total API calls:   %d%n", totalApiCalls);
            System.out.printf("  Avg tokens/call:   %.0f%n", avgTokens);
            System.out.printf("  Total tokens:      %,d%n", totalTokens);
            System.out.printf("  Estimated cost:    $%.2f%n", estimatedCost);
            System.out.println();
        }

        // Workspace Activity (top 5 workspaces)
        Map<String, Long> workspaceActivity = events.stream()
                .filter(e -> e.mcpWorkspace() != null)
                .collect(Collectors.groupingBy(MetricsEvent::mcpWorkspace, Collectors.counting()));

        if (!workspaceActivity.isEmpty()) {
            System.out.println(BOLD + "Top Workspaces:" + RESET);
            System.out.println();

            workspaceActivity.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> {
                        String shortPath = shortenPath(e.getKey());
                        System.out.printf("  %-50s %s%d%s invocations%n",
                                shortPath, DIM, e.getValue(), RESET);
                    });
            System.out.println();
        }
    }

    private void outputJson(MetricsDatabase db) throws Exception {
        List<MetricsEvent> events = db.queryEvents(periodDays);
        Map<String, MetricsDatabase.ToolStats> toolStats = db.getToolStats(periodDays);

        if (workspaceFilter != null && !workspaceFilter.isBlank()) {
            events = events.stream()
                    .filter(e -> e.mcpWorkspace() != null && e.mcpWorkspace().contains(workspaceFilter))
                    .toList();
        }

        System.out.println("{");
        System.out.println("  \"period_days\": " + periodDays + ",");
        System.out.println("  \"total_records\": " + db.getRecordCount() + ",");
        System.out.println("  \"database_size_bytes\": " + db.getDatabaseSize() + ",");
        System.out.println("  \"mcp_tools\": {");

        int i = 0;
        for (var entry : toolStats.entrySet()) {
            String tool = entry.getKey();
            MetricsDatabase.ToolStats stats = entry.getValue();

            System.out.print("    \"" + tool + "\": {");
            System.out.print("\"invocations\": " + stats.invocationCount() + ", ");
            System.out.print("\"avg_time_ms\": " + Math.round(stats.avgExecutionTimeMs()) + ", ");
            System.out.print("\"max_time_ms\": " + stats.maxExecutionTimeMs() + ", ");
            System.out.print("\"success_rate\": " + String.format("%.1f", stats.successRate()));
            System.out.print("}");

            i++;
            if (i < toolStats.size()) System.out.print(",");
            System.out.println();
        }

        System.out.println("  },");
        System.out.println("  \"event_count\": " + events.size());
        System.out.println("}");
    }

    private String formatSize(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String shortenPath(String path) {
        if (path == null) return "unknown";
        if (path.length() <= 50) return path;

        // Shorten long paths: /home/user/very/long/path/to/docs -> ~/.../docs
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }

        if (path.length() <= 50) return path;

        // Further shorten: take first 20 and last 25 chars
        return path.substring(0, 20) + "..." + path.substring(path.length() - 25);
    }
}
