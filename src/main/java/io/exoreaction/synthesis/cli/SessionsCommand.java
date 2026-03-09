package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.sessions.ClaudeSessionScanner;
import io.exoreaction.synthesis.sessions.SessionStore;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI command for indexing and searching Claude Code session history (episodic memory).
 *
 * <p>Usage:
 * <pre>
 *   synthesis sessions scan              # Scan ~/.claude/projects/ and index
 *   synthesis sessions search "query"    # FTS search across all sessions
 *   synthesis sessions list              # List recent sessions
 *   synthesis sessions list --project X  # Filter by project directory
 *   synthesis sessions list --since 7d   # Sessions from last 7 days
 *   synthesis sessions get &lt;session-id&gt;  # Full detail for one session
 * </pre>
 */
@Command(
        name = "sessions",
        description = "Index and search Claude Code session history (episodic memory)",
        mixinStandardHelpOptions = true,
        subcommands = {
                SessionsCommand.ScanSubcommand.class,
                SessionsCommand.SearchSubcommand.class,
                SessionsCommand.ListSubcommand.class,
                SessionsCommand.GetSubcommand.class
        }
)
public class SessionsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        // No subcommand -- print usage
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    // -----------------------------------------------------------------------
    // Subcommand: scan
    // -----------------------------------------------------------------------

    @Command(name = "scan", description = "Scan ~/.claude/projects/ and index session history",
            mixinStandardHelpOptions = true)
    static class ScanSubcommand implements Callable<Integer> {

        @ParentCommand
        private SessionsCommand parent;

        @Option(names = {"-v", "--verbose"}, description = "Show verbose output", defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                AnsiOutput.printHeader("Synthesis - Sessions Scan");
                SynthesisDatabase db = SynthesisDatabase.getDefault();
                SessionStore store = new SessionStore(db);
                ClaudeSessionScanner scanner = new ClaudeSessionScanner(store);

                int before = store.count();
                AnsiOutput.printInfo("Scanning ~/.claude/projects/ ...");
                long start = System.currentTimeMillis();
                int processed = scanner.scan();
                long elapsed = System.currentTimeMillis() - start;
                int total = store.count();

                AnsiOutput.printSuccess(String.format("Indexed %d sessions (%d updated) — %d total [%dms]",
                        processed, processed, total, elapsed));

                if (verbose && processed > 0) {
                    System.out.println();
                    AnsiOutput.printInfo("Run 'synthesis sessions list' to browse indexed sessions.");
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Sessions scan failed: " + e.getMessage());
                if (verbose) e.printStackTrace();
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: search
    // -----------------------------------------------------------------------

    @Command(name = "search", description = "FTS search across indexed session history",
            mixinStandardHelpOptions = true)
    static class SearchSubcommand implements Callable<Integer> {

        @ParentCommand
        private SessionsCommand parent;

        @Parameters(index = "0", description = "Search query (FTS5 syntax)")
        private String query;

        @Option(names = {"--limit"}, description = "Maximum results (default: 20)", defaultValue = "20")
        private int limit;

        @Option(names = {"-v", "--verbose"}, description = "Show full user text", defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                AnsiOutput.printHeader("Synthesis - Session Search");
                SynthesisDatabase db = SynthesisDatabase.getDefault();
                SessionStore store = new SessionStore(db);
                List<ClaudeSession> results = store.search(query, limit);

                if (results.isEmpty()) {
                    AnsiOutput.printInfo("No sessions found matching: " + query);
                    return 0;
                }

                System.out.println("  " + AnsiOutput.bold("Sessions matching \"" + query + "\" ("
                        + results.size() + "):"));
                System.out.println();

                for (ClaudeSession session : results) {
                    printSession(session, verbose);
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Session search failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: list
    // -----------------------------------------------------------------------

    @Command(name = "list", description = "List recent indexed sessions",
            mixinStandardHelpOptions = true)
    static class ListSubcommand implements Callable<Integer> {

        @ParentCommand
        private SessionsCommand parent;

        @Option(names = {"--project"}, description = "Filter by project directory (substring match)")
        private String project;

        @Option(names = {"--since"}, description = "Duration filter: 7d, 24h, 30d (default: 30d)",
                defaultValue = "30d")
        private String since;

        @Option(names = {"--limit"}, description = "Maximum results (default: 30)", defaultValue = "30")
        private int limit;

        @Option(names = {"-v", "--verbose"}, description = "Show full user text", defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                AnsiOutput.printHeader("Synthesis - Session History");
                SynthesisDatabase db = SynthesisDatabase.getDefault();
                SessionStore store = new SessionStore(db);

                Instant sinceInstant = parseSince(since);
                List<ClaudeSession> sessions = store.listSince(sinceInstant, project);

                // Apply limit
                if (sessions.size() > limit) {
                    sessions = sessions.subList(0, limit);
                }

                if (sessions.isEmpty()) {
                    AnsiOutput.printInfo("No sessions found. Run 'synthesis sessions scan' first.");
                    return 0;
                }

                String header = "Recent sessions (" + sessions.size() + ")"
                        + (project != null ? " — project: " + project : "")
                        + " — since: " + since;
                System.out.println("  " + AnsiOutput.bold(header));
                System.out.println();

                for (ClaudeSession session : sessions) {
                    printSession(session, verbose);
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Sessions list failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: get
    // -----------------------------------------------------------------------

    @Command(name = "get", description = "Show full detail for one session",
            mixinStandardHelpOptions = true)
    static class GetSubcommand implements Callable<Integer> {

        @ParentCommand
        private SessionsCommand parent;

        @Parameters(index = "0", description = "Session UUID")
        private String sessionId;

        @Override
        public Integer call() {
            try {
                AnsiOutput.printHeader("Synthesis - Session Detail");
                SynthesisDatabase db = SynthesisDatabase.getDefault();
                SessionStore store = new SessionStore(db);

                Optional<ClaudeSession> result = store.getBySessionId(sessionId);
                if (result.isEmpty()) {
                    AnsiOutput.printError("Session not found: " + sessionId);
                    AnsiOutput.printInfo("Run 'synthesis sessions scan' to index sessions first.");
                    return 1;
                }

                ClaudeSession session = result.get();
                System.out.println();
                System.out.println("  " + AnsiOutput.bold("Session: ") + AnsiOutput.cyan(session.sessionId()));
                System.out.println("  " + AnsiOutput.bold("Project: ") + session.projectDir());
                System.out.println("  " + AnsiOutput.bold("Started: ") + formatTime(session.startedAt()));
                if (session.endedAt() != null) {
                    System.out.println("  " + AnsiOutput.bold("Ended:   ") + formatTime(session.endedAt()));
                }
                System.out.println("  " + AnsiOutput.bold("Turns:   ") + session.turnCount());
                System.out.println("  " + AnsiOutput.bold("Tools:   ") + session.toolCallCount()
                        + (session.toolNames() != null && !session.toolNames().isEmpty()
                        ? " — " + String.join(", ", session.toolNames()) : ""));
                System.out.println();
                if (session.firstMessage() != null) {
                    System.out.println("  " + AnsiOutput.bold("Opening intent:"));
                    System.out.println("    " + session.firstMessage());
                }
                if (session.allUserText() != null && session.allUserText().length() > session.firstMessage().length()) {
                    System.out.println();
                    System.out.println("  " + AnsiOutput.bold("User messages (all):"));
                    System.out.println("    " + session.allUserText());
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Sessions get failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static void printSession(ClaudeSession session, boolean verbose) {
        String ts = formatTime(session.startedAt());
        System.out.println("  " + AnsiOutput.cyan(ts) + "  " + AnsiOutput.bold(session.sessionId()));
        System.out.println("    " + AnsiOutput.dim("project: ") + session.projectDir());
        System.out.println("    " + AnsiOutput.dim("turns:   ") + session.turnCount()
                + "   " + AnsiOutput.dim("tools: ") + session.toolCallCount());
        if (session.firstMessage() != null) {
            System.out.println("    " + AnsiOutput.dim("intent:  ") + truncate(session.firstMessage(), 80));
        }
        if (verbose && session.allUserText() != null
                && !session.allUserText().equals(session.firstMessage())) {
            System.out.println("    " + AnsiOutput.dim("text:    ") + truncate(session.allUserText(), 200));
        }
        System.out.println();
    }

    static String formatTime(Instant instant) {
        if (instant == null) return "(unknown)";
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TIME_FMT);
    }

    public static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Instant.now().minus(30, ChronoUnit.DAYS);
        }
        try {
            String value = since.substring(0, since.length() - 1);
            char unit = since.charAt(since.length() - 1);
            int amount = Integer.parseInt(value);
            return switch (unit) {
                case 'h' -> Instant.now().minus(amount, ChronoUnit.HOURS);
                case 'd' -> Instant.now().minus(amount, ChronoUnit.DAYS);
                case 'w' -> Instant.now().minus(amount * 7L, ChronoUnit.DAYS);
                default -> Instant.now().minus(30, ChronoUnit.DAYS);
            };
        } catch (Exception e) {
            return Instant.now().minus(30, ChronoUnit.DAYS);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
