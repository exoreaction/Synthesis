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
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Exports the workspace index as JSON or Markdown.
 *
 * <p>Useful for:
 * <ul>
 *   <li>Sharing a workspace overview with team members</li>
 *   <li>Generating AI context (inject into Claude Code prompts)</li>
 *   <li>Creating documentation from indexed content</li>
 *   <li>Auditing what's in the index</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   synthesis export                                    # Export as Markdown to stdout
 *   synthesis export --format json                      # Export as JSON to stdout
 *   synthesis export --output overview.md               # Export to file
 *   synthesis export --type CODE                        # Export only code files
 *   synthesis export --format architecture-doc          # AI-generated architecture doc
 *   synthesis export --format onboarding-guide          # AI-generated onboarding guide
 * </pre>
 */
@Command(
        name = "export",
        description = "Export workspace index as JSON or Markdown",
        mixinStandardHelpOptions = true
)
public class ExportCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"-f", "--format"},
            description = "Output format: markdown, json, architecture-doc, onboarding-guide (default: markdown)",
            defaultValue = "markdown"
    )
    private String format;

    @Option(
            names = {"-o", "--output"},
            description = "Output file (default: stdout)"
    )
    private Path output;

    @Option(
            names = {"--type"},
            description = "Filter by file type (e.g., CODE, MARKDOWN, YAML, PDF)"
    )
    private String typeFilter;

    @Option(
            names = {"--limit"},
            description = "Maximum number of entries to export",
            defaultValue = "1000"
    )
    private int limit;

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

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            // Query the index for all documents
            List<SearchResult> results;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                results = index.listAll(typeFilter, limit);
            }

            if (results.isEmpty()) {
                AnsiOutput.printWarning("No documents found in index. Run 'synthesis scan' first.");
                return 1;
            }

            // Generate output
            String content = switch (format.toLowerCase()) {
                case "json" -> exportAsJson(config, results);
                case "markdown", "md" -> exportAsMarkdown(config, results, workspaceRoot);
                case "architecture-doc", "architecture" -> exportAsArchitectureDoc(config, results, workspaceRoot);
                case "onboarding-guide", "onboarding" -> exportAsOnboardingGuide(config, results, workspaceRoot);
                default -> {
                    AnsiOutput.printError("Unknown format: " + format +
                            ". Use 'markdown', 'json', 'architecture-doc', or 'onboarding-guide'.");
                    yield null;
                }
            };

            if (content == null) return 1;

            // Write output
            if (output != null) {
                Files.writeString(output, content);
                AnsiOutput.printSuccess("Exported " + results.size() + " entries to " + output);
            } else {
                System.out.print(content);
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Export failed: " + e.getMessage());
            return 1;
        }
    }

    private String exportAsJson(SynthesisConfig config, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"workspace\": \"").append(escapeJson(config.getWorkspace().getName())).append("\",\n");
        sb.append("  \"type\": \"").append(escapeJson(config.getWorkspace().getType())).append("\",\n");
        sb.append("  \"totalFiles\": ").append(results.size()).append(",\n");
        sb.append("  \"files\": [\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("    {\n");
            sb.append("      \"path\": \"").append(escapeJson(r.relativePath())).append("\",\n");
            sb.append("      \"fileName\": \"").append(escapeJson(r.fileName())).append("\",\n");
            sb.append("      \"fileType\": \"").append(r.fileType() != null ? escapeJson(r.fileType()) : "").append("\",\n");
            sb.append("      \"language\": ").append(r.language() != null ? "\"" + escapeJson(r.language()) + "\"" : "null").append(",\n");
            sb.append("      \"sizeBytes\": ").append(r.sizeBytes()).append(",\n");
            sb.append("      \"summary\": \"").append(escapeJson(r.summary())).append("\"\n");
            sb.append("    }");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String exportAsMarkdown(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(config.getWorkspace().getName()).append(" - Workspace Index\n\n");
        sb.append("**Type:** ").append(config.getWorkspace().getType()).append("\n");
        sb.append("**Root:** ").append(workspaceRoot).append("\n");
        sb.append("**Total files:** ").append(results.size()).append("\n\n");

        // Group by file type
        Map<String, List<SearchResult>> byType = results.stream()
                .collect(Collectors.groupingBy(
                        r -> r.fileType() != null ? r.fileType() : "OTHER",
                        TreeMap::new,
                        Collectors.toList()
                ));

        // Type summary table
        sb.append("## File Types\n\n");
        sb.append("| Type | Count |\n");
        sb.append("|------|-------|\n");
        for (var entry : byType.entrySet()) {
            sb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue().size()).append(" |\n");
        }
        sb.append("\n");

        // Files grouped by type
        for (var entry : byType.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");

            for (SearchResult r : entry.getValue()) {
                sb.append("- **").append(r.relativePath()).append("**");
                if (r.language() != null) {
                    sb.append(" (").append(r.language()).append(")");
                }
                sb.append(" - ").append(FileUtils.formatSize(r.sizeBytes()));
                if (!r.summary().isEmpty()) {
                    sb.append("\n  > ").append(truncate(r.summary(), 120));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Exports as AI-generated architecture documentation.
     * Falls back to basic markdown if AI is unavailable.
     */
    private String exportAsArchitectureDoc(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        String fileIndex = buildFileIndex(results);

        Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
        if (clientOpt.isEmpty()) {
            AnsiOutput.printWarning("AI not configured. Generating basic architecture overview.");
            return exportAsBasicArchitecture(config, results, workspaceRoot, fileIndex);
        }

        AnsiOutput.printInfo("Generating architecture documentation with AI...");

        try {
            String prompt = PromptTemplates.buildArchitectureDocPrompt(
                    config.getWorkspace().getName(),
                    config.getWorkspace().getType(),
                    workspaceRoot.toString(),
                    fileIndex
            );
            return clientOpt.get().generate(prompt, 4000);
        } catch (Exception e) {
            AnsiOutput.printWarning("AI generation failed: " + e.getMessage() + ". Using basic format.");
            return exportAsBasicArchitecture(config, results, workspaceRoot, fileIndex);
        }
    }

    /**
     * Exports as AI-generated onboarding guide.
     * Falls back to basic markdown if AI is unavailable.
     */
    private String exportAsOnboardingGuide(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        String fileIndex = buildFileIndex(results);

        Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
        if (clientOpt.isEmpty()) {
            AnsiOutput.printWarning("AI not configured. Generating basic onboarding guide.");
            return exportAsBasicOnboarding(config, results, workspaceRoot, fileIndex);
        }

        AnsiOutput.printInfo("Generating onboarding guide with AI...");

        try {
            String prompt = PromptTemplates.buildOnboardingGuidePrompt(
                    config.getWorkspace().getName(),
                    config.getWorkspace().getType(),
                    workspaceRoot.toString(),
                    fileIndex
            );
            return clientOpt.get().generate(prompt, 4000);
        } catch (Exception e) {
            AnsiOutput.printWarning("AI generation failed: " + e.getMessage() + ". Using basic format.");
            return exportAsBasicOnboarding(config, results, workspaceRoot, fileIndex);
        }
    }

    /**
     * Builds a compact file index string for AI prompts.
     */
    String buildFileIndex(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (SearchResult r : results) {
            sb.append(r.relativePath());
            if (r.fileType() != null) sb.append(" [").append(r.fileType()).append("]");
            if (r.language() != null) sb.append(" (").append(r.language()).append(")");
            sb.append(" ").append(FileUtils.formatSize(r.sizeBytes()));
            if (!r.summary().isEmpty()) {
                sb.append(" - ").append(truncate(r.summary(), 80));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Basic architecture doc fallback (no AI).
     */
    private String exportAsBasicArchitecture(SynthesisConfig config, List<SearchResult> results,
                                              Path workspaceRoot, String fileIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Architecture: ").append(config.getWorkspace().getName()).append("\n\n");
        sb.append("**Type:** ").append(config.getWorkspace().getType()).append("\n");
        sb.append("**Root:** ").append(workspaceRoot).append("\n");
        sb.append("**Total files:** ").append(results.size()).append("\n\n");

        // Technology stack
        Map<String, Long> languages = results.stream()
                .filter(r -> r.language() != null)
                .collect(Collectors.groupingBy(SearchResult::language, Collectors.counting()));
        if (!languages.isEmpty()) {
            sb.append("## Technology Stack\n\n");
            languages.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append("- **").append(e.getKey()).append("**: ")
                            .append(e.getValue()).append(" files\n"));
            sb.append("\n");
        }

        // Directory structure
        Map<String, Long> directories = results.stream()
                .collect(Collectors.groupingBy(r -> {
                    String path = r.relativePath();
                    int sep = path.indexOf('/');
                    if (sep < 0) sep = path.indexOf('\\');
                    return sep > 0 ? path.substring(0, sep) : "(root)";
                }, Collectors.counting()));

        sb.append("## Directory Structure\n\n");
        directories.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("- **").append(e.getKey()).append("/**: ")
                        .append(e.getValue()).append(" files\n"));
        sb.append("\n");

        // Key files
        sb.append("## Key Files\n\n");
        results.stream()
                .filter(r -> r.fileName().equalsIgnoreCase("README.md") ||
                        r.fileName().equalsIgnoreCase("pom.xml") ||
                        r.fileName().equalsIgnoreCase("package.json") ||
                        r.fileName().equalsIgnoreCase("build.gradle") ||
                        r.fileName().equalsIgnoreCase("Makefile") ||
                        r.fileName().equalsIgnoreCase("Dockerfile"))
                .forEach(r -> {
                    sb.append("- `").append(r.relativePath()).append("`");
                    if (!r.summary().isEmpty()) {
                        sb.append(" - ").append(truncate(r.summary(), 80));
                    }
                    sb.append("\n");
                });
        sb.append("\n");

        sb.append("---\n");
        sb.append("*Note: This is a basic architecture overview. Enable AI (set ai.enabled=true " +
                "and ANTHROPIC_API_KEY) for a detailed narrative document.*\n");

        return sb.toString();
    }

    /**
     * Basic onboarding guide fallback (no AI).
     */
    private String exportAsBasicOnboarding(SynthesisConfig config, List<SearchResult> results,
                                            Path workspaceRoot, String fileIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Onboarding Guide: ").append(config.getWorkspace().getName()).append("\n\n");
        sb.append("Welcome to the ").append(config.getWorkspace().getName()).append(" workspace.\n\n");

        // Getting started
        sb.append("## Getting Started\n\n");
        sb.append("1. Explore the project root: `").append(workspaceRoot).append("`\n");
        sb.append("2. Read key documentation files listed below\n");
        sb.append("3. Run `synthesis search <topic>` to find relevant files\n\n");

        // README files
        List<SearchResult> readmes = results.stream()
                .filter(r -> r.fileName().equalsIgnoreCase("README.md"))
                .toList();
        if (!readmes.isEmpty()) {
            sb.append("## Start Reading Here\n\n");
            for (SearchResult r : readmes) {
                sb.append("- `").append(r.relativePath()).append("`");
                if (!r.summary().isEmpty()) {
                    sb.append(" - ").append(truncate(r.summary(), 80));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Project layout
        sb.append("## Project Layout\n\n");
        sb.append("**Total:** ").append(results.size()).append(" files\n\n");

        Map<String, Long> byType = results.stream()
                .filter(r -> r.fileType() != null)
                .collect(Collectors.groupingBy(SearchResult::fileType, Collectors.counting()));
        byType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                        .append(e.getValue()).append(" files\n"));
        sb.append("\n");

        // Configuration files
        List<SearchResult> configs = results.stream()
                .filter(r -> "CONFIG".equals(r.fileType()) || "YAML".equals(r.fileType()) || "JSON".equals(r.fileType()))
                .limit(10)
                .toList();
        if (!configs.isEmpty()) {
            sb.append("## Configuration Files\n\n");
            for (SearchResult r : configs) {
                sb.append("- `").append(r.relativePath()).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("*Note: This is a basic onboarding guide. Enable AI (set ai.enabled=true " +
                "and ANTHROPIC_API_KEY) for a detailed narrative guide.*\n");

        return sb.toString();
    }
}
