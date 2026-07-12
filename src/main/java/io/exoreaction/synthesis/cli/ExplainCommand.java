package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.AiProvider;
import io.exoreaction.synthesis.ai.CodeExplainer;
import io.exoreaction.synthesis.ai.CodeExplainer.Depth;
import io.exoreaction.synthesis.ai.CodeExplainer.ExplanationResult;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.RelationService;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * AI-powered explanation of files, modules, or architectural patterns.
 *
 * <p>Generates comprehensive natural-language explanations that help developers
 * quickly understand unfamiliar code. Uses the Synthesis index as context to
 * ground explanations in actual workspace structure.
 *
 * <p>Usage:
 * <pre>
 *   synthesis explain --file src/auth/Login.java          # Explain a file
 *   synthesis explain --module src/auth/                   # Explain a module
 *   synthesis explain --pattern "authentication"           # Explain a concept
 *   synthesis explain --file Login.java --depth deep       # Deep dive
 *   synthesis explain --file Login.java --format json      # Machine-readable
 * </pre>
 *
 * @see CodeExplainer
 */
@Command(
        name = "explain",
        description = "AI-powered explanation of files, modules, or architectural patterns",
        mixinStandardHelpOptions = true
)
public class ExplainCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"-f", "--file"},
            description = "File to explain (path or filename)"
    )
    private Path filePath;

    @Option(
            names = {"-m", "--module"},
            description = "Module (directory) to explain"
    )
    private Path modulePath;

    @Option(
            names = {"-p", "--pattern"},
            description = "Architectural pattern or concept to explain (e.g., 'authentication')"
    )
    private String pattern;

    @Option(
            names = {"--depth"},
            description = "Explanation depth: brief, standard, deep (default: standard)",
            defaultValue = "standard"
    )
    private String depth;

    @Option(
            names = {"--format"},
            description = "Output format: text, json, markdown (default: text)",
            defaultValue = "text"
    )
    private String format;

    private final RelationService relationService = new RelationService();

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

            // Check that AI is available
            if (SynthesisApp.isAirGapped()) {
                AnsiOutput.printError("synthesis explain requires AI. Not available in " +
                        SynthesisApp.getEdition() + " edition.");
                return 1;
            }

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            Optional<AiClient> clientOpt = AiClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                AnsiOutput.printError("AI not configured. Set "
                        + AiProvider.forConfig(config.getAi()).apiKeyName() + " to enable explain.");
                return 1;
            }

            // Validate exactly one mode is specified
            int modeCount = (filePath != null ? 1 : 0) +
                    (modulePath != null ? 1 : 0) +
                    (pattern != null ? 1 : 0);
            if (modeCount == 0) {
                AnsiOutput.printError("Specify one of --file, --module, or --pattern.");
                return 1;
            }
            if (modeCount > 1) {
                AnsiOutput.printError("Specify only one of --file, --module, or --pattern.");
                return 1;
            }

            // Parse depth
            Depth depthLevel = switch (depth.toLowerCase()) {
                case "brief" -> Depth.BRIEF;
                case "deep" -> Depth.DEEP;
                default -> Depth.STANDARD;
            };

            CodeExplainer explainer = new CodeExplainer(clientOpt.get(), config.getAi().getMaxTokens());
            ExplanationResult result;

            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                if (filePath != null) {
                    // Resolve file path
                    Path resolved = resolveFilePath(filePath, workspaceRoot, index);
                    if (resolved == null) {
                        AnsiOutput.printError("File not found: " + filePath);
                        AnsiOutput.printInfo("Try 'synthesis search " + filePath.getFileName()
                                + "' to find it.");
                        return 1;
                    }

                    AnsiOutput.printInfo("Explaining: " + workspaceRoot.relativize(resolved));
                    System.out.println();

                    result = explainer.explainFile(resolved, index, workspaceRoot, depthLevel);

                } else if (modulePath != null) {
                    Path resolved = modulePath.isAbsolute() ? modulePath :
                            workspaceRoot.resolve(modulePath);
                    if (!Files.isDirectory(resolved)) {
                        AnsiOutput.printError("Directory not found: " + modulePath);
                        return 1;
                    }

                    AnsiOutput.printInfo("Explaining module: " + workspaceRoot.relativize(resolved));
                    System.out.println();

                    result = explainer.explainModule(resolved, index, workspaceRoot, depthLevel);

                } else {
                    AnsiOutput.printInfo("Explaining pattern: " + pattern);
                    System.out.println();

                    result = explainer.explainPattern(pattern, index, workspaceRoot, depthLevel);
                }
            }

            // Output result
            if ("json".equals(format)) {
                System.out.printf("""
                        {
                          "target": "%s",
                          "mode": "%s",
                          "explanation": %s,
                          "contextDocuments": %d,
                          "durationMs": %d
                        }
                        """,
                        result.target(),
                        result.mode(),
                        escapeJson(result.explanation()),
                        result.contextDocuments(),
                        result.durationMs());
            } else {
                System.out.println(result.explanation());
                System.out.println();
                System.out.printf("  (context: %d documents, %.1fs)%n",
                        result.contextDocuments(), result.durationMs() / 1000.0);
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Explain failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Resolves a file path — handles absolute paths, workspace-relative paths,
     * and bare filenames by searching the index.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Absolute path that exists on disk</li>
     *   <li>Path relative to workspace root</li>
     *   <li>Filename search in the Synthesis index (e.g. {@code StagingCommand.java}
     *       resolves to {@code src/main/java/.../StagingCommand.java})</li>
     * </ol>
     */
    Path resolveFilePath(Path input, Path workspaceRoot, SearchIndex index) {
        // Try as absolute
        if (input.isAbsolute() && Files.exists(input)) {
            return input;
        }
        // Try relative to workspace
        Path resolved = workspaceRoot.resolve(input);
        if (Files.exists(resolved)) {
            return resolved;
        }
        // Fall back to filename search in the index
        String query = input.getFileName().toString();
        try {
            // #431 bug class: the argument is a filename, not Lucene query syntax --
            // special characters (e.g. Next.js "[id].ts") corrupt the classic query
            // parser unless escaped. Cap raised 10 -> 1000 (#449) so ambiguity
            // detection below sees the full candidate set, not a ranked subset.
            List<SearchResult> results = index.searchLiteral(query, 1000);
            // #448: same three-tier resolution as before (exact-path-or-suffix ->
            // filename -> top result), now via RelationService so explain shares
            // relate/impact's ambiguity warning (#430) instead of silently
            // explaining an arbitrary same-named file.
            SearchResult match = relationService.findBestMatch(results, query);
            if (match != null) {
                warnIfAmbiguous(results, query, match);
                return match.path();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Warns on stderr when {@code chosen} was resolved from an ambiguous bare filename (#448),
     * so explaining the wrong file isn't silent. Writes to stderr (not AnsiOutput.printWarning's
     * stdout) so it never corrupts {@code --format json} output.
     */
    private void warnIfAmbiguous(List<SearchResult> results, String targetFile, SearchResult chosen) {
        String warning = relationService.formatAmbiguityWarning(results, targetFile, chosen);
        if (warning != null) System.err.println(AnsiOutput.warning("  [WARN] ") + warning);
    }

    private String escapeJson(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
