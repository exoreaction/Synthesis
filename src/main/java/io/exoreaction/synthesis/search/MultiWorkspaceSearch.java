package io.exoreaction.synthesis.search;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.workspace.WorkspaceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Searches across multiple Synthesis workspaces in parallel.
 *
 * <p>Used by:
 * <ul>
 *   <li>SearchCommand with --all or --workspaces flags</li>
 *   <li>Unified MCP server with --workspaces flag</li>
 *   <li>WhichCommand for locating files across workspaces</li>
 * </ul>
 *
 * <p>Results are grouped by workspace and include the workspace name
 * and path as metadata for display.
 */
public class MultiWorkspaceSearch {

    private static final Logger LOG = Logger.getLogger(MultiWorkspaceSearch.class.getName());

    private final List<WorkspaceEntry> workspaces;

    /**
     * A workspace entry with its path and loaded metadata.
     */
    public record WorkspaceEntry(
            Path path,
            String name,
            WorkspaceType type,
            String primaryLanguage,
            String company
    ) {}

    /**
     * Search results grouped by workspace.
     */
    public record GroupedResults(
            WorkspaceEntry workspace,
            List<SearchResult> results,
            long searchTimeMs,
            String error
    ) {
        public boolean hasError() {
            return error != null;
        }

        public boolean hasResults() {
            return results != null && !results.isEmpty();
        }
    }

    /**
     * Aggregate results across all workspaces.
     */
    public record MultiSearchResult(
            List<GroupedResults> groups,
            int totalResults,
            long totalTimeMs
    ) {}

    public MultiWorkspaceSearch(List<Path> workspacePaths) {
        this.workspaces = new ArrayList<>();
        for (Path path : workspacePaths) {
            workspaces.add(loadWorkspaceEntry(path));
        }
    }

    public MultiWorkspaceSearch(List<WorkspaceEntry> entries, boolean direct) {
        this.workspaces = new ArrayList<>(entries);
    }

    /**
     * Returns the list of workspace entries.
     */
    public List<WorkspaceEntry> getWorkspaces() {
        return Collections.unmodifiableList(workspaces);
    }

    /**
     * Searches across all workspaces in parallel.
     *
     * @param query    Lucene query string
     * @param fileType optional file type filter
     * @param limit    max results per workspace
     * @return aggregated results grouped by workspace
     */
    public MultiSearchResult search(String query, String fileType, int limit) {
        long startTime = System.nanoTime();

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(workspaces.size(), Runtime.getRuntime().availableProcessors()));

        List<Future<GroupedResults>> futures = new ArrayList<>();

        for (WorkspaceEntry ws : workspaces) {
            futures.add(executor.submit(() -> searchSingleWorkspace(ws, query, fileType, limit)));
        }

        List<GroupedResults> groups = new ArrayList<>();
        int totalResults = 0;

        for (Future<GroupedResults> future : futures) {
            try {
                GroupedResults result = future.get(30, TimeUnit.SECONDS);
                groups.add(result);
                if (result.hasResults()) {
                    totalResults += result.results().size();
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOG.warning("Workspace search failed: " + e.getMessage());
            }
        }

        executor.shutdown();

        long totalTimeMs = (System.nanoTime() - startTime) / 1_000_000;
        return new MultiSearchResult(groups, totalResults, totalTimeMs);
    }

    /**
     * Finds which workspaces contain files matching a filename or pattern.
     *
     * @param filename the filename or pattern to search for
     * @param useGlob  if true, treat filename as a glob pattern
     * @return map of workspace to matching file paths
     */
    public Map<WorkspaceEntry, List<String>> which(String filename, boolean useGlob) {
        Map<WorkspaceEntry, List<String>> results = new LinkedHashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(workspaces.size(), Runtime.getRuntime().availableProcessors()));

        List<Future<Map.Entry<WorkspaceEntry, List<String>>>> futures = new ArrayList<>();

        for (WorkspaceEntry ws : workspaces) {
            futures.add(executor.submit(() -> {
                List<String> matches = findInWorkspace(ws, filename, useGlob);
                return Map.entry(ws, matches);
            }));
        }

        for (Future<Map.Entry<WorkspaceEntry, List<String>>> future : futures) {
            try {
                Map.Entry<WorkspaceEntry, List<String>> entry = future.get(30, TimeUnit.SECONDS);
                if (!entry.getValue().isEmpty()) {
                    results.put(entry.getKey(), entry.getValue());
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOG.warning("Which search failed: " + e.getMessage());
            }
        }

        executor.shutdown();
        return results;
    }

