package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds graph data structures from indexed files and their relationships.
 *
 * <p>Produces nodes and edges that can be rendered as Graphviz DOT, Mermaid,
 * or exported for other visualization tools.
 */
public class GraphBuilder {

    // Reference detection patterns
    private static final Pattern JAVA_IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)", Pattern.MULTILINE);
    private static final Pattern GENERIC_FILE_REF = Pattern.compile(
            "(?:['\"`])([\\w./-]+\\.(?:java|py|js|ts|md|yaml|yml|json|xml|go|rs|kt))['\"`]");

    /**
     * A node in the file relationship graph.
     */
    public record GraphNode(
            String id,
            String label,
            String fileType,
            String language,
            String repository,
            long sizeBytes,
            String directory
    ) {}

    /**
     * An edge (relationship) between two nodes.
     */
    public record GraphEdge(
            String sourceId,
            String targetId,
            String type,
            int weight
    ) {}

    /**
     * A complete graph with nodes and edges.
     */
    public record FileGraph(
            List<GraphNode> nodes,
            List<GraphEdge> edges,
            String title
    ) {
        public Set<String> getDirectories() {
            Set<String> dirs = new TreeSet<>();
            for (GraphNode node : nodes) {
                dirs.add(node.directory());
            }
            return dirs;
        }
    }

    /**
     * Builds a file relationship graph centered on a specific file.
     *
     * @param targetFile the focal file
     * @param allFiles   all indexed files
     * @param depth      how many levels of relationships to include
     * @return the file graph
     */
    public FileGraph buildFileGraph(SearchResult targetFile, List<SearchResult> allFiles, int depth) {
        Map<String, SearchResult> fileIndex = new LinkedHashMap<>();
        Map<String, String> fileNameToPath = new HashMap<>();
        for (SearchResult f : allFiles) {
            fileIndex.put(f.relativePath(), f);
            fileNameToPath.put(f.fileName(), f.relativePath());
        }

        // BFS from target file to discover relationships
        Set<String> visitedFiles = new LinkedHashSet<>();
        List<GraphEdge> edges = new ArrayList<>();

        Queue<String> toVisit = new LinkedList<>();
        toVisit.add(targetFile.relativePath());
        visitedFiles.add(targetFile.relativePath());

        int currentDepth = 0;
        int currentLevelSize = 1;
        int nextLevelSize = 0;

        while (!toVisit.isEmpty() && currentDepth <= depth) {
            String filePath = toVisit.poll();
            currentLevelSize--;

            SearchResult file = fileIndex.get(filePath);
            if (file == null) continue;

            // Find outgoing references
            Set<String> refs = extractReferences(file, fileNameToPath);
            for (String ref : refs) {
                if (!ref.equals(filePath) && fileIndex.containsKey(ref)) {
                    edges.add(new GraphEdge(filePath, ref, "references", 1));
                    if (!visitedFiles.contains(ref) && currentDepth < depth) {
                        visitedFiles.add(ref);
                        toVisit.add(ref);
                        nextLevelSize++;
                    }
                }
            }

            // Find incoming references
            for (SearchResult other : allFiles) {
                if (other.relativePath().equals(filePath)) continue;
                Set<String> otherRefs = extractReferences(other, fileNameToPath);
                if (otherRefs.contains(filePath)) {
                    edges.add(new GraphEdge(other.relativePath(), filePath, "references", 1));
                    if (!visitedFiles.contains(other.relativePath()) && currentDepth < depth) {
                        visitedFiles.add(other.relativePath());
                        toVisit.add(other.relativePath());
                        nextLevelSize++;
                    }
                }
            }

            if (currentLevelSize == 0) {
                currentDepth++;
                currentLevelSize = nextLevelSize;
                nextLevelSize = 0;
            }
        }

        // Build nodes
        List<GraphNode> nodes = new ArrayList<>();
        for (String path : visitedFiles) {
            SearchResult f = fileIndex.get(path);
            if (f != null) {
                String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
                nodes.add(new GraphNode(path, f.fileName(), f.fileType(), f.language(),
                        f.repository(), f.sizeBytes(), dir));
            }
        }

        // Deduplicate edges and compute weights
        Map<String, GraphEdge> edgeMap = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            String key = edge.sourceId() + "|" + edge.targetId();
            edgeMap.merge(key, edge, (a, b) -> new GraphEdge(a.sourceId(), a.targetId(),
                    a.type(), a.weight() + b.weight()));
        }

        return new FileGraph(nodes, new ArrayList<>(edgeMap.values()),
                "File relationships: " + targetFile.relativePath());
    }

    /**
     * Builds a module/directory-level graph.
     */
    public FileGraph buildModuleGraph(List<SearchResult> allFiles) {
        // Group files by top-level directory
        Map<String, List<SearchResult>> modules = new LinkedHashMap<>();
        Map<String, String> fileNameToPath = new HashMap<>();

        for (SearchResult f : allFiles) {
            String[] parts = f.relativePath().split("/");
            String module = parts.length > 1 ? parts[0] : ".";
            modules.computeIfAbsent(module, k -> new ArrayList<>()).add(f);
            fileNameToPath.put(f.fileName(), f.relativePath());
        }

        // Build module-level edges
        Map<String, GraphEdge> edgeMap = new LinkedHashMap<>();
        Map<String, Long> moduleSizes = new LinkedHashMap<>();

        for (Map.Entry<String, List<SearchResult>> entry : modules.entrySet()) {
            String sourceModule = entry.getKey();
            long totalSize = 0;

            for (SearchResult file : entry.getValue()) {
                totalSize += file.sizeBytes();
                Set<String> refs = extractReferences(file, fileNameToPath);
                for (String ref : refs) {
                    String[] parts = ref.split("/");
                    String targetModule = parts.length > 1 ? parts[0] : ".";
                    if (!targetModule.equals(sourceModule)) {
                        String key = sourceModule + "|" + targetModule;
                        edgeMap.merge(key,
                                new GraphEdge(sourceModule, targetModule, "depends", 1),
                                (a, b) -> new GraphEdge(a.sourceId(), a.targetId(),
                                        a.type(), a.weight() + b.weight()));
                    }
                }
            }
            moduleSizes.put(sourceModule, totalSize);
        }

        // Create module nodes
        List<GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, List<SearchResult>> entry : modules.entrySet()) {
            nodes.add(new GraphNode(entry.getKey(),
                    entry.getKey() + " (" + entry.getValue().size() + " files)",
                    "MODULE", null, null,
                    moduleSizes.getOrDefault(entry.getKey(), 0L),
                    entry.getKey()));
        }

        return new FileGraph(nodes, new ArrayList<>(edgeMap.values()),
                "Module dependency graph");
    }

    /**
     * Builds a cross-repository dependency graph.
     */
    public FileGraph buildCrossRepoGraph(List<SearchResult> allFiles) {
        // Group files by repository
        Map<String, List<SearchResult>> repos = new LinkedHashMap<>();
        Map<String, String> fileNameToPath = new HashMap<>();

        for (SearchResult f : allFiles) {
            String repo = f.repository() != null ? f.repository() : "default";
            repos.computeIfAbsent(repo, k -> new ArrayList<>()).add(f);
            fileNameToPath.put(f.fileName(), f.relativePath());
        }

        // Build repo-level edges
        Map<String, GraphEdge> edgeMap = new LinkedHashMap<>();
        Map<String, Long> repoSizes = new LinkedHashMap<>();

        // Also build file->repo mapping
        Map<String, String> fileToRepo = new HashMap<>();
        for (Map.Entry<String, List<SearchResult>> entry : repos.entrySet()) {
            for (SearchResult f : entry.getValue()) {
                fileToRepo.put(f.relativePath(), entry.getKey());
            }
        }

        for (Map.Entry<String, List<SearchResult>> entry : repos.entrySet()) {
            String sourceRepo = entry.getKey();
            long totalSize = 0;

            for (SearchResult file : entry.getValue()) {
                totalSize += file.sizeBytes();
                Set<String> refs = extractReferences(file, fileNameToPath);
                for (String ref : refs) {
                    String targetRepo = fileToRepo.getOrDefault(ref, sourceRepo);
                    if (!targetRepo.equals(sourceRepo)) {
                        String key = sourceRepo + "|" + targetRepo;
                        edgeMap.merge(key,
                                new GraphEdge(sourceRepo, targetRepo, "depends", 1),
                                (a, b) -> new GraphEdge(a.sourceId(), a.targetId(),
                                        a.type(), a.weight() + b.weight()));
                    }
                }
            }
            repoSizes.put(sourceRepo, totalSize);
        }

        // Create repo nodes
        List<GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, List<SearchResult>> entry : repos.entrySet()) {
            nodes.add(new GraphNode(entry.getKey(),
                    entry.getKey() + " (" + entry.getValue().size() + " files)",
                    "REPOSITORY", null, entry.getKey(),
                    repoSizes.getOrDefault(entry.getKey(), 0L),
                    entry.getKey()));
        }

        return new FileGraph(nodes, new ArrayList<>(edgeMap.values()),
                "Cross-repository dependency graph");
    }

    private Set<String> extractReferences(SearchResult file, Map<String, String> fileNameToPath) {
        Set<String> references = new LinkedHashSet<>();
        try {
            if (!Files.exists(file.path()) || !Files.isReadable(file.path())) {
                return references;
            }
            String content = FileUtils.readPreview(file.path(), 30_000);
            if (content.isEmpty()) return references;

            // Java imports
            if ("Java".equals(file.language())) {
                Matcher m = JAVA_IMPORT.matcher(content);
                while (m.find()) {
                    String imp = m.group(1);
                    String[] parts = imp.split("\\.");
                    String className = parts[parts.length - 1] + ".java";
                    String resolved = fileNameToPath.get(className);
                    if (resolved != null) references.add(resolved);
                }
            }

            // Markdown links
            if ("MARKDOWN".equals(file.fileType())) {
                Matcher m = MARKDOWN_LINK.matcher(content);
                while (m.find()) {
                    String link = m.group(2);
                    if (!link.startsWith("http") && !link.startsWith("#")) {
                        String fileName = link.contains("/") ? link.substring(link.lastIndexOf('/') + 1) : link;
                        if (fileName.contains("#")) fileName = fileName.substring(0, fileName.indexOf('#'));
                        String resolved = fileNameToPath.get(fileName);
                        if (resolved != null) references.add(resolved);
                    }
                }
            }

            // Generic file references
            Matcher m = GENERIC_FILE_REF.matcher(content);
            while (m.find()) {
                String ref = m.group(1);
                String fileName = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
                String resolved = fileNameToPath.get(fileName);
                if (resolved != null) references.add(resolved);
            }
        } catch (IOException e) {
            // Skip
        }
        return references;
    }
}
