package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch.WorkspaceEntry;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Finds which workspace(s) contain a file or pattern.
 *
 * <p>Searches across all discovered Synthesis workspaces to locate files.
 * Useful for determining which repository contains a specific file,
 * especially in multi-repository development environments.
 *
 * <p>Usage:
 * <pre>
 *   synthesis which MetricsCollector.java
 *   synthesis which --pattern "*.md" README
 *   synthesis which --verbose SynthesisConfig
 * </pre>
 */
@Command(
        name = "which",
        description = "Find which workspace(s) contain a file or pattern",
        mixinStandardHelpOptions = true
)
public class WhichCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "File name or search term to locate"
    )
    private String filename;

    @Option(
            names = {"--pattern", "-p"},
            description = "Treat the filename as a glob pattern (e.g., *.md)",
            defaultValue = "false"
    )
    private boolean usePattern;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show all matching file paths, not just workspace names",
            defaultValue = "false"
    )
    private boolean verbose;

    @Option(
            names = {"--type"},
            description = "Filter workspaces by type: source-code, documents, mixed"
    )
    private String typeFilter;

    @Option(
            names = {"--format"},
            description = "Output format: table (default) or json",
            defaultValue = "table"
    )
    private String format;

    @Override
    public Integer call() {
        try {
            // Discover all workspaces
            List<Path> allWorkspaces = MultiWorkspaceSearch.discoverAllWorkspaces();

            if (allWorkspaces.isEmpty()) {
                AnsiOutput.printError("No Synthesis workspaces found. Run 'synthesis init' first.");
                return 1;
            }

            // Filter by type if specified
            if (typeFilter != null) {
                var type = io.exoreaction.synthesis.workspace.WorkspaceType.fromConfigValue(typeFilter);
                allWorkspaces = MultiWorkspaceSearch.discoverWorkspacesByType(type);
                if (allWorkspaces.isEmpty()) {
                    AnsiOutput.printError("No workspaces found with type: " + typeFilter);
                    return 1;
                }
            }

            MultiWorkspaceSearch search = new MultiWorkspaceSearch(allWorkspaces);
            Map<WorkspaceEntry, List<String>> results = search.which(filename, usePattern);

            if (results.isEmpty()) {
                System.out.println();
                System.out.println("  No workspaces contain: " + AnsiOutput.bold(filename));
                System.out.println();
                System.out.println("  Searched " + allWorkspaces.size() + " workspace(s).");
                System.out.println("  Tips:");
                System.out.println("    - Check spelling and case");
                System.out.println("    - Use --pattern for glob matching");
                System.out.println("    - Run " + AnsiOutput.cyan("synthesis scan") + " on workspaces to update indices");
                System.out.println();
                return 0;
            }

            if ("json".equalsIgnoreCase(format)) {
                printJson(results);
            } else {
                printTable(results);
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Which failed: " + e.getMessage());
            return 1;
        }
    }

    private void printTable(Map<WorkspaceEntry, List<String>> results) {
        System.out.println();
        System.out.printf("  Found %s in %s workspace(s):%n%n",
                AnsiOutput.bold(filename),
                AnsiOutput.bold(String.valueOf(results.size())));

        for (Map.Entry<WorkspaceEntry, List<String>> entry : results.entrySet()) {
            WorkspaceEntry ws = entry.getKey();
            List<String> paths = entry.getValue();

            // Workspace name with type indicator
            String typeLabel = switch (ws.type()) {
                case SOURCE_CODE -> AnsiOutput.blue("[source]");
                case DOCUMENTS -> AnsiOutput.green("[docs]  ");
                case MIXED -> AnsiOutput.yellow("[mixed] ");
            };

            System.out.printf("  %s %s  %s%n",
                    typeLabel,
                    AnsiOutput.bold(ws.name()),
                    AnsiOutput.dim("(" + ws.path() + ")"));

            if (verbose) {
                for (String path : paths) {
                    System.out.println("      " + AnsiOutput.cyan(path));
                }
            } else {
                System.out.printf("      %s matching file(s)%n",
                        AnsiOutput.cyan(String.valueOf(paths.size())));
            }
            System.out.println();
        }

        // Summary
        int totalFiles = results.values().stream().mapToInt(List::size).sum();
        System.out.printf("  %s: %d file(s) across %d workspace(s)%n%n",
                AnsiOutput.bold("Total"),
                totalFiles,
                results.size());
    }

    private void printJson(Map<WorkspaceEntry, List<String>> results) {
        System.out.println("{");
        System.out.println("  \"query\": \"" + filename + "\",");
        System.out.println("  \"pattern\": " + usePattern + ",");
        System.out.println("  \"results\": [");

        int wsCount = 0;
        for (Map.Entry<WorkspaceEntry, List<String>> entry : results.entrySet()) {
            WorkspaceEntry ws = entry.getKey();
            List<String> paths = entry.getValue();

            System.out.println("    {");
            System.out.println("      \"workspace\": \"" + ws.name() + "\",");
            System.out.println("      \"path\": \"" + ws.path() + "\",");
            System.out.println("      \"type\": \"" + ws.type().getConfigValue() + "\",");
            System.out.println("      \"matchCount\": " + paths.size() + ",");
            System.out.println("      \"matches\": [");

            for (int i = 0; i < paths.size(); i++) {
                System.out.print("        \"" + paths.get(i) + "\"");
                System.out.println(i < paths.size() - 1 ? "," : "");
            }

            System.out.println("      ]");
            System.out.print("    }");
            wsCount++;
            System.out.println(wsCount < results.size() ? "," : "");
        }

        System.out.println("  ]");
        System.out.println("}");
    }
}
