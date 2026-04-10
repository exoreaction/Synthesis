package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.sessions.SessionStore;
import io.exoreaction.synthesis.skills.ConsolidateState;
import io.exoreaction.synthesis.skills.ConsolidateState.State;
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
 * {@code synthesis topic-triage} — scored maintenance suggestions for memory topic files.
 *
 * <p>Scores each topic file on four dimensions (Recency, Recurrence, Actionability, Staleness)
 * and outputs the top 5 files that need attention with suggested actions
 * (ARCHIVE / PRUNE / UPDATE / KEEP). Advisory only — no files are modified.
 *
 * <p>Tracks state in {@code ~/.synthesis/consolidate-state.json} for the dual-threshold
 * auto-trigger: at least 24h elapsed AND 5+ new sessions since last run.
 *
 * <p>Usage:
 * <pre>
 *   synthesis topic-triage                  # manual run (always executes)
 *   synthesis topic-triage --auto           # skip if thresholds not met
 *   synthesis topic-triage --since 14d      # look back 14 days (default: 30d)
 *   synthesis topic-triage --memory-dir /p  # custom memory directory
 * </pre>
 */
@Command(
        name = "topic-triage",
        description = "Score memory topic files and suggest maintenance actions",
        mixinStandardHelpOptions = true
)
public class TopicTriageCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--memory-dir"},
            description = "Memory directory to scan (default: ~/.claude/projects/-home-totto-Documents/memory/)")
    private Path memoryDir;

    @Option(names = {"--since"},
            description = "How far back to search sessions (default: 30d)",
            defaultValue = "30d")
    private String since;

    @Option(names = {"--auto"},
            description = "Skip if dual-threshold not met (24h + 5 sessions)",
            defaultValue = "false")
    private boolean auto;

    private static final Set<String> STOP_WORDS = TopicHealthCommand.STOP_WORDS;
    private static final List<String> ACTIONABILITY_PATTERNS =
            List.of("always", "never", "must", "instead of", "before", "critical", "warning", "important");

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

            // Dual-threshold check for --auto mode
            State state = ConsolidateState.load();
            int newSessions = 0;
            if (state.lastConsolidatedAt() != null) {
                newSessions = store.listSince(state.lastConsolidatedAt(), null).size();
            }

            if (auto && !ConsolidateState.isDue(state, newSessions)) {
                System.out.println("synthesis topic-triage: thresholds not met, skipping.");
                return 0;
            }

            String trigger = auto ? "auto" : "manual";

            // Parse since
            Instant sinceInstant = SessionsCommand.parseSince(since);

            List<TopicEntry> entries = scoreFiles(resolvedDir, store, sinceInstant);
            if (entries.isEmpty()) {
                AnsiOutput.printInfo("No .md files found in " + resolvedDir);
                return 0;
            }

            // Sort: most attention needed first = lowest composite score
            entries.sort(Comparator.comparingDouble(e -> e.composite));
            List<TopicEntry> top5 = entries.stream().limit(5).toList();

            printSuggestions(top5, entries.size(), resolvedDir);
            appendTriageRecord(top5, entries.size(), trigger, newSessions);

            // Update state
            int totalSessions = newSessions + state.sessionCountAtLastConsolidate();
            ConsolidateState.save(new State(Instant.now(), totalSessions));

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("topic-triage failed: " + e.getMessage());
            return 1;
        }
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    private List<TopicEntry> scoreFiles(Path dir, SessionStore store, Instant since) throws Exception {
        List<Path> mdFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                  .sorted()
                  .forEach(mdFiles::add);
        }

        List<TopicEntry> entries = new ArrayList<>();

        for (Path file : mdFiles) {
            String name = file.getFileName().toString();
            int lineCount = countLines(file);
            long ageDays = daysSinceModified(file);
            List<String> keywords = TopicHealthCommand.extractKeywords(name);

            // Gather all session hits for this file's keywords
            Set<String> sessionIds = new LinkedHashSet<>();
            Instant mostRecentHit = null;

            for (String kw : keywords) {
                try {
                    List<ClaudeSession> results = store.search(kw, 200);
                    for (ClaudeSession s : results) {
                        if (s.startedAt() != null && s.startedAt().isAfter(since)) {
                            sessionIds.add(s.sessionId());
                            if (mostRecentHit == null || s.startedAt().isAfter(mostRecentHit)) {
                                mostRecentHit = s.startedAt();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            double recency = scoreRecency(mostRecentHit);
            double recurrence = scoreRecurrence(sessionIds.size());
            double actionability = scoreActionability(file);
            double staleness = Math.min(ageDays / 60.0, 1.0);
            double composite = computeComposite(recency, recurrence, actionability, staleness);
            String action = suggestAction(staleness, recurrence, recency, lineCount);

            entries.add(new TopicEntry(name, lineCount, ageDays, sessionIds.size(),
                    recency, recurrence, actionability, staleness, composite, action));
        }

        return entries;
    }

    /**
     * Scores recency based on when the most recent session hit occurred.
     */
    static double scoreRecency(Instant mostRecentHit) {
        if (mostRecentHit == null) return 0.1;
        long days = ChronoUnit.DAYS.between(mostRecentHit, Instant.now());
        if (days <= 7)  return 1.0;
        if (days <= 14) return 0.7;
        if (days <= 30) return 0.4;
        return 0.1;
    }

    /**
     * Scores recurrence: distinct session count / 3, clamped to 1.0.
     */
    static double scoreRecurrence(int sessionCount) {
        return Math.min(sessionCount / 3.0, 1.0);
    }

    /**
     * Scores actionability: 1.0 if first 50 lines contain prescriptive patterns, else 0.0.
     */
    static double scoreActionability(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            int limit = Math.min(50, lines.size());
            for (int i = 0; i < limit; i++) {
                String lower = lines.get(i).toLowerCase();
                for (String pattern : ACTIONABILITY_PATTERNS) {
                    if (lower.contains(pattern)) return 1.0;
                }
            }
        } catch (IOException ignored) {}
        return 0.0;
    }

    /**
     * Composite = 0.3*Recency + 0.25*Recurrence + 0.25*Actionability + 0.2*(1-Staleness)
     * Higher score = more valuable/active file. Lower score = more attention needed.
     */
    static double computeComposite(double recency, double recurrence,
                                    double actionability, double staleness) {
        return 0.30 * recency
             + 0.25 * recurrence
             + 0.25 * actionability
             + 0.20 * (1.0 - staleness);
    }

    private String suggestAction(double staleness, double recurrence,
                                  double recency, int lineCount) {
        if (staleness > 0.8 && recurrence < 0.2) return "ARCHIVE";
        if (lineCount > 300 && recurrence < 0.4)  return "PRUNE";
        if (recency < 0.4 && recurrence > 0.6)    return "UPDATE";
        return "KEEP";
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private void printSuggestions(List<TopicEntry> top5, int totalFiles, Path dir) {
        System.out.println();
        AnsiOutput.printHeader("Synthesis - Topic Triage");
        System.out.printf("  Directory: %s%n", dir);
        System.out.printf("  Files scanned: %d  |  Top suggestions: %d%n%n",
                totalFiles, top5.size());

        for (int i = 0; i < top5.size(); i++) {
            TopicEntry e = top5.get(i);
            String actionColor = switch (e.action) {
                case "ARCHIVE" -> AnsiOutput.red(e.action);
                case "PRUNE"   -> AnsiOutput.yellow(e.action);
                case "UPDATE"  -> AnsiOutput.cyan(e.action);
                default        -> AnsiOutput.dim(e.action);
            };

            System.out.printf("  %d. %s  →  %s%n", i + 1,
                    AnsiOutput.bold(e.name), actionColor);
            System.out.printf("     Score: %.2f  (recency=%.2f  recurrence=%.2f  " +
                              "actionability=%.2f  staleness=%.2f)%n",
                    e.composite, e.recency, e.recurrence, e.actionability, e.staleness);
            System.out.printf("     %s  |  %d lines  |  %dd old  |  %d session hits%n",
                    reasonFor(e), e.lineCount, e.ageDays, e.sessionHits);
            System.out.println();
        }

        System.out.println("  " + AnsiOutput.dim(
                "Advisory only — no files modified. Act on suggestions manually."));
        System.out.println();
    }

    private String reasonFor(TopicEntry e) {
        return switch (e.action) {
            case "ARCHIVE" -> "stale (>" + e.ageDays + "d) + no recent session hits";
            case "PRUNE"   -> "large (" + e.lineCount + " lines) with low session engagement";
            case "UPDATE"  -> "referenced in sessions but not recently edited";
            default        -> "no action needed";
        };
    }

    // -------------------------------------------------------------------------
    // JSONL logging
    // -------------------------------------------------------------------------

    private void appendTriageRecord(List<TopicEntry> suggestions, int filesScanned,
                                     String trigger, int newSessions) {
        try {
            Path logPath = Path.of(System.getProperty("user.home"),
                    ".synthesis", "topic-triage-log.jsonl");
            Path logDir = logPath.getParent();
            if (logDir != null && !Files.isDirectory(logDir)) Files.createDirectories(logDir);

            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"triage\"");
            sb.append(",\"timestamp\":\"").append(Instant.now()).append("\"");
            sb.append(",\"trigger\":\"").append(trigger).append("\"");
            sb.append(",\"sessionsSinceLastRun\":").append(newSessions);
            sb.append(",\"filesScanned\":").append(filesScanned);
            sb.append(",\"suggestionsCount\":").append(suggestions.size());
            sb.append(",\"suggestions\":[");

            for (int i = 0; i < suggestions.size(); i++) {
                TopicEntry e = suggestions.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"file\":\"").append(e.name.replace("\"", "\\\"")).append("\"");
                sb.append(",\"action\":\"").append(e.action).append("\"");
                sb.append(",\"compositeScore\":").append(String.format("%.3f", e.composite));
                sb.append(",\"recency\":").append(String.format("%.3f", e.recency));
                sb.append(",\"recurrence\":").append(String.format("%.3f", e.recurrence));
                sb.append(",\"actionability\":").append(String.format("%.3f", e.actionability));
                sb.append(",\"staleness\":").append(String.format("%.3f", e.staleness));
                sb.append(",\"lines\":").append(e.lineCount);
                sb.append(",\"ageDays\":").append(e.ageDays);
                sb.append(",\"sessionHits\":").append(e.sessionHits);
                sb.append(",\"reason\":\"").append(reasonFor(e).replace("\"", "\\\"")).append("\"");
                sb.append("}");
            }

            sb.append("]}");
            Files.writeString(logPath, sb + "\n",
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

    // -------------------------------------------------------------------------
    // Data record
    // -------------------------------------------------------------------------

    record TopicEntry(
            String name, int lineCount, long ageDays, int sessionHits,
            double recency, double recurrence, double actionability, double staleness,
            double composite, String action
    ) {}
}
