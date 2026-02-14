package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            description = "Your question about the workspace"
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

    @Override
    public Integer call() {
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

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Ask failed: " + e.getMessage());
            return 1;
        }
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
}