    /**
     * Discovers all initialized Synthesis workspaces on the system.
     *
     * @return list of workspace paths
     */
    public static List<Path> discoverAllWorkspaces() {
        List<Path> discovered = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        String homeDir = System.getProperty("user.home");
        List<Path> searchPaths = List.of(
                Path.of(homeDir, "Documents"),
                Path.of(homeDir, "Downloads"),
                Path.of("/src"),
                Path.of(homeDir, "src")
        );

        for (Path searchPath : searchPaths) {
            if (!Files.exists(searchPath)) continue;

            // Direct .synthesis directory
            if (Files.isDirectory(searchPath.resolve(".synthesis"))) {
                Path abs = searchPath.toAbsolutePath().normalize();
                if (seen.add(abs)) discovered.add(abs);
            }

            // One level deep
            if (Files.isDirectory(searchPath)) {
                try (Stream<Path> entries = Files.list(searchPath)) {
                    entries.filter(Files::isDirectory)
                            .forEach(subDir -> {
                                if (Files.isDirectory(subDir.resolve(".synthesis"))) {
                                    Path abs = subDir.toAbsolutePath().normalize();
                                    if (seen.add(abs)) discovered.add(abs);
                                }
                            });
                } catch (IOException e) {
                    // Skip this search path
                }
            }
        }

        return discovered;
    }

    /**
     * Discovers workspaces filtered by type.
     */
    public static List<Path> discoverWorkspacesByType(WorkspaceType type) {
        List<Path> all = discoverAllWorkspaces();
        List<Path> filtered = new ArrayList<>();

        for (Path path : all) {
            WorkspaceEntry entry = loadWorkspaceEntry(path);
            if (entry.type() == type) {
                filtered.add(path);
            }
        }

        return filtered;
    }

    // --- Private helpers ---

    private GroupedResults searchSingleWorkspace(WorkspaceEntry ws, String query,
                                                  String fileType, int limit) {
        long startTime = System.nanoTime();
        try {
            WorkspaceManager workspace = new WorkspaceManager(ws.path());
            Optional<String> validation = workspace.validate();
            if (validation.isPresent()) {
                return new GroupedResults(ws, List.of(), 0, validation.get());
            }

            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                List<SearchResult> results;
                if (fileType != null && !fileType.isBlank() && !"ALL".equalsIgnoreCase(fileType)) {
                    results = index.search(query, fileType, limit);
                } else {
                    results = index.search(query, limit);
                }
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                return new GroupedResults(ws, results, elapsedMs, null);
            }
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            return new GroupedResults(ws, List.of(), elapsedMs, e.getMessage());
        }
    }

    private List<String> findInWorkspace(WorkspaceEntry ws, String filename, boolean useGlob) {
        List<String> matches = new ArrayList<>();
        try {
            WorkspaceManager workspace = new WorkspaceManager(ws.path());
            Optional<String> validation = workspace.validate();
            if (validation.isPresent()) return matches;

            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                // Search by filename
                List<SearchResult> results = index.search(filename, 100);

                for (SearchResult result : results) {
                    String fileName = result.fileName();
                    String relPath = result.relativePath();

                    if (useGlob) {
                        // Simple glob matching: * matches anything
                        String pattern = filename.replace(".", "\\.").replace("*", ".*");
                        if (fileName.matches(pattern) || relPath.matches(".*" + pattern)) {
                            matches.add(relPath);
                        }
                    } else {
                        // Exact filename match or partial path match
                        if (fileName.equals(filename) ||
                            fileName.equalsIgnoreCase(filename) ||
                            relPath.contains(filename)) {
                            matches.add(relPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("Error searching workspace " + ws.path() + ": " + e.getMessage());
        }
        return matches;
    }

    static WorkspaceEntry loadWorkspaceEntry(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        WorkspaceType type = WorkspaceType.MIXED;
        String primaryLanguage = null;
        String company = null;

        try {
            SynthesisConfig config = ConfigLoader.load(path);
            if (config.getWorkspace() != null) {
                if (config.getWorkspace().getName() != null && !config.getWorkspace().getName().isBlank()) {
                    name = config.getWorkspace().getName();
                }
                type = config.getWorkspace().getWorkspaceType();

                if (config.getWorkspace().getMetadata() != null) {
                    primaryLanguage = config.getWorkspace().getMetadata().getPrimaryLanguage();
                    company = config.getWorkspace().getMetadata().getCompany();
                }
            }
        } catch (Exception e) {
            LOG.fine("Could not load config for " + path + ": " + e.getMessage());
        }

        return new WorkspaceEntry(path, name, type, primaryLanguage, company);
    }
}
