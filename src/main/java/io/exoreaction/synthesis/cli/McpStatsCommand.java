package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * CLI command: {@code synthesis mcp-stats}
 *
 * <p>Reads the MCP query log ({@code ~/.synthesis/logs/mcp-queries.jsonl})
 * and prints a human-readable summary covering:
 * <ul>
 *   <li>Total query count and zero-result rate</li>
 *   <li>Average and P95 latency</li>
 *   <li>Top queries by frequency</li>
 *   <li>Most recent zero-result queries (actionable gaps)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   synthesis mcp-stats                  # last 7 days
 *   synthesis mcp-stats --days 30        # last 30 days
 *   synthesis mcp-stats --workspace /p   # filter by workspace
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@Command(
        name = "mcp-stats",
        description = "Show MCP query statistics from the query log"
)
public class McpStatsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--days"},
            description = "Number of days to include (default: 7, 0 = all)",
            defaultValue = "7"
    )
    private int days;

    @Option(
            names = {"--workspace"},
            description = "Filter by workspace path substring"
    )
    private String workspaceFilter;

    @Option(
            names = {"--log"},
            description = "Path to query log file (default: ~/.synthesis/logs/mcp-queries.jsonl)"
    )
    private Path logFile;

    // ANSI
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String DIM    = "\u001B[2m";

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    @Override
    public Integer call() throws Exception {
        Path log = resolveLog();

        if (!Files.exists(log)) {
            System.err.println("No MCP query log found at: " + log);
            System.err.println("Start synthesis-mcp-server to begin collecting data.");
            return 1;
        }

        List<LogEntry> entries = readEntries(log);

        if (entries.isEmpty()) {
            System.out.println("No entries in log file.");
            return 0;
        }

        // Apply time filter
        Instant cutoff = days > 0 ? Instant.now().minusSeconds((long) days * 86400) : Instant.EPOCH;
        entries = entries.stream()
                .filter(e -> e.ts.isAfter(cutoff))
                .collect(Collectors.toList());

        // Apply workspace filter
        if (workspaceFilter != null && !workspaceFilter.isBlank()) {
            entries = entries.stream()
                    .filter(e -> e.workspace.contains(workspaceFilter))
                    .collect(Collectors.toList());
        }

        if (entries.isEmpty()) {
            System.out.println("No entries in the selected time window.");
            return 0;
        }

        printStats(entries);
        return 0;
    }

    private void printStats(List<LogEntry> entries) {
        int total = entries.size();
        long zeroResults = entries.stream().filter(e -> e.zeroResult).count();
        double zeroRate = total > 0 ? (100.0 * zeroResults / total) : 0;

        // Latency stats
        long[] latencies = entries.stream().mapToLong(e -> e.latencyMs).sorted().toArray();
        long avgLatency = Math.round(Arrays.stream(latencies).average().orElse(0));
        long p95Latency = latencies.length > 0
                ? latencies[(int) Math.min(latencies.length - 1, Math.ceil(latencies.length * 0.95) - 1)]
                : 0;

        // Header
        String period = days > 0 ? "last " + days + " days" : "all time";
        System.out.println();
        System.out.println(BOLD + "MCP Query Log — " + period + RESET);
        System.out.println("=".repeat(40));

        // Totals
        System.out.printf("%-20s %d%n", "Total queries:", total);
        System.out.printf("%-20s %.1f%% (%d queries)%n", "Zero-result rate:", zeroRate, zeroResults);
        System.out.printf("%-20s %dms%n", "Avg latency:", avgLatency);
        System.out.printf("%-20s %dms%n", "P95 latency:", p95Latency);

        // Top queries
        Map<String, Long> queryCounts = entries.stream()
                .collect(Collectors.groupingBy(e -> e.query.toLowerCase().trim(), Collectors.counting()));
        Map<String, Long> queryZeroResults = entries.stream()
                .filter(e -> e.zeroResult)
                .collect(Collectors.groupingBy(e -> e.query.toLowerCase().trim(), Collectors.counting()));

        List<Map.Entry<String, Long>> topQueries = queryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toList());

        if (!topQueries.isEmpty()) {
            System.out.println();
            System.out.println(BOLD + "Top queries:" + RESET);
            int rank = 1;
            for (Map.Entry<String, Long> e : topQueries) {
                long zeros = queryZeroResults.getOrDefault(e.getKey(), 0L);
                String zeroNote = zeros > 0 ? RED + " (" + zeros + " zero-result)" + RESET : "";
                System.out.printf("  #%-3d %-40s %d hits%s%n",
                        rank++,
                        truncate(e.getKey(), 40),
                        e.getValue(),
                        zeroNote);
            }
        }

        // Recent zero-result queries
        if (zeroResults > 0) {
            List<LogEntry> recentZero = entries.stream()
                    .filter(e -> e.zeroResult)
                    .sorted(Comparator.comparing((LogEntry e) -> e.ts).reversed())
                    .limit(10)
                    .collect(Collectors.toList());

            System.out.println();
            System.out.println(BOLD + "Zero-result queries (most recent):" + RESET);
            for (LogEntry e : recentZero) {
                System.out.printf("  %-40s %s%n",
                        truncate(e.query, 40),
                        DIM + TS_FMT.format(e.ts) + RESET);
            }
        }
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Log file parsing
    // -----------------------------------------------------------------------

    private List<LogEntry> readEntries(Path log) throws IOException {
        List<LogEntry> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(log)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                LogEntry entry = parseEntry(line);
                if (entry != null) result.add(entry);
            }
        }
        return result;
    }

    /**
     * Minimal JSON parser for the fixed log entry format.
     * Uses string scanning instead of a JSON library to keep dependencies zero.
     */
    private LogEntry parseEntry(String line) {
        try {
            String ts        = extractString(line, "\"ts\":");
            String query     = extractString(line, "\"query\":");
            String workspace = extractString(line, "\"workspace\":");
            int resultCount  = extractInt(line, "\"resultCount\":");
            long latencyMs   = extractLong(line, "\"latencyMs\":");
            boolean zero     = line.contains("\"zeroResult\":true");

            if (ts == null || query == null) return null;
            return new LogEntry(Instant.parse(ts), query, workspace != null ? workspace : "",
                    resultCount, latencyMs, zero);
        } catch (Exception e) {
            return null;  // skip malformed lines
        }
    }

    private String extractString(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int start = json.indexOf('"', keyIdx + key.length());
        if (start < 0) return null;
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"') break;
            end++;
        }
        return json.substring(start + 1, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    private int extractInt(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return 0;
        int start = keyIdx + key.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    private long extractLong(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return 0;
        int start = keyIdx + key.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private Path resolveLog() {
        if (logFile != null) return logFile;
        return Path.of(System.getProperty("user.home"), ".synthesis", "logs", "mcp-queries.jsonl");
    }

    private String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // -----------------------------------------------------------------------
    // Log entry record
    // -----------------------------------------------------------------------

    private record LogEntry(Instant ts, String query, String workspace,
                             int resultCount, long latencyMs, boolean zeroResult) {}
}
