package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.RelationService;
import io.exoreaction.synthesis.graph.RelationService.RelationshipMap;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Traces the import-graph path between two classes.
 *
 * <p>Uses BFS over outgoing dependencies (imports/references) from the
 * starting class to find the shortest path to the target class.
 *
 * <p>Usage:
 * <pre>
 *   synthesis trace SummaryCommand --to ClaudeClient
 *   synthesis trace SearchIndex.java --to FileUtils --max-depth 5
 *   synthesis trace SummaryCommand --to ClaudeClient --format mermaid
 * </pre>
 */
@Command(
        name = "trace",
        description = "Find import-graph path between two classes",
        mixinStandardHelpOptions = true
)
public class TraceCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(index = "0", description = "Starting class name or file")
    String startClass;

    @Option(names = {"--to"}, required = true, description = "Target class name")
    String toClass;

    @Option(names = {"--max-depth"}, defaultValue = "10", description = "Maximum BFS depth (default: 10)")
    int maxDepth;

    @Option(names = {"--format"}, defaultValue = "text", description = "Output format: text or mermaid")
    String format;

    private final RelationService relationService;

    public TraceCommand() {
        this.relationService = new RelationService();
    }

    /** Test constructor allowing injection of a custom RelationService. */
    TraceCommand(RelationService relationService) {
        this.relationService = relationService;
    }

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Resolve start and end files from the index
            List<SearchResult> allFiles;
            SearchResult startFile;
            SearchResult endFile;

            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                List<SearchResult> startResults = index.search(startClass, 5);
                startFile = relationService.findBestMatch(startResults, startClass);
                if (startFile == null) {
                    AnsiOutput.printError("Start class not found in index: " + startClass);
                    AnsiOutput.printInfo("Try 'synthesis search " + startClass + "' to find it.");
                    return 1;
                }

                List<SearchResult> endResults = index.search(toClass, 5);
                endFile = relationService.findBestMatch(endResults, toClass);
                if (endFile == null) {
                    AnsiOutput.printError("Target class not found in index: " + toClass);
                    AnsiOutput.printInfo("Try 'synthesis search " + toClass + "' to find it.");
                    return 1;
                }

                allFiles = index.listAll(null, 5000);
            }

            // Build filename index for resolving references
            Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);

            // Build a map from relativePath to SearchResult for quick lookup
            Map<String, SearchResult> fileMap = new LinkedHashMap<>();
            for (SearchResult f : allFiles) {
                fileMap.put(f.relativePath(), f);
            }

            // BFS to find shortest path
            List<String> path = bfsTrace(startFile, endFile, workspaceRoot, allFiles,
                    fileNameIndex, fileMap);

            if (path == null) {
                System.out.println();
                System.out.println("No path found from " + startFile.fileName()
                        + " to " + endFile.fileName() + " (within depth " + maxDepth + ")");
                System.out.println();
                return 0;
            }

            // Output
            if ("mermaid".equalsIgnoreCase(format)) {
                printMermaid(path);
            } else {
                printTextPath(path, startFile, endFile);
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Trace failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * BFS from startFile to endFile following outgoing dependencies (imports).
     *
     * @return the path as a list of relativePaths, or null if not found
     */
    List<String> bfsTrace(SearchResult startFile, SearchResult endFile,
                           Path workspaceRoot, List<SearchResult> allFiles,
                           Map<String, List<String>> fileNameIndex,
                           Map<String, SearchResult> fileMap) {
        String startPath = startFile.relativePath();
        String endPath = endFile.relativePath();

        // Trivial: start == end
        if (startPath.equals(endPath)) {
            return List.of(startPath);
        }

        // BFS with parent tracking
        Queue<String> queue = new LinkedList<>();
        Map<String, String> parentMap = new LinkedHashMap<>(); // child -> parent
        Set<String> visited = new LinkedHashSet<>();

        queue.add(startPath);
        visited.add(startPath);
        parentMap.put(startPath, null);

        int currentDepth = 0;
        int currentLevelSize = 1;
        int nextLevelSize = 0;

        while (!queue.isEmpty() && currentDepth < maxDepth) {
            String current = queue.poll();
            currentLevelSize--;

            // Get outgoing dependencies for the current file
            SearchResult currentFile = fileMap.get(current);
            if (currentFile == null) {
                if (currentLevelSize == 0) {
                    currentDepth++;
                    currentLevelSize = nextLevelSize;
                    nextLevelSize = 0;
                }
                continue;
            }

            Set<String> neighbors = getOutgoingDependencies(currentFile, workspaceRoot, fileNameIndex);

            for (String neighbor : neighbors) {
                if (visited.contains(neighbor)) continue;

                visited.add(neighbor);
                parentMap.put(neighbor, current);

                if (neighbor.equals(endPath)) {
                    // Reconstruct path
                    return reconstructPath(parentMap, endPath);
                }

                queue.add(neighbor);
                nextLevelSize++;
            }

            if (currentLevelSize == 0) {
                currentDepth++;
                currentLevelSize = nextLevelSize;
                nextLevelSize = 0;
            }
        }

        return null; // No path found
    }

    /**
     * Gets outgoing dependencies (imports) for a file using RelationService.
     */
    private Set<String> getOutgoingDependencies(SearchResult file, Path workspaceRoot,
                                                  Map<String, List<String>> fileNameIndex) {
        RelationshipMap map = new RelationshipMap(file.relativePath());
        relationService.analyzeOutgoingRefs(file, workspaceRoot, map, fileNameIndex);
        return map.outgoing().keySet();
    }

    /**
     * Reconstructs the path from parentMap by backtracking from end to start.
     */
    private List<String> reconstructPath(Map<String, String> parentMap, String end) {
        List<String> path = new ArrayList<>();
        String current = end;
        while (current != null) {
            path.add(current);
            current = parentMap.get(current);
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Prints the trace path in human-readable text format.
     */
    private void printTextPath(List<String> path, SearchResult startFile, SearchResult endFile) {
        System.out.println();
        System.out.println("Tracing: " + startFile.fileName() + " -> " + endFile.fileName());
        System.out.println();

        for (int i = 0; i < path.size(); i++) {
            String relPath = path.get(i);
            String fileName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            String indent = "  " + "  ".repeat(i);

            if (i == 0) {
                System.out.println(indent + fileName);
            } else {
                System.out.println(indent + "-> " + fileName + "          (imports)");
            }
        }

        System.out.println();
        System.out.println("Path length: " + (path.size() - 1) + " hops");
        StringBuilder filesLine = new StringBuilder("Files: ");
        for (int i = 0; i < path.size(); i++) {
            String relPath = path.get(i);
            String fileName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            if (i > 0) filesLine.append(" -> ");
            filesLine.append(fileName);
        }
        System.out.println(filesLine);
        System.out.println();
    }

    /**
     * Prints the trace path as a Mermaid sequence diagram.
     */
    private void printMermaid(List<String> path) {
        System.out.println("```mermaid");
        System.out.println("graph LR");
        for (int i = 0; i < path.size(); i++) {
            String relPath = path.get(i);
            String fileName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            String id = sanitizeMermaidId(relPath);
            System.out.println("    " + id + "[\"" + fileName + "\"]");
        }
        for (int i = 0; i < path.size() - 1; i++) {
            String fromId = sanitizeMermaidId(path.get(i));
            String toId = sanitizeMermaidId(path.get(i + 1));
            System.out.println("    " + fromId + " -->|imports| " + toId);
        }
        // Highlight start and end
        if (!path.isEmpty()) {
            System.out.println("    style " + sanitizeMermaidId(path.get(0))
                    + " fill:#bbf,stroke:#333,stroke-width:2px");
            System.out.println("    style " + sanitizeMermaidId(path.get(path.size() - 1))
                    + " fill:#fbb,stroke:#333,stroke-width:2px");
        }
        System.out.println("```");
    }

    private String sanitizeMermaidId(String path) {
        return path.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
