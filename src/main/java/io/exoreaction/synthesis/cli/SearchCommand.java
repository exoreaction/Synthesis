package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Searches the workspace index for matching files.
 *
 * <p>Supports full Lucene query syntax: simple terms, exact phrases,
 * boolean operators, wildcards, and field-specific queries.
 *
 * <p>Usage: {@code synthesis search "testing strategy" [--type CODE] [--limit 10]}
 */
@Command(
        name = "search",
        description = "Search the workspace index",
        mixinStandardHelpOptions = true
)
public class SearchCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "Search query (supports Lucene syntax)"
    )
    private String query;

    @Option(
            names = {"-t", "--type"},
            description = "Filter by file type: MARKDOWN, CODE, YAML, JSON, CONFIG, PDF"
    )
    private String fileType;

    @Option(
            names = {"-l", "--limit"},
            description = "Maximum number of results (default: 20)",
            defaultValue = "20"
    )
    private int limit;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed result information",
            defaultValue = "false"
    )
    private boolean verbose;

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

            // Search
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                List<SearchResult> results = index.search(query, fileType, limit);

                if (results.isEmpty()) {
                    System.out.println();
                    System.out.println("  No results found for: " + AnsiOutput.bold(query));
                    if (fileType != null) {
                        System.out.println("  (filtered by type: " + fileType + ")");
                    }
                    System.out.println();
                    System.out.println("  Tips:");
                    System.out.println("    - Try broader search terms");
                    System.out.println("    - Use wildcards: " + AnsiOutput.cyan("test*"));
                    System.out.println("    - Remove type filter");
                    System.out.println("    - Run " + AnsiOutput.cyan("synthesis scan") + " if index is stale");
                    System.out.println();
                    return 0;
                }

                printResults(results, query);
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Search failed: " + e.getMessage());
            return 1;
        }
    }

    private void printResults(List<SearchResult> results, String query) {
        System.out.println();
        System.out.printf("  %s results for: %s%n%n",
                AnsiOutput.bold(String.valueOf(results.size())),
                AnsiOutput.bold(query));

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);

            // Result number and file name with color based on type
            String typeColor = colorForType(result.fileType());
            System.out.printf("  %s %s %s%n",
                    AnsiOutput.dim(String.format("%2d.", i + 1)),
                    typeColor,
                    AnsiOutput.bold(result.relativePath()));

            // Summary line
            if (!result.summary().isEmpty()) {
                String summaryText = result.summary();
                if (summaryText.length() > 100) {
                    summaryText = summaryText.substring(0, 100) + "...";
                }
                System.out.printf("     %s%n", AnsiOutput.dim(summaryText));
            }

            // Metadata line
            StringBuilder meta = new StringBuilder();
            meta.append(FileUtils.formatSize(result.sizeBytes()));
            if (result.language() != null) {
                meta.append(" | ").append(result.language());
            }
            if (result.fileType() != null) {
                meta.append(" | ").append(result.fileType());
            }
            if (verbose) {
                meta.append(String.format(" | score: %.2f", result.score()));
                if (!result.headings().isEmpty()) {
                    String headings = result.headings();
                    if (headings.length() > 80) headings = headings.substring(0, 80) + "...";
                    meta.append(" | headings: ").append(headings);
                }
                if (!result.structure().isEmpty()) {
                    meta.append(" | ").append(result.structure());
                }
            }
            System.out.printf("     %s%n", AnsiOutput.dim(meta.toString()));
            System.out.println();
        }
    }

    private String colorForType(String fileType) {
        if (fileType == null) return AnsiOutput.dim("[???]");
        return switch (fileType) {
            case "MARKDOWN" -> AnsiOutput.green("[MD] ");
            case "CODE"     -> AnsiOutput.blue("[CODE]");
            case "YAML"     -> AnsiOutput.magenta("[YAML]");
            case "JSON"     -> AnsiOutput.cyan("[JSON]");
            case "CONFIG"   -> AnsiOutput.yellow("[CONF]");
            case "PDF"      -> AnsiOutput.red("[PDF] ");
            default         -> AnsiOutput.dim("[" + fileType + "]");
        };
    }
}
