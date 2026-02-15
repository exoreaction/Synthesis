package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.EmbeddingService;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch.GroupedResults;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch.MultiSearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
            description = "Filter by file type: MARKDOWN, CODE, YAML, JSON, CONFIG, PDF, IMAGE, VIDEO, AUDIO"
    )
    private String fileType;

    @Option(
            names = {"--media-type"},
            description = "Filter by media type: presentation, document, spreadsheet, photo, screenshot, diagram"
    )
    private String mediaType;

    @Option(
            names = {"-l", "--limit"},
            description = "Maximum number of results (default: 20)",
            defaultValue = "20"
    )
    private int limit;

    @Option(
            names = {"--repo"},
            description = "Filter results to a specific repository (multi-repo workspaces)"
    )
    private String repo;

    @Option(
            names = {"--company"},
            description = "Filter results to a specific organization/company"
    )
    private String company;

    @Option(
            names = {"--client"},
            description = "Filter results to a specific client"
    )
    private String client;

    @Option(
            names = {"--all"},
            description = "Search across all discovered Synthesis workspaces",
            defaultValue = "false"
    )
    private boolean searchAll;

    @Option(
            names = {"--workspaces"},
            description = "Comma-separated list of workspace names or paths to search",
            split = ","
    )
    private List<String> workspaceNames;

    @Option(
            names = {"--semantic"},
            description = "Use semantic search (embedding-based) instead of keyword search",
            defaultValue = "false"
    )
    private boolean semantic;

    @Option(
            names = {"--similarity-threshold"},
            description = "Minimum similarity score for semantic search (0.0-1.0, default: 0.3)",
            defaultValue = "0.3"
    )
    private float similarityThreshold;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed result information",
            defaultValue = "false"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            // Multi-workspace search mode
            if (searchAll || (workspaceNames != null && !workspaceNames.isEmpty())) {
                return performMultiWorkspaceSearch();
            }

            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Semantic search mode
            if (semantic) {
                return performSemanticSearch(workspace);
            }

            // Keyword search
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                List<SearchResult> results;
                if (mediaType != null) {
                    results = index.searchWithMediaType(query, fileType, repo, mediaType,
                            company, client, limit);
                } else if (company != null || client != null) {
                    results = index.search(query, fileType, repo, company, client, limit);
                } else {
                    results = index.search(query, fileType, repo, limit);
                }

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

    /**
     * Performs search across multiple workspaces.
     */
    private int performMultiWorkspaceSearch() {
        List<Path> workspacePaths;

        if (searchAll) {
            workspacePaths = MultiWorkspaceSearch.discoverAllWorkspaces();
            if (workspacePaths.isEmpty()) {
                AnsiOutput.printError("No Synthesis workspaces found. Run 'synthesis init' first.");
                return 1;
            }
        } else {
            // Resolve workspace names/paths
            workspacePaths = resolveWorkspacePaths(workspaceNames);
            if (workspacePaths.isEmpty()) {
                AnsiOutput.printError("No valid workspaces found for: " + String.join(", ", workspaceNames));
                return 1;
            }
        }

        MultiWorkspaceSearch multiSearch = new MultiWorkspaceSearch(workspacePaths);
        MultiSearchResult result = multiSearch.search(query, fileType, limit);

        // Print results grouped by workspace
        System.out.println();
        System.out.printf("  %s results across %s workspaces for: %s  %s%n%n",
                AnsiOutput.bold(String.valueOf(result.totalResults())),
                AnsiOutput.bold(String.valueOf(result.groups().size())),
                AnsiOutput.bold(query),
                AnsiOutput.dim(String.format("(%.1fs)", result.totalTimeMs() / 1000.0)));

        for (GroupedResults group : result.groups()) {
            // Workspace header
            String wsLabel = AnsiOutput.bold(group.workspace().name());
            String wsPath = AnsiOutput.dim(" (" + group.workspace().path() + ")");

            if (group.hasError()) {
                System.out.printf("  %s%s %s%n", wsLabel, wsPath,
                        AnsiOutput.red("[error: " + group.error() + "]"));
                System.out.println();
                continue;
            }

            if (!group.hasResults()) {
                System.out.printf("  %s%s %s%n", wsLabel, wsPath,
                        AnsiOutput.dim("[no results]"));
                System.out.println();
                continue;
            }

            System.out.printf("  %s%s  %s results  %s%n",
                    wsLabel, wsPath,
                    AnsiOutput.cyan(String.valueOf(group.results().size())),
                    AnsiOutput.dim(String.format("%.1fs", group.searchTimeMs() / 1000.0)));

            for (int i = 0; i < group.results().size(); i++) {
                SearchResult sr = group.results().get(i);
                String typeColor = colorForType(sr.fileType());
                System.out.printf("    %s %s %s%n",
                        AnsiOutput.dim(String.format("%2d.", i + 1)),
                        typeColor,
                        AnsiOutput.bold(sr.relativePath()));

                if (!sr.summary().isEmpty()) {
                    String summaryText = sr.summary();
                    if (summaryText.length() > 100) {
                        summaryText = summaryText.substring(0, 100) + "...";
                    }
                    System.out.printf("       %s%n", AnsiOutput.dim(summaryText));
                }

                StringBuilder meta = new StringBuilder();
                meta.append(FileUtils.formatSize(sr.sizeBytes()));
                if (sr.language() != null) meta.append(" | ").append(sr.language());
                if (sr.fileType() != null) meta.append(" | ").append(sr.fileType());
                if (verbose) {
                    meta.append(String.format(" | score: %.2f", sr.score()));
                }
                System.out.printf("       %s%n", AnsiOutput.dim(meta.toString()));
            }
            System.out.println();
        }

        return 0;
    }

    /**
     * Resolves workspace names to actual paths.
     * Accepts both absolute paths and workspace names (matched against discovered workspaces).
     */
    private List<Path> resolveWorkspacePaths(List<String> names) {
        List<Path> resolved = new ArrayList<>();
        List<Path> allWorkspaces = MultiWorkspaceSearch.discoverAllWorkspaces();

        for (String name : names) {
            // Try as absolute path first
            Path asPath = Path.of(name);
            if (asPath.isAbsolute() && Files.isDirectory(asPath.resolve(".synthesis"))) {
                resolved.add(asPath.toAbsolutePath().normalize());
                continue;
            }

            // Try matching by workspace name or directory name
            boolean found = false;
            for (Path wsPath : allWorkspaces) {
                String wsName = wsPath.getFileName() != null ? wsPath.getFileName().toString() : "";
                if (wsName.equalsIgnoreCase(name) || wsPath.toString().contains(name)) {
                    resolved.add(wsPath);
                    found = true;
                    break;
                }
            }

            if (!found) {
                AnsiOutput.printWarning("Workspace not found: " + name);
            }
        }

        return resolved;
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

    /**
     * Performs semantic search using embedding similarity.
     */
    private int performSemanticSearch(WorkspaceManager workspace) {
        try {
            EmbeddingService embeddingService = EmbeddingService.create();
            AnsiOutput.printInfo("Semantic search using " + embeddingService.getProvider() + " embeddings");
            System.out.println();

            // Generate query embedding
            float[] queryEmbedding = embeddingService.embed(query);

            // Get all files and compute similarity
            List<SearchResult> allFiles;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                allFiles = index.listAll(fileType, limit * 5);
            }

            // Score each file by embedding similarity
            record ScoredResult(SearchResult result, float similarity) {}
            List<ScoredResult> scored = new ArrayList<>();

            for (SearchResult file : allFiles) {
                try {
                    if (!Files.exists(file.path()) || !Files.isReadable(file.path())) continue;

                    // Build file representation for embedding
                    String fileText = file.summary() + " " + file.headings() + " " + file.fileName();
                    if (!file.structure().isEmpty()) fileText += " " + file.structure();

                    float[] fileEmbedding = embeddingService.embed(fileText);
                    float similarity = EmbeddingService.cosineSimilarity(queryEmbedding, fileEmbedding);

                    if (similarity >= similarityThreshold) {
                        scored.add(new ScoredResult(file, similarity));
                    }
                } catch (Exception e) {
                    // Skip files that fail
                }
            }

            // Sort by similarity (descending)
            scored.sort((a, b) -> Float.compare(b.similarity(), a.similarity()));

            // Limit results
            if (scored.size() > limit) {
                scored = scored.subList(0, limit);
            }

            if (scored.isEmpty()) {
                System.out.println("  No semantic matches found for: " + AnsiOutput.bold(query));
                System.out.println("  (threshold: " + similarityThreshold + ")");
                return 0;
            }

            System.out.printf("  %s semantic results for: %s%n%n",
                    AnsiOutput.bold(String.valueOf(scored.size())),
                    AnsiOutput.bold(query));

            for (int i = 0; i < scored.size(); i++) {
                ScoredResult sr = scored.get(i);
                SearchResult result = sr.result();

                String typeColor = colorForType(result.fileType());
                System.out.printf("  %s %s %s  %s%n",
                        AnsiOutput.dim(String.format("%2d.", i + 1)),
                        typeColor,
                        AnsiOutput.bold(result.relativePath()),
                        AnsiOutput.cyan(String.format("%.1f%%", sr.similarity() * 100)));

                if (!result.summary().isEmpty()) {
                    String summaryText = result.summary();
                    if (summaryText.length() > 100) {
                        summaryText = summaryText.substring(0, 100) + "...";
                    }
                    System.out.printf("     %s%n", AnsiOutput.dim(summaryText));
                }
                System.out.println();
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Semantic search failed: " + e.getMessage());
            return 1;
        }
    }

    private String colorForType(String fileType) {
        if (fileType == null) return AnsiOutput.dim("[???]");
        return switch (fileType) {
            case "MARKDOWN" -> AnsiOutput.green("[MD]  ");
            case "CODE"     -> AnsiOutput.blue("[CODE]");
            case "YAML"     -> AnsiOutput.magenta("[YAML]");
            case "JSON"     -> AnsiOutput.cyan("[JSON]");
            case "CONFIG"   -> AnsiOutput.yellow("[CONF]");
            case "PDF"      -> AnsiOutput.red("[PDF] ");
            case "IMAGE"    -> AnsiOutput.magenta("[IMG] ");
            case "VIDEO"    -> AnsiOutput.cyan("[VID] ");
            case "AUDIO"    -> AnsiOutput.yellow("[AUD] ");
            case "DOCUMENT" -> AnsiOutput.red("[DOC] ");
            default         -> AnsiOutput.dim("[" + fileType + "]");
        };
    }
}
