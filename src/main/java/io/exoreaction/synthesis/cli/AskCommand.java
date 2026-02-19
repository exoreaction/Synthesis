package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.ai.DirectedSynthesisEngine;
import io.exoreaction.synthesis.ai.PromptTemplates;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
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
            Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                AnsiOutput.printError("AI is not configured. Set ai.enabled=true in config and provide ANTHROPIC_API_KEY.");
                AnsiOutput.printInfo("Edit .synthesis/config.yaml or set environment variable ANTHROPIC_API_KEY.");
                return 1;
            }

            ClaudeClient client = clientOpt.get();

            // Search for relevant files
            if (verbose) {
                AnsiOutput.printInfo("Searching workspace for relevant files...");
            }

            List<SearchResult> results;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                results = index.search(question, contextFiles);
            }

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

            // Generate prompt and ask Claude
            String prompt = PromptTemplates.buildAskPrompt(question, context);

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
     * Builds a context string from search results by reading file content.
     * Includes file path, language, and a preview of the content with line numbers.
     */
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
            Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                AnsiOutput.printError("AI is not configured. Set ai.enabled=true in config and provide ANTHROPIC_API_KEY.");
                AnsiOutput.printInfo("Edit .synthesis/config.yaml or set environment variable ANTHROPIC_API_KEY.");
                return 1;
            }

            ClaudeClient client = clientOpt.get();

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
                    try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
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
