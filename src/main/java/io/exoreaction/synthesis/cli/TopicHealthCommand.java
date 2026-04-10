package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.sessions.SessionStore;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis topic-health} — analyze memory topic file hotness and staleness.
 *
 * <p>Scans all {@code *.md} files in the memory directory, extracts keywords
 * from filenames, queries the session store for FTS hits, and outputs a
 * HOT/WARM/COLD table. Appends a health snapshot to
 * {@code ~/.synthesis/topic-triage-log.jsonl}.
 *
 * <p>Usage:
 * <pre>
 *   synthesis topic-health
 *   synthesis topic-health --memory-dir /path/to/memory
 * </pre>
 */
@Command(
        name = "topic-health",
        description = "Analyze memory topic file hotness and staleness",
        mixinStandardHelpOptions = true
)
public class TopicHealthCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--memory-dir"},
            description = "Memory directory to scan (default: ~/.claude/projects/-home-totto-Documents/memory/)")
    private Path memoryDir;

    static final Set<String> STOP_WORDS = Set.of(
            "to", "and", "the", "of", "in", "for", "a", "an", "with", "from",
            "by", "at", "on", "is", "be", "as", "this", "that", "it", "my",
            "md", "notes", "log", "file", "index", "status"
    );

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        Path resolvedDir = resolvedMemoryDir();
        if (!Files.isDirectory(resolvedDir)) {
            AnsiOutput.printError("Memory directory not found: " + resolvedDir);
            return 1;
        }

        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            SessionStore store = new SessionStore(db);

            List<TopicFileInfo> files = scanFiles(resolvedDir, store);
            if (files.isEmpty()) {
                AnsiOutput.printInfo("No .md files found in " + resolvedDir);
                return 0;
            }

            int totalLines = files.stream().mapToInt(f -> f.lineCount).sum();
            printTable(files, totalLines, resolvedDir);
            appendHealthSnapshot(files, totalLines);
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("topic-health failed: " + e.getMessage());
            return 1;
        }
    }

    // -------------------------------------------------------------------------
    // Scanning and scoring
    // -------------------------------------------------------------------------

    private List<TopicFileInfo> scanFiles(Path dir, SessionStore store) throws Exception {
        List<Path> mdFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                  .sorted()
                  .forEach(mdFiles::add);
        }

        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        List<TopicFileInfo> infos = new ArrayList<>();

        for (Path file : mdFiles) {
            String name = file.getFileName().toString();
            int lineCount = countLines(file);
            long ageDays = daysSinceModified(file);
            List<String> keywords = extractKeywords(name);

            Set<String> sessionIds = new HashSet<>();
            for (String kw : keywords) {
                try {
                    List<ClaudeSession> results = store.search(kw, 200);
                    results.stream()
                           .filter(s -> s.startedAt() != null && s.startedAt().isAfter(thirtyDaysAgo))
                           .map(ClaudeSession::sessionId)
                           .forEach(sessionIds::add);
                } catch (Exception ignored) {}
            }

            infos.add(new TopicFileInfo(name, lineCount, ageDays, sessionIds.size()));
        }

        // Compute hotness (requires maxHits across all files)
        int maxHits = infos.stream().mapToInt(f -> f.sessionHits).max().orElse(1);
        if (maxHits == 0) maxHits = 1;
        final int fMaxHits = maxHits;
        infos.forEach(f -> f.hotness = computeHotness(f.sessionHits, fMaxHits, f.ageDays));
        infos.sort(Comparator.comparingDouble((TopicFileInfo f) -> f.hotness).reversed());
        return infos;
    }

    /**
     * Computes hotness score in [0, 1].
     * Formula: 0.6 * (hits / maxHits) + 0.4 * (1 - min(ageDays, 60) / 60)
     */
    static double computeHotness(int hits, int maxHits, long ageDays) {
        double hitScore = maxHits > 0 ? (double) hits / maxHits : 0.0;
        double recencyScore = 1.0 - Math.min(ageDays, 60) / 60.0;
        return Math.max(0.0, Math.min(1.0, 0.6 * hitScore + 0.4 * recencyScore));
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private void printTable(List<TopicFileInfo> files, int totalLines, Path dir) {
        System.out.println();
        AnsiOutput.printHeader("Synthesis - Topic Health");
        System.out.printf("  Directory:   %s%n", dir);
        System.out.printf("  Files: %d  |  Total lines: %d%n", files.size(), totalLines);
        printBaselineDelta(totalLines, files.size());
        System.out.println();

        System.out.printf("  %-40s %5s %5s %5s %6s  %s%n",
                "File", "Lines", "Age", "Hits", "Heat", "Status");
        System.out.printf("  %-40s %5s %5s %5s %6s  %s%n",
                "-".repeat(39), "-----", "-----", "-----", "------", "------");

        for (TopicFileInfo f : files) {
            String status = f.hotness >= 0.6 ? AnsiOutput.green("HOT ")
                    : f.hotness >= 0.3 ? AnsiOutput.yellow("WARM")
                    : AnsiOutput.red("COLD");
            System.out.printf("  %-40s %5d %4dd %5d  %5.2f  %s%n",
                    truncate(f.name, 40), f.lineCount, f.ageDays, f.sessionHits, f.hotness, status);
        }

        System.out.println();
        long hot  = files.stream().filter(f -> f.hotness >= 0.6).count();
        long warm = files.stream().filter(f -> f.hotness >= 0.3 && f.hotness < 0.6).count();
        long cold = files.stream().filter(f -> f.hotness < 0.3).count();
        System.out.printf("  %s %d HOT  |  %s %d WARM  |  %s %d COLD%n",
                AnsiOutput.green("●"), hot, AnsiOutput.yellow("●"), warm, AnsiOutput.red("●"), cold);
        System.out.println();

        if (cold > 0) {
            System.out.println("  " + AnsiOutput.dim(
                    "Run 'synthesis topic-triage' for scored maintenance suggestions."));
            System.out.println();
        }
    }

    private void printBaselineDelta(int currentLines, int currentFiles) {
        Path baselinePath = Path.of(System.getProperty("user.home"),
                ".synthesis", "topic-health-baseline.json");
        if (!Files.isRegularFile(baselinePath)) return;
        try {
            String json = Files.readString(baselinePath);
            int baselineLines = parseJsonInt(json, "totalLines");
            int baselineFiles = parseJsonInt(json, "fileCount");
            if (baselineLines <= 0) return;
            int lineDelta = currentLines - baselineLines;
            int fileDelta = currentFiles - baselineFiles;
            String lineDeltaStr = (lineDelta >= 0 ? "+" : "") + lineDelta;
            String fileDeltaStr = (fileDelta >= 0 ? "+" : "") + fileDelta;
            String lineColored = lineDelta > 50 ? AnsiOutput.yellow(lineDeltaStr)
                    : lineDelta < 0 ? AnsiOutput.green(lineDeltaStr) : lineDeltaStr;
            System.out.printf("  Baseline:    %s lines  %s files  (since 2026-04-05)%n",
                    lineColored, fileDeltaStr);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // JSONL logging
    // -------------------------------------------------------------------------

    private void appendHealthSnapshot(List<TopicFileInfo> files, int totalLines) {
        try {
            Path logPath = Path.of(System.getProperty("user.home"),
                    ".synthesis", "topic-triage-log.jsonl");
            ensureParentDir(logPath);

            List<String> hot  = files.stream().filter(f -> f.hotness >= 0.6)
                    .map(f -> f.name).limit(5).toList();
            List<String> cold = files.stream().filter(f -> f.hotness < 0.3)
                    .map(f -> f.name).limit(5).toList();
            long medianAge = files.isEmpty() ? 0
                    : files.stream().mapToLong(f -> f.ageDays).sorted()
                           .skip(files.size() / 2).findFirst().orElse(0);

            String record = "{\"type\":\"health-snapshot\",\"timestamp\":\"" + Instant.now() + "\""
                    + ",\"totalLines\":" + totalLines
                    + ",\"fileCount\":" + files.size()
                    + ",\"hotFiles\":[" + toJsonArray(hot) + "]"
                    + ",\"coldFiles\":[" + toJsonArray(cold) + "]"
                    + ",\"medianAgeDays\":" + medianAge + "}";

            Files.writeString(logPath, record + "\n",
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path resolvedMemoryDir() {
        if (memoryDir != null) return memoryDir.toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"),
                ".claude", "projects", "-home-totto-Documents", "memory");
    }

    /**
     * Extracts searchable keywords from a topic filename.
     * Example: "mistakes-to-avoid.md" → ["mistakes", "avoid"]
     */
    static List<String> extractKeywords(String filename) {
        String base = filename.replaceAll("\\.md$", "").toLowerCase();
        List<String> words = new ArrayList<>();
        for (String part : base.split("[-_]")) {
            String word = part.trim();
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words.isEmpty() ? List.of(base.replaceAll("\\.md$", "")) : words;
    }

    private int countLines(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return (int) lines.count();
        } catch (IOException e) {
            return 0;
        }
    }

    private long daysSinceModified(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return ChronoUnit.DAYS.between(attrs.lastModifiedTime().toInstant(), Instant.now());
        } catch (IOException e) {
            return 0;
        }
    }

    private int parseJsonInt(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return 0;
        int start = idx + key.length() + 3;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Exception e) { return 0; }
    }

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max - 1) + "\u2026";
    }

    private void ensureParentDir(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent);
    }

    // -------------------------------------------------------------------------
    // Data class
    // -------------------------------------------------------------------------

    static class TopicFileInfo {
        final String name;
        final int lineCount;
        final long ageDays;
        final int sessionHits;
        double hotness;

        TopicFileInfo(String name, int lineCount, long ageDays, int sessionHits) {
            this.name = name;
            this.lineCount = lineCount;
            this.ageDays = ageDays;
            this.sessionHits = sessionHits;
        }
    }
}
