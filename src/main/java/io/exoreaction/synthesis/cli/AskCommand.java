package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.AiProvider;
import io.exoreaction.synthesis.ai.DirectedSynthesisEngine;
import io.exoreaction.synthesis.ai.Grounder;
import io.exoreaction.synthesis.ai.PromptTemplates;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.sessions.SessionStore;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * AI-powered Q&A command that answers questions about the workspace.
 *
 * <p>Searches the workspace index for relevant files, reads their content,
 * and uses Claude to synthesize an answer citing specific files and line numbers.
 *
 * <p>Usage:
 * <pre>
 *   synthesis ask "How does plugin loading work?"
 *   synthesis ask "What testing frameworks are used?" --context 10
 *   synthesis ask "Where is authentication handled?" --verbose
 * </pre>
 */
@Command(
        name = "ask",
        description = "Ask a question about your workspace (AI-powered)",
        mixinStandardHelpOptions = true
)
public class AskCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "Your question about the workspace",
            arity = "0..1"
    )
    private String question;

    @Option(
            names = {"-c", "--context"},
            description = "Number of files to include as context (default: 8)",
            defaultValue = "8"
    )
    private int contextFiles;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show which files were used as context",
            defaultValue = "false"
    )
    private boolean verbose;

    @Option(
            names = {"--max-tokens"},
            description = "Maximum tokens in AI response (default: 2048)",
            defaultValue = "2048"
    )
    private int maxTokens;

    @Option(
            names = {"--ground"},
            description = "Verify answer claims against loaded context (fail-closed grounding)",
            defaultValue = "false"
    )
    private boolean ground;

    @Option(
            names = {"-i", "--interactive"},
            description = "Start interactive conversation mode",
            defaultValue = "false"
    )
    private boolean interactive;

    @Override
    public Integer call() {
        if (interactive) {
            return runInteractive();
        }

        if (question == null || question.isBlank()) {
            AnsiOutput.printError("Question is required (or use --interactive for conversation mode)");
            System.out.println("  Usage: synthesis ask \"your question\"");
            System.out.println("  Or:    synthesis ask --interactive");
            return 1;
        }

        return runSingleQuestion();
    }

    private Integer runSingleQuestion() {
        long startMs = System.nanoTime();
        boolean metricsSuccess = false;
        String metricsWs = "unknown";
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            metricsWs = workspaceRoot.toString();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load config and create AI client
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<AiClient> clientOpt = AiClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                String keyName = AiProvider.forConfig(config.getAi()).apiKeyName();
                AnsiOutput.printError("AI is not configured. Set ai.enabled=true in config and provide " + keyName + ".");
                AnsiOutput.printInfo("Edit .synthesis/config.yaml or set environment variable " + keyName + ".");
                return 1;
            }

            AiClient client = clientOpt.get();

            // Search for relevant files
            if (verbose) {
                AnsiOutput.printInfo("Searching workspace for relevant files...");
            }

            List<SearchResult> results;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                results = index.search(question, contextFiles);
            }

            // Boost results whose paths match KCP units with query-matching triggers
            results = boostByKcpTriggers(results, question, workspaceRoot.toString());

            if (results.isEmpty()) {
                AnsiOutput.printWarning("No relevant files found in the index.");
                AnsiOutput.printInfo("Try running 'synthesis scan' first, or rephrase your question.");
                return 0;
            }

            if (verbose) {
                System.out.println();
                AnsiOutput.printInfo("Using " + results.size() + " files as context:");
                for (SearchResult r : results) {
                    System.out.printf("    %s (score: %.2f)%n", r.relativePath(), r.score());
                }
                System.out.println();
            }

            // Build context from file contents
            String context = buildContext(results, workspaceRoot);

            // Enrich with session history (episodic memory)
            String sessionContext = buildSessionContext(question);
            if (verbose && !sessionContext.isEmpty()) {
                AnsiOutput.printInfo("Including session history context.");
            }

            // Generate prompt and ask Claude
            String prompt = sessionContext.isEmpty()
                    ? PromptTemplates.buildAskPrompt(question, context)
                    : PromptTemplates.buildAskPrompt(question, context, sessionContext);

            if (verbose) {
                AnsiOutput.printInfo("Asking Claude (" + client.getModel() + ")...");
                System.out.println();
            }

            String answer = client.generate(prompt, maxTokens);

            // Display answer
            System.out.println();
            System.out.println(AnsiOutput.bold("  Q: " + question));
            System.out.println();
            // Indent the answer
            for (String line : answer.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println();

            // Grounding: verify claims against loaded context
            if (ground) {
                Map<String, Grounder.FileUnit> loadedUnits = buildFileUnits(results, workspaceRoot);
                if (!loadedUnits.isEmpty()) {
                    if (verbose) {
                        AnsiOutput.printInfo("Grounding answer against " + loadedUnits.size() + " loaded units...");
                    }
                    Grounder.GroundedAnswer grounded = Grounder.groundAnswer(
                            answer, loadedUnits, client);
                    printGroundingReport(grounded);
                }
            }

            // Suggest perspectives command for complex/ambiguous questions
            if (DirectedSynthesisEngine.isPerspectivesCandidate(question)) {
                System.out.println(AnsiOutput.cyan(
                        "  \u2139\uFE0F  This question might benefit from multiple perspectives. Try:"));
                System.out.println(AnsiOutput.cyan(
                        "  synthesis perspectives '" + truncateQuestion(question) + "'"));
                System.out.println();
            }

            metricsSuccess = true;
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Ask failed: " + e.getMessage());
            return 1;
        } finally {
            long elapsed = (System.nanoTime() - startMs) / 1_000_000;
            parent.getMetrics().recordAiFeature("ask", metricsWs, elapsed, 0, metricsSuccess, false);
        }
    }

    /**
     * Truncates a question for display in suggestions.
     */
    private static String truncateQuestion(String question) {
        if (question.length() <= 60) return question;
        return question.substring(0, 57) + "...";
    }

    /**
     * Searches the episodic memory (session history) for sessions relevant to the question.
     * Returns an empty string if no sessions are found or the database is unavailable.
     */
    private String buildSessionContext(String question) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            SessionStore store = new SessionStore(db);
            List<ClaudeSession> sessions = store.search(SessionStore.sanitizeFtsQuery(question), 3);
            if (sessions.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ClaudeSession s : sessions) {
                sb.append("\n--- Session: ")
                  .append(s.sessionId().length() > 8 ? s.sessionId().substring(0, 8) + "..." : s.sessionId());
                if (s.startedAt() != null) {
                    sb.append(" (").append(s.startedAt().toString(), 0, 10).append(")");
                }
                if (s.projectDir() != null) {
                    sb.append(" [").append(s.projectDir()).append("]");
                }
                sb.append(" ---\n");
                if (s.allUserText() != null && !s.allUserText().isBlank()) {
                    sb.append(s.allUserText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // Sessions DB not available — proceed without session context
            return "";
        }
    }

    /**
     * Builds a context string from search results by reading file content.
     * Includes file path, language, and a preview of the content with line numbers.
     */
    private List<SearchResult> boostByKcpTriggers(List<SearchResult> results,
                                                     String query, String workspacePath) {
        try {
            io.exoreaction.synthesis.db.SynthesisDatabase db =
                    io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
            try (java.sql.Connection conn = db.getConnection()) {
                io.exoreaction.synthesis.kcp.KcpRepository repo =
                        new io.exoreaction.synthesis.kcp.KcpRepository();
                List<io.exoreaction.synthesis.kcp.KcpRepository.KcpUnitRow> allUnits =
                        new java.util.ArrayList<>();
                for (io.exoreaction.synthesis.kcp.KcpRepository.KcpManifestRow m :
                        repo.getManifests(conn, workspacePath)) {
                    allUnits.addAll(repo.getUnitsForManifest(conn, workspacePath, m.filePath()));
                }
                if (allUnits.isEmpty()) return results;
                var triggerScores = io.exoreaction.synthesis.kcp.KcpPlanner
                        .buildTriggerScores(query, allUnits);
                if (triggerScores.isEmpty()) return results;
                return io.exoreaction.synthesis.kcp.KcpPlanner
                        .boostResults(results, triggerScores);
            }
        } catch (Exception e) {
            return results;
        }
    }

    String buildContext(List<SearchResult> results, Path workspaceRoot) {
        StringBuilder context = new StringBuilder();
        int maxBytesPerFile = 4096;

        for (SearchResult result : results) {
            Path filePath = result.path();
            context.append("\n--- File: ").append(result.relativePath());
            if (result.language() != null) {
                context.append(" (").append(result.language()).append(")");
            }
            context.append(" ---\n");

            if (!result.summary().isEmpty()) {
                context.append("Summary: ").append(result.summary()).append("\n");
            }

            try {
                if (Files.exists(filePath) && Files.isReadable(filePath)) {
                    String content = FileUtils.readPreview(filePath, maxBytesPerFile);
                    if (!content.isEmpty()) {
                        // Add line numbers
                        String[] lines = content.split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            context.append(String.format("L%d: %s\n", i + 1, lines[i]));
                        }
                    }
                } else {
                    context.append("(file not readable)\n");
                }
            } catch (IOException e) {
                context.append("(error reading file: ").append(e.getMessage()).append(")\n");
            }
        }

        return context.toString();
    }

    /**
     * Build FileUnit map from search results for grounding.
     */
    private Map<String, Grounder.FileUnit> buildFileUnits(List<SearchResult> results, Path workspaceRoot) {
        Map<String, Grounder.FileUnit> units = new LinkedHashMap<>();
        int maxBytes = 4096;
        for (SearchResult r : results) {
            try {
                if (Files.exists(r.path()) && Files.isReadable(r.path())) {
                    String content = FileUtils.readPreview(r.path(), maxBytes);
                    if (!content.isEmpty()) {
                        units.put(r.relativePath(), Grounder.FileUnit.of(r.relativePath(), content));
                    }
                }
            } catch (IOException ignored) {
                // skip unreadable files
            }
        }
        return units;
    }

    /**
     * Print grounding report to the CLI.
     */
    private void printGroundingReport(Grounder.GroundedAnswer result) {
        System.out.println(AnsiOutput.bold("  Grounding: " + result.status()));
        System.out.printf("  %d/%d claims grounded%n",
                result.grounded().size(), result.claims().size());

        if (!result.grounded().isEmpty()) {
            for (Grounder.ClaimVerdict v : result.grounded()) {
                String sha = v.sha256() != null && v.sha256().length() >= 12
                        ? v.sha256().substring(0, 12) : "";
                System.out.println(AnsiOutput.green("    + " + truncateClaim(v.claim())
                        + " [" + v.unitId() + " " + sha + "]"));
            }
        }
        if (!result.gaps().isEmpty()) {
            System.out.println(AnsiOutput.bold("  Gaps:"));
            for (Grounder.ClaimVerdict v : result.gaps()) {
                System.out.println(AnsiOutput.warning("    - " + truncateClaim(v.claim())
                        + " (" + v.reason() + ")"));
            }
        }
        System.out.println();
    }

    private static String truncateClaim(String claim) {
        if (claim.length() <= 80) return claim;
        return claim.substring(0, 77) + "...";
    }

    private Integer runInteractive() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load config and create AI client
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<AiClient> clientOpt = AiClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                String keyName = AiProvider.forConfig(config.getAi()).apiKeyName();
                AnsiOutput.printError("AI is not configured. Set ai.enabled=true in config and provide " + keyName + ".");
                AnsiOutput.printInfo("Edit .synthesis/config.yaml or set environment variable " + keyName + ".");
                return 1;
            }

            AiClient client = clientOpt.get();

            // Print welcome banner
            System.out.println();
            System.out.println(AnsiOutput.bold("╔════════════════════════════════════════════════════════════════╗"));
            System.out.println(AnsiOutput.bold("║ Synthesis Interactive Q&A                                      ║"));
            System.out.println(AnsiOutput.bold("║ Workspace: " + String.format("%-49s", config.getWorkspace().getName()) + "║"));
            System.out.println(AnsiOutput.bold("╚════════════════════════════════════════════════════════════════╝"));
            System.out.println();
            System.out.println("  Type your question or " + AnsiOutput.cyan("/help") + " for commands.");
            System.out.println("  Press " + AnsiOutput.cyan("Ctrl+D") + " or type " + AnsiOutput.cyan("/exit") + " to quit.");
            System.out.println();

            // Conversation history (keep last 10 Q&A pairs)
            List<ConversationTurn> history = new ArrayList<>();
            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print(AnsiOutput.bold("You: "));
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    // Ctrl+D pressed
                    System.out.println();
                    break;
                }

                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                // Handle commands
                if (input.startsWith("/")) {
                    if (input.equals("/exit") || input.equals("/quit")) {
                        break;
                    } else if (input.equals("/help")) {
                        showInteractiveHelp();
                        continue;
                    } else if (input.equals("/clear")) {
                        history.clear();
                        System.out.println(AnsiOutput.dim("  Conversation history cleared."));
                        System.out.println();
                        continue;
                    } else if (input.equals("/history")) {
                        showHistory(history);
                        continue;
                    } else {
                        System.out.println(AnsiOutput.warning("  Unknown command: " + input));
                        System.out.println("  Type /help for available commands.");
                        System.out.println();
                        continue;
                    }
                }

                // Process question
                try {
                    System.out.println(AnsiOutput.dim("  🤔 Thinking..."));
                    System.out.println();

                    // Search for relevant files
                    List<SearchResult> results;
                    try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                        results = index.search(input, contextFiles);
                    }

                    if (results.isEmpty()) {
                        System.out.println(AnsiOutput.warning("  No relevant files found for this question."));
                        System.out.println();
                        continue;
                    }

                    // Build context from files and conversation history
                    String fileContext = buildContext(results, workspaceRoot);
                    String conversationContext = buildConversationContext(history);

                    // Generate prompt with conversation history
                    String prompt;
                    if (history.isEmpty()) {
                        prompt = PromptTemplates.buildAskPrompt(input, fileContext);
                    } else {
                        prompt = PromptTemplates.buildAskPrompt(input, fileContext + "\n\nPrevious conversation:\n" + conversationContext);
                    }

                    // Ask Claude
                    String answer = client.generate(prompt, maxTokens);

                    // Store in history (keep last 10)
                    history.add(new ConversationTurn(input, answer));
                    if (history.size() > 10) {
                        history.remove(0);
                    }

                    // Display answer
                    System.out.println(AnsiOutput.cyan("Assistant:"));
                    for (String line : answer.split("\n")) {
                        System.out.println("  " + line);
                    }
                    System.out.println();

                } catch (Exception e) {
                    System.out.println(AnsiOutput.error("  Error: " + e.getMessage()));
                    System.out.println();
                }
            }

            System.out.println(AnsiOutput.dim("  Goodbye!"));
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Interactive session failed: " + e.getMessage());
            return 1;
        }
    }

    private void showInteractiveHelp() {
        System.out.println();
        System.out.println(AnsiOutput.bold("  Available Commands:"));
        System.out.println("    " + AnsiOutput.cyan("/help") + "     - Show this help message");
        System.out.println("    " + AnsiOutput.cyan("/exit") + "     - Exit interactive mode");
        System.out.println("    " + AnsiOutput.cyan("/quit") + "     - Exit interactive mode");
        System.out.println("    " + AnsiOutput.cyan("/clear") + "    - Clear conversation history");
        System.out.println("    " + AnsiOutput.cyan("/history") + "  - Show conversation history");
        System.out.println();
    }

    private void showHistory(List<ConversationTurn> history) {
        System.out.println();
        if (history.isEmpty()) {
            System.out.println(AnsiOutput.dim("  (No conversation history)"));
        } else {
            System.out.println(AnsiOutput.bold("  Conversation History:"));
            System.out.println();
            for (int i = 0; i < history.size(); i++) {
                ConversationTurn turn = history.get(i);
                System.out.println("  " + AnsiOutput.bold("[" + (i + 1) + "]") + " You: " + turn.question());
                System.out.println("      " + AnsiOutput.cyan("→") + " " +
                    (turn.answer().length() > 80 ? turn.answer().substring(0, 77) + "..." : turn.answer()));
                System.out.println();
            }
        }
        System.out.println();
    }

    private String buildConversationContext(List<ConversationTurn> history) {
        if (history.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        for (ConversationTurn turn : history) {
            context.append("Q: ").append(turn.question()).append("\n");
            context.append("A: ").append(turn.answer()).append("\n\n");
        }
        return context.toString();
    }

    private record ConversationTurn(String question, String answer) {}
}
