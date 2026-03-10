package io.exoreaction.synthesis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.sessions.ClaudeSessionScanner;
import io.exoreaction.synthesis.sessions.SessionStore;
import io.exoreaction.synthesis.skills.ReflectState;
import io.exoreaction.synthesis.skills.ReflectState.State;
import io.exoreaction.synthesis.skills.SessionAnalyzer;
import io.exoreaction.synthesis.skills.SessionAnalyzer.ExtractedPattern;
import io.exoreaction.synthesis.skills.SkillUpdater;
import io.exoreaction.synthesis.skills.SkillUpdater.ChangeType;
import io.exoreaction.synthesis.skills.SkillUpdater.ReflectResult;
import io.exoreaction.synthesis.skills.SkillUpdater.SkillChange;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command that analyzes recent Claude Code sessions and automatically
 * creates or updates skill YAML files based on discovered patterns.
 *
 * <p>Usage:
 * <pre>
 *   synthesis reflect                          # analyze last 7 days, apply changes
 *   synthesis reflect --since 30d              # look back 30 days
 *   synthesis reflect --dry-run                # preview without writing files
 *   synthesis reflect --compact                # single-line output for scripting
 *   synthesis reflect --json                   # machine-readable JSON
 *   synthesis reflect --max-new 3              # limit new skill files created
 *   synthesis reflect --min-confidence 0.5     # higher confidence threshold
 *   synthesis reflect --force                  # bypass staleness check
 *   synthesis reflect --verbose                # detailed output
 * </pre>
 *
 * <p>The reflect command maintains state in {@code ~/.synthesis/reflect-state.json}
 * to avoid redundant re-analysis. Use {@code --force} to bypass the staleness
 * check.
 */
