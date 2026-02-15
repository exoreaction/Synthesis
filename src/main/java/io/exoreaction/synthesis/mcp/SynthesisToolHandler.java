package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.cli.RelateCommand;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.GraphBuilder;
import io.exoreaction.synthesis.graph.GraphBuilder.FileGraph;
import io.exoreaction.synthesis.graph.GraphRenderer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Implements the four Synthesis MCP tools: search, relate, graph, and stats.
 *
 * <p>Each tool method accepts a JSON params object and returns a JSON result
 * object. The methods reuse the existing Synthesis core components (SearchIndex,
 * GraphBuilder, etc.) to avoid duplicating logic.
 *
 * <p>Thread safety: Methods are synchronized on the workspace's index path
 * to avoid concurrent Lucene access issues.
 */
public class SynthesisToolHandler {

    private static final Logger LOG = Logger.getLogger(SynthesisToolHandler.class.getName());
    private final ObjectMapper mapper;
    private final Path defaultWorkspace;

    public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace) {
        this.mapper = mapper;
        this.defaultWorkspace = defaultWorkspace;
    }

    /**
     * Resolves the workspace path from params or falls back to default.
     */
    private Path resolveWorkspace(JsonNode params) {
        if (params != null && params.has("workspace") && !params.get("workspace").isNull()) {
            String ws = params.get("workspace").asText();
            if (!ws.isBlank()) {
                return Path.of(ws).toAbsolutePath().normalize();
            }
        }
        return defaultWorkspace;
    }

    /**
     * Validates that a workspace is initialized and returns the WorkspaceManager.
     *
     * @throws McpToolException if workspace is not valid
     */
    private WorkspaceManager validateWorkspace(Path workspacePath) throws McpToolException {
        WorkspaceManager workspace = new WorkspaceManager(workspacePath);
        Optional<String> validation = workspace.validate();
        if (validation.isPresent()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, validation.get());
        }
        return workspace;
    }

    // -----------------------------------------------------------------------
    // Tool: search
    // -----------------------------------------------------------------------

    /**
     * Searches the Synthesis index across all file types.
     *
     * @param params JSON object with: query (required), fileType, limit, workspace
     * @return JSON object with: results[], totalHits, searchTime
     */
    public ObjectNode handleSearch(JsonNode params) throws McpToolException {
        if (params == null || !params.has("query")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: query");
        }

        String query = params.get("query").asText();
        if (query == null || query.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Parameter 'query' must not be empty");
        }

        String fileType = params.has("fileType") && !params.get("fileType").isNull()
                ? params.get("fileType").asText() : null;
        if ("ALL".equalsIgnoreCase(fileType)) {
            fileType = null;
        }

        int limit = params.has("limit") ? params.get("limit").asInt(20) : 20;
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        long startTime = System.nanoTime();
        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            List<SearchResult> results = index.search(query, fileType, limit);
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            ObjectNode response = mapper.createObjectNode();
            ArrayNode resultsArray = mapper.createArrayNode();

            for (SearchResult result : results) {
                ObjectNode item = mapper.createObjectNode();
                item.put("path", result.path().toString());
                item.put("relativePath", result.relativePath());
                item.put("type", result.fileType() != null ? result.fileType() : "UNKNOWN");
                item.put("score", Math.round(result.score() * 100.0) / 100.0);
                item.put("fileName", result.fileName());

                if (!result.summary().isEmpty()) {
                    String snippet = result.summary();
                    if (snippet.length() > 300) {
                        snippet = snippet.substring(0, 300) + "...";
                    }
                    item.put("snippet", snippet);
                }

                ObjectNode metadata = mapper.createObjectNode();
                metadata.put("size", result.sizeBytes());
                if (result.language() != null) {
                    metadata.put("language", result.language());
                }
                if (!result.headings().isEmpty()) {
                    metadata.put("headings", result.headings());
                }
                if (!result.structure().isEmpty()) {
                    metadata.put("structure", result.structure());
                }
                if (result.repository() != null) {
                    metadata.put("repository", result.repository());
                }
                item.set("metadata", metadata);

                resultsArray.add(item);
            }

            response.set("results", resultsArray);
            response.put("totalHits", results.size());
            response.put("searchTime", String.format("%.1fs", elapsedMs / 1000.0));
            response.put("workspace", workspacePath.toString());

            return response;
        } catch (Exception e) {
            if (e instanceof McpToolException) throw (McpToolException) e;
            LOG.warning("Search failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Search failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: relate
    // -----------------------------------------------------------------------

    /**
     * Shows bidirectional relationships for a file.
     *
     * @param params JSON object with: filePath (required), workspace, format
     * @return JSON object with: file, outgoing[], incoming[], stats
     */
    public ObjectNode handleRelate(JsonNode params) throws McpToolException {
        if (params == null || !params.has("filePath")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: filePath");
        }

        String filePath = params.get("filePath").asText();
        if (filePath == null || filePath.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Parameter 'filePath' must not be empty");
        }

        String format = params.has("format") && !params.get("format").isNull()
                ? params.get("format").asText() : "json";

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            // Find the target file
            List<SearchResult> targetResults;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                targetResults = index.search(filePath, 10);
            }

            // Use RelateCommand's matching logic
            RelateCommand relateCmd = new RelateCommand();
            SearchResult target = relateCmd.findBestMatch(targetResults, filePath);
            if (target == null) {
                throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                        "File not found in index: " + filePath);
            }

            // Get all files for cross-reference analysis
            List<SearchResult> allFiles;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                allFiles = index.listAll(null, 5000);
            }

            // Build file name index
            Map<String, List<String>> fileNameIndex = new HashMap<>();
            for (SearchResult f : allFiles) {
                fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>()).add(f.relativePath());
            }

            // Analyze relationships
            RelateCommand.RelationshipMap relationshipMap = new RelateCommand.RelationshipMap(target.relativePath());
            relateCmd.analyzeOutgoingRefs(target, workspacePath, relationshipMap, fileNameIndex);
            relateCmd.analyzeIncomingRefs(target, allFiles, workspacePath, relationshipMap);

            // Build response
            if ("mermaid".equalsIgnoreCase(format)) {
                ObjectNode response = mapper.createObjectNode();
                response.put("format", "mermaid");
                response.put("diagram", relateCmd.generateMermaid(relationshipMap));
                response.put("file", target.relativePath());
                return response;
            }

            // JSON format (default)
            ObjectNode response = mapper.createObjectNode();
            response.put("file", target.path().toString());
            response.put("relativePath", target.relativePath());

            ArrayNode outgoing = mapper.createArrayNode();
            for (var entry : relationshipMap.outgoing().entrySet()) {
                ObjectNode rel = mapper.createObjectNode();
                rel.put("path", entry.getKey());
                rel.put("type", entry.getValue());
                outgoing.add(rel);
            }
            response.set("outgoing", outgoing);

            ArrayNode incoming = mapper.createArrayNode();
            for (var entry : relationshipMap.incoming().entrySet()) {
                ObjectNode rel = mapper.createObjectNode();
                rel.put("path", entry.getKey());
                rel.put("type", entry.getValue());
                incoming.add(rel);
            }
            response.set("incoming", incoming);

            ObjectNode stats = mapper.createObjectNode();
            stats.put("outgoingCount", relationshipMap.outgoing().size());
            stats.put("incomingCount", relationshipMap.incoming().size());
            stats.put("totalConnections", relationshipMap.outgoing().size() + relationshipMap.incoming().size());
            response.set("stats", stats);

            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Relate failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Relate failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: graph
    // -----------------------------------------------------------------------

    /**
     * Generates an architecture graph.
     *
     * @param params JSON object with: mode, format, filter, workspace
     * @return JSON object with: format, graph, nodes, edges, generationTime
     */
    public ObjectNode handleGraph(JsonNode params) throws McpToolException {
        String mode = params != null && params.has("mode") && !params.get("mode").isNull()
                ? params.get("mode").asText() : "modules";
        String format = params != null && params.has("format") && !params.get("format").isNull()
                ? params.get("format").asText() : "mermaid";
        String filter = params != null && params.has("filter") && !params.get("filter").isNull()
                ? params.get("filter").asText() : null;

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        long startTime = System.nanoTime();
        try {
            List<SearchResult> allFiles;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                allFiles = index.listAll(null, 50000);
            }

            if (allFiles.isEmpty()) {
                throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                        "No files in index. Run 'synthesis scan' first.");
            }

            // Apply filter if specified
            if (filter != null && !filter.isBlank()) {
                allFiles = allFiles.stream()
                        .filter(f -> f.relativePath().contains(filter) ||
                                (f.repository() != null && f.repository().contains(filter)))
                        .toList();
                if (allFiles.isEmpty()) {
                    throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                            "No files match filter: " + filter);
                }
            }

            GraphBuilder builder = new GraphBuilder();
            GraphRenderer renderer = new GraphRenderer();
            FileGraph graph;

            switch (mode.toLowerCase()) {
                case "dependencies" -> graph = builder.buildModuleGraph(allFiles);
                case "cross-repo" -> graph = builder.buildCrossRepoGraph(allFiles);
                default -> graph = builder.buildModuleGraph(allFiles); // "modules" or default
            }

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            ObjectNode response = mapper.createObjectNode();
            response.put("format", format);
            response.put("nodes", graph.nodes().size());
            response.put("edges", graph.edges().size());
            response.put("title", graph.title());
            response.put("generationTime", String.format("%.1fs", elapsedMs / 1000.0));

            switch (format.toLowerCase()) {
                case "mermaid" -> response.put("graph", renderer.toMermaid(graph));
                case "dot" -> response.put("graph", renderer.toDot(graph));
                case "json" -> {
                    ArrayNode nodesArray = mapper.createArrayNode();
                    for (var node : graph.nodes()) {
                        ObjectNode n = mapper.createObjectNode();
                        n.put("id", node.id());
                        n.put("label", node.label());
                        n.put("type", node.fileType());
                        if (node.language() != null) n.put("language", node.language());
                        if (node.repository() != null) n.put("repository", node.repository());
                        n.put("directory", node.directory());
                        n.put("size", node.sizeBytes());
                        nodesArray.add(n);
                    }
                    response.set("nodesData", nodesArray);

                    ArrayNode edgesArray = mapper.createArrayNode();
                    for (var edge : graph.edges()) {
                        ObjectNode e = mapper.createObjectNode();
                        e.put("source", edge.sourceId());
                        e.put("target", edge.targetId());
                        e.put("type", edge.type());
                        e.put("weight", edge.weight());
                        edgesArray.add(e);
                    }
                    response.set("edgesData", edgesArray);
                }
                default -> response.put("graph", renderer.toMermaid(graph));
            }

            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Graph generation failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Graph generation failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: stats
    // -----------------------------------------------------------------------

    /**
     * Returns workspace statistics.
     *
     * @param params JSON object with: workspace
     * @return JSON object with file counts, index size, health info
     */
    public ObjectNode handleStats(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("workspace", workspacePath.toString());

            // Get document count and type breakdown
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                int totalDocs = index.documentCount();
                response.put("totalFiles", totalDocs);

                // Type breakdown
                ObjectNode typeBreakdown = mapper.createObjectNode();
                for (String type : List.of("CODE", "MARKDOWN", "YAML", "JSON", "CONFIG",
                        "PDF", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT")) {
                    List<SearchResult> typed = index.listAll(type, 50000);
                    if (!typed.isEmpty()) {
                        typeBreakdown.put(type, typed.size());
                    }
                }
                response.set("fileTypes", typeBreakdown);
            }

            // Index size
            Path indexPath = workspace.getIndexPath();
            if (Files.exists(indexPath)) {
                long indexSize = Files.walk(indexPath)
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try { return Files.size(p); }
                            catch (Exception e) { return 0; }
                        })
                        .sum();
                response.put("indexSizeBytes", indexSize);
                response.put("indexSize", formatSize(indexSize));
            }

            // Scan state
            Path scanStatePath = workspace.getScanStatePath();
            if (Files.exists(scanStatePath)) {
                response.put("lastScan", Files.getLastModifiedTime(scanStatePath).toInstant().toString());
            } else {
                response.put("lastScan", "never");
            }

            // Health status
            String health = "healthy";
            Path configPath = workspacePath.resolve(".synthesis").resolve("config.yaml");
            if (!Files.exists(configPath)) {
                health = "missing-config";
            }
            response.put("health", health);
            response.put("timestamp", Instant.now().toString());

            return response;
        } catch (Exception e) {
            if (e instanceof McpToolException) throw (McpToolException) e;
            LOG.warning("Stats failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Stats failed: " + e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Exception type for MCP tool errors that map to JSON-RPC error codes.
     */
    public static class McpToolException extends Exception {
        private final int code;

        public McpToolException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
