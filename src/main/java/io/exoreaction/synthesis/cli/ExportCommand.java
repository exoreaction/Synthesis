package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
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
 *   synthesis export                        # Export as Markdown to stdout
 *   synthesis export --format json          # Export as JSON to stdout
 *   synthesis export --output overview.md   # Export to file
 *   synthesis export --type CODE            # Export only code files
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
            description = "Output format: markdown, json (default: markdown)",
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
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
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
                default -> {
                    AnsiOutput.printError("Unknown format: " + format + ". Use 'markdown' or 'json'.");
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
}