@Command(
        name = "reflect",
        description = "Analyze sessions and update skill library from discovered patterns",
        mixinStandardHelpOptions = true
)
public class ReflectCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--since"}, description = "How far back to analyze (default: 7d)",
            defaultValue = "7d")
    private String since;

    @Option(names = {"--skills-dir"}, description = "Skills directory (default: ~/.claude/skills/)")
    private Path skillsDir;

    @Option(names = {"--dry-run"}, description = "Preview changes without writing files",
            defaultValue = "false")
    private boolean dryRun;

    @Option(names = {"--verbose", "-v"}, description = "Show detailed output",
            defaultValue = "false")
    private boolean verbose;

    @Option(names = {"--json"}, description = "Machine-readable JSON output",
            defaultValue = "false")
    private boolean json;

    @Option(names = {"--compact"}, description = "Single-line output for scripting",
            defaultValue = "false")
    private boolean compact;

    @Option(names = {"--max-new"}, description = "Maximum new skills to create (default: 5)",
            defaultValue = "5")
    private int maxNew;

    @Option(names = {"--min-confidence"}, description = "Minimum confidence threshold (default: 0.3)",
            defaultValue = "0.3")
    private double minConfidence;

    @Option(names = {"--force"}, description = "Bypass staleness check",
            defaultValue = "false")
    private boolean force;

    @Override
    public Integer call() {
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Resolve skills directory
            Path resolvedSkillsDir = skillsDir != null ? skillsDir.toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.home"), ".claude", "skills");

            // Step 2: Load reflect state and scan sessions
            Instant sinceInstant = SessionsCommand.parseSince(since);
            State state = ReflectState.load();

            SynthesisDatabase db = SynthesisDatabase.getDefault();
            SessionStore store = new SessionStore(db);
            ClaudeSessionScanner scanner = new ClaudeSessionScanner(store);
            scanner.scan();

            // Step 3: Load only sessions newer than last reflect (not the full since window).
            // This ensures staleness is measured by new session count, not elapsed time (#311).
            Instant effectiveSince = (state.lastReflectedAt() != null && !force
                    && state.lastReflectedAt().isAfter(sinceInstant))
                    ? state.lastReflectedAt()
                    : sinceInstant;
            List<ClaudeSession> sessions = store.listSince(effectiveSince, null);

            // Up-to-date check: no new sessions since last reflect
            if (!force && sessions.isEmpty()) {
                long ago = state.lastReflectedAt() != null
                        ? Duration.between(state.lastReflectedAt(), Instant.now()).toMinutes() : -1;
                String humanDuration = ago >= 0 ? formatDuration(ago) : "never";
                if (compact) {
                    System.out.println("reflect: up-to-date (0 new sessions since " + humanDuration + " ago)");
                } else if (json) {
                    System.out.println("{\"status\":\"up-to-date\",\"newSessions\":0,\"lastReflected\":\""
                            + state.lastReflectedAt() + "\"}");
                } else {
                    AnsiOutput.printInfo("Skill library is up-to-date (0 new sessions since last reflect "
                            + humanDuration + " ago). Use --force to re-analyze.");
                }
                return 0;
            }

            if (sessions.isEmpty()) {
                if (compact) {
                    System.out.println("reflect: no new sessions");
                } else if (json) {
                    System.out.println("{\"status\":\"no-sessions\",\"sessionsAnalyzed\":0}");
                } else {
                    AnsiOutput.printInfo("No sessions found since " + since
                            + ". Run 'synthesis sessions scan' first.");
                }
                return 0;
            }

            // Step 5: Analyze patterns
            List<ExtractedPattern> patterns = SessionAnalyzer.analyze(sessions, minConfidence);

            if (verbose && !compact && !json) {
                AnsiOutput.printInfo("Found " + patterns.size() + " patterns from "
                        + sessions.size() + " sessions");
            }

            // Step 6: Apply to skill library
            ReflectResult result = SkillUpdater.apply(patterns, resolvedSkillsDir, dryRun, maxNew);

            // Step 7: Save state (unless dry run)
            if (!dryRun) {
                ReflectState.save(new State(
                        Instant.now(),
                        sessions.size(),
                        result.skillsCreated(),
                        result.skillsUpdated()));
            }

            // Step 8: Render output
            long elapsed = System.currentTimeMillis() - startTime;
            renderOutput(result, sessions.size(), patterns.size(), elapsed);

            return 0;

        } catch (Exception e) {
            if (compact) {
                System.out.println("reflect: error — " + e.getMessage());
            } else if (json) {
                System.out.println("{\"status\":\"error\",\"message\":\""
                        + escapeJson(e.getMessage()) + "\"}");
            } else {
                AnsiOutput.printError("Reflect failed: " + e.getMessage());
                if (verbose) e.printStackTrace();
            }
            return 1;
        }
    }

    // -----------------------------------------------------------------------
    // Output rendering
    // -----------------------------------------------------------------------

    private void renderOutput(ReflectResult result, int sessionCount,
                               int patternCount, long elapsedMs) throws Exception {
        if (compact) {
            System.out.println("reflect: " + sessionCount + " sessions, "
                    + result.skillsCreated() + " created, "
                    + result.skillsUpdated() + " updated, "
                    + result.skillsSkipped() + " skipped"
                    + (dryRun ? " [dry-run]" : "")
                    + " [" + elapsedMs + "ms]");
            return;
        }

        if (json) {
            ObjectMapper mapper = new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"status\": \"ok\",\n");
            sb.append("  \"sessionsAnalyzed\": ").append(sessionCount).append(",\n");
            sb.append("  \"patternsExtracted\": ").append(patternCount).append(",\n");
            sb.append("  \"skillsCreated\": ").append(result.skillsCreated()).append(",\n");
            sb.append("  \"skillsUpdated\": ").append(result.skillsUpdated()).append(",\n");
            sb.append("  \"skillsSkipped\": ").append(result.skillsSkipped()).append(",\n");
            sb.append("  \"dryRun\": ").append(dryRun).append(",\n");
            sb.append("  \"elapsedMs\": ").append(elapsedMs).append(",\n");
            sb.append("  \"reflectedAt\": \"").append(result.reflectedAt()).append("\",\n");
            sb.append("  \"changes\": [\n");
            for (int i = 0; i < result.changes().size(); i++) {
                SkillChange change = result.changes().get(i);
                sb.append("    {");
                sb.append("\"type\": \"").append(change.type()).append("\", ");
                sb.append("\"name\": \"").append(escapeJson(change.skillName())).append("\", ");
                sb.append("\"description\": \"").append(escapeJson(change.description())).append("\"");
                if (change.newVersion() != null) {
                    sb.append(", \"version\": \"").append(change.newVersion()).append("\"");
                }
                if (change.filePath() != null) {
                    sb.append(", \"path\": \"").append(escapeJson(change.filePath().toString())).append("\"");
                }
                sb.append("}").append(i < result.changes().size() - 1 ? "," : "").append("\n");
            }
            sb.append("  ]\n");
            sb.append("}");
            System.out.println(sb);
            return;
        }

        // Default verbose output
        AnsiOutput.printHeader("Synthesis - Reflect");
        System.out.printf("  Sessions analyzed:  %d%n", sessionCount);
        System.out.printf("  Patterns extracted: %d%n", patternCount);
        System.out.printf("  Skills created:     %d%n", result.skillsCreated());
        System.out.printf("  Skills updated:     %d%n", result.skillsUpdated());
        System.out.printf("  Skills skipped:     %d%n", result.skillsSkipped());
        if (dryRun) {
            System.out.println("  " + AnsiOutput.dim("[dry-run — no files written]"));
        }
        System.out.println();

        // Group changes by type
        List<SkillChange> created = result.changes().stream()
                .filter(c -> c.type() == ChangeType.CREATED).toList();
        List<SkillChange> updated = result.changes().stream()
                .filter(c -> c.type() == ChangeType.UPDATED).toList();
        List<SkillChange> skipped = result.changes().stream()
                .filter(c -> c.type() == ChangeType.SKIPPED).toList();

        if (!created.isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Created:"));
            for (SkillChange c : created) {
                System.out.printf("    + %-30s %s%n",
                        AnsiOutput.cyan(c.skillName()),
                        c.description() != null ? truncate(c.description(), 50) : "");
            }
            System.out.println();
        }

        if (!updated.isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Updated:"));
            for (SkillChange c : updated) {
                String ver = c.previousVersion() != null && c.newVersion() != null
                        ? c.previousVersion() + " -> " + c.newVersion() : "";
                System.out.printf("    ~ %-30s %-15s %s%n",
                        AnsiOutput.cyan(c.skillName()), ver,
                        c.description() != null ? truncate(c.description(), 40) : "");
            }
            System.out.println();
        }

        if (verbose && !skipped.isEmpty()) {
            System.out.println("  " + AnsiOutput.dim("Skipped:"));
            for (SkillChange c : skipped) {
                System.out.printf("    - %-30s %s%n",
                        c.skillName(),
                        c.description() != null ? truncate(c.description(), 50) : "");
            }
            System.out.println();
        }

        System.out.printf("  Completed in %dms%n", elapsedMs);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String formatDuration(long minutes) {
        if (minutes < 60) return minutes + " minutes";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hours";
        long days = hours / 24;
        return days + " days";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
