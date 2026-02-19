package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.ai.CodeExplainer;
import io.exoreaction.synthesis.ai.DirectedSynthesisEngine;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.cli.RelateCommand;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;
import io.exoreaction.synthesis.enrichment.EnrichmentLevel;
import io.exoreaction.synthesis.graph.GraphBuilder;
import io.exoreaction.synthesis.graph.GraphBuilder.FileGraph;
import io.exoreaction.synthesis.graph.GraphRenderer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.metrics.MetricsCollector;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
    private final List<Path> allWorkspaces;
    private final boolean multiWorkspaceMode;
    private final MetricsCollector metrics;

    /**
     * Single workspace constructor (backward compatible).
     */
    public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace) {
        this(mapper, defaultWorkspace, List.of(defaultWorkspace));
    }

    /**
     * Multi-workspace constructor.
     *
     * @param mapper           Jackson ObjectMapper
     * @param defaultWorkspace primary workspace (fallback)
     * @param allWorkspaces    all workspaces to search across
     */
    public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace, List<Path> allWorkspaces) {
        this.mapper = mapper;
        this.defaultWorkspace = defaultWorkspace;
        this.allWorkspaces = allWorkspaces != null && !allWorkspaces.isEmpty()
                ? allWorkspaces : List.of(defaultWorkspace);
        this.multiWorkspaceMode = this.allWorkspaces.size() > 1;
        this.metrics = MetricsCollector.create();
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
     * In multi-workspace mode, searches across all configured workspaces
     * and returns results grouped by workspace.
     *
     * @param params JSON object with: query (required), fileType, limit, workspace
     * @return JSON object with: results[], totalHits, searchTime
     */
    public ObjectNode handleSearch(JsonNode params) throws McpToolException {
        long startTime = System.nanoTime();

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

        String subWorkspace = params.has("subWorkspace") && !params.get("subWorkspace").isNull()
                ? params.get("subWorkspace").asText() : null;

        int limit = params.has("limit") ? params.get("limit").asInt(20) : 20;
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        // Multi-workspace search
        if (multiWorkspaceMode && !hasExplicitWorkspace(params)) {
            return handleMultiWorkspaceSearch(query, fileType, subWorkspace, limit, startTime);
        }

        // Single workspace search (original behavior)
        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            List<SearchResult> results;
            if (subWorkspace != null && !subWorkspace.isBlank()) {
                results = index.searchWithSubWorkspace(query, fileType, null,
                        null, null, subWorkspace, limit);
            } else {
                results = index.search(query, fileType, limit);
            }
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            // Record metrics
            metrics.recordMcpInvocation("search", workspacePath.toString(), elapsedMs,
                                      results.size(), true, null);

            return buildSearchResponse(results, elapsedMs, workspacePath.toString());
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            if (!(e instanceof McpToolException)) {
                metrics.recordMcpInvocation("search", workspacePath.toString(), elapsedMs,
                                          null, false, e.getMessage());
            }

            if (e instanceof McpToolException) throw (McpToolException) e;
            LOG.warning("Search failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Performs search across all configured workspaces, with optional sub-workspace scoping.
     */
    private ObjectNode handleMultiWorkspaceSearch(String query, String fileType,
                                                    String subWorkspace,
                                                    int limit, long startTime) throws McpToolException {
        try {
            MultiWorkspaceSearch multiSearch = new MultiWorkspaceSearch(allWorkspaces);
            MultiWorkspaceSearch.MultiSearchResult multiResult;
            if (subWorkspace != null && !subWorkspace.isBlank()) {
                multiResult = multiSearch.searchWithSubWorkspace(query, fileType, subWorkspace, limit);
            } else {
                multiResult = multiSearch.search(query, fileType, limit);
            }
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            ObjectNode response = mapper.createObjectNode();
            ArrayNode resultsArray = mapper.createArrayNode();

            // Flatten results from all workspaces, adding workspace info to each result
            for (MultiWorkspaceSearch.GroupedResults group : multiResult.groups()) {
                if (group.hasError() || !group.hasResults()) continue;

                for (SearchResult result : group.results()) {
                    ObjectNode item = buildSearchResultNode(result);
                    // Add workspace info to each result
                    item.put("workspace", group.workspace().path().toString());
                    item.put("workspaceName", group.workspace().name());
                    resultsArray.add(item);
                }
            }

            response.set("results", resultsArray);
            response.put("totalHits", multiResult.totalResults());
            response.put("searchTime", String.format("%.1fs", elapsedMs / 1000.0));
            response.put("workspaceCount", multiResult.groups().size());
            response.put("multiWorkspace", true);

            // Also add grouped view
            ArrayNode groupsArray = mapper.createArrayNode();
            for (MultiWorkspaceSearch.GroupedResults group : multiResult.groups()) {
                ObjectNode groupNode = mapper.createObjectNode();
                groupNode.put("workspace", group.workspace().path().toString());
                groupNode.put("name", group.workspace().name());
                groupNode.put("resultCount", group.hasResults() ? group.results().size() : 0);
                groupNode.put("searchTimeMs", group.searchTimeMs());
                if (group.hasError()) {
                    groupNode.put("error", group.error());
                }
                groupsArray.add(groupNode);
            }
            response.set("workspaces", groupsArray);

            // Record metrics
            metrics.recordMcpInvocation("search", "multi:" + allWorkspaces.size(),
                    elapsedMs, multiResult.totalResults(), true, null);

            return response;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            metrics.recordMcpInvocation("search", "multi:" + allWorkspaces.size(),
                    elapsedMs, null, false, e.getMessage());
            LOG.warning("Multi-workspace search failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Multi-workspace search failed: " + e.getMessage());
        }
    }

    /**
     * Builds a search result node from a SearchResult.
     */
    private ObjectNode buildSearchResultNode(SearchResult result) {
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
        if (result.subWorkspace() != null) {
            metadata.put("subWorkspace", result.subWorkspace());
        }
        item.set("metadata", metadata);

        return item;
    }

    /**
     * Builds the standard search response from a list of results.
     */
    private ObjectNode buildSearchResponse(List<SearchResult> results, long elapsedMs, String workspace) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode resultsArray = mapper.createArrayNode();

        for (SearchResult result : results) {
            resultsArray.add(buildSearchResultNode(result));
        }

        response.set("results", resultsArray);
        response.put("totalHits", results.size());
        response.put("searchTime", String.format("%.1fs", elapsedMs / 1000.0));
        response.put("workspace", workspace);

        return response;
    }

    /**
     * Checks if the params include an explicit workspace override.
     */
    private boolean hasExplicitWorkspace(JsonNode params) {
        return params != null && params.has("workspace")
                && !params.get("workspace").isNull()
                && !params.get("workspace").asText().isBlank();
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
        long startTime = System.nanoTime();
        Path workspacePath = resolveWorkspace(params);

        if (params == null || !params.has("filePath")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: filePath");
        }

        String filePath = params.get("filePath").asText();
        if (filePath == null || filePath.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Parameter 'filePath' must not be empty");
        }

        String format = params.has("format") && !params.get("format").isNull()
                ? params.get("format").asText() : "json";

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

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            int totalConnections = relationshipMap.outgoing().size() + relationshipMap.incoming().size();
            metrics.recordMcpInvocation("relate", workspacePath.toString(), elapsedMs,
                                      totalConnections, true, null);

            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            metrics.recordMcpInvocation("relate", workspacePath.toString(), elapsedMs,
                                      null, false, e.getMessage());

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

            metrics.recordMcpInvocation("graph", workspacePath.toString(), elapsedMs,
                                      graph.nodes().size(), true, null);

            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            metrics.recordMcpInvocation("graph", workspacePath.toString(), elapsedMs,
                                      null, false, e.getMessage());

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
     * In multi-workspace mode, returns aggregated stats across all workspaces.
     *
     * @param params JSON object with: workspace
     * @return JSON object with file counts, index size, health info
     */
    public ObjectNode handleStats(JsonNode params) throws McpToolException {
        long startTime = System.nanoTime();

        // Multi-workspace stats
        if (multiWorkspaceMode && !hasExplicitWorkspace(params)) {
            return handleMultiWorkspaceStats(startTime);
        }

        // Single workspace stats (original behavior)
        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            ObjectNode response = buildSingleWorkspaceStats(workspacePath, workspace);

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            metrics.recordMcpInvocation("stats", workspacePath.toString(), elapsedMs,
                                      null, true, null);

            return response;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            if (!(e instanceof McpToolException)) {
                metrics.recordMcpInvocation("stats", workspacePath.toString(), elapsedMs,
                                          null, false, e.getMessage());
            }

            if (e instanceof McpToolException) throw (McpToolException) e;
            LOG.warning("Stats failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Stats failed: " + e.getMessage());
        }
    }

    private ObjectNode buildSingleWorkspaceStats(Path workspacePath, WorkspaceManager workspace) throws Exception {
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
    }

    private ObjectNode handleMultiWorkspaceStats(long startTime) throws McpToolException {
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("multiWorkspace", true);
            response.put("workspaceCount", allWorkspaces.size());

            int totalFiles = 0;
            long totalIndexSize = 0;
            ArrayNode workspacesArray = mapper.createArrayNode();

            for (Path wsPath : allWorkspaces) {
                try {
                    WorkspaceManager ws = validateWorkspace(wsPath);
                    ObjectNode wsStats = buildSingleWorkspaceStats(wsPath, ws);
                    workspacesArray.add(wsStats);

                    totalFiles += wsStats.has("totalFiles") ? wsStats.get("totalFiles").asInt() : 0;
                    totalIndexSize += wsStats.has("indexSizeBytes") ? wsStats.get("indexSizeBytes").asLong() : 0;
                } catch (Exception e) {
                    ObjectNode errorNode = mapper.createObjectNode();
                    errorNode.put("workspace", wsPath.toString());
                    errorNode.put("error", e.getMessage());
                    workspacesArray.add(errorNode);
                }
            }

            response.set("workspaces", workspacesArray);
            response.put("totalFiles", totalFiles);
            response.put("totalIndexSizeBytes", totalIndexSize);
            response.put("totalIndexSize", formatSize(totalIndexSize));
            response.put("timestamp", Instant.now().toString());

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            metrics.recordMcpInvocation("stats", "multi:" + allWorkspaces.size(),
                    elapsedMs, null, true, null);

            return response;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            metrics.recordMcpInvocation("stats", "multi:" + allWorkspaces.size(),
                    elapsedMs, null, false, e.getMessage());
            LOG.warning("Multi-workspace stats failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Multi-workspace stats failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: ask
    // -----------------------------------------------------------------------

    /**
     * AI-powered Q&A about the workspace using Directed Synthesis.
     *
     * @param params JSON object with: query (required), workspace
     * @return JSON object with: answer, citations[]
     */
    public ObjectNode handleAsk(JsonNode params) throws McpToolException {
        if (params == null || !params.has("query")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: query");
        }

        String query = params.get("query").asText();
        if (query == null || query.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Parameter 'query' must not be empty");
        }

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);
            Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                        "AI not configured. Set ANTHROPIC_API_KEY environment variable.");
            }

            DirectedSynthesisEngine engine = new DirectedSynthesisEngine(clientOpt.get(), config.getAi().getMaxTokens());

            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                // Search for relevant files
                List<SearchResult> results = index.search(query, 10);

                // Build context from results
                StringBuilder context = new StringBuilder();
                List<String> citations = new ArrayList<>();

                for (SearchResult result : results) {
                    if (Files.exists(result.path()) && Files.isReadable(result.path())) {
                        try {
                            String fileContent = io.exoreaction.synthesis.util.FileUtils.readPreview(result.path(), 4096);
                            context.append("\n--- ").append(result.relativePath()).append(" ---\n");

                            // Add line numbers
                            String[] lines = fileContent.split("\n");
                            for (int i = 0; i < lines.length; i++) {
                                context.append(String.format("L%d: %s%n", i + 1, lines[i]));
                            }

                            citations.add(result.relativePath());
                        } catch (Exception e) {
                            // Skip unreadable files
                        }
                    }
                }

                // Generate answer using the ask prompt
                String prompt = io.exoreaction.synthesis.ai.PromptTemplates.buildAskPrompt(
                        query, context.toString());
                String answer = clientOpt.get().generate(prompt, config.getAi().getMaxTokens());

                // Build response
                ObjectNode response = mapper.createObjectNode();
                response.put("answer", answer);

                ArrayNode citationsArray = mapper.createArrayNode();
                for (String citation : citations) {
                    citationsArray.add(citation);
                }
                response.set("citations", citationsArray);
                response.put("contextFiles", results.size());
                response.put("workspace", workspacePath.toString());

                return response;
            }
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Ask failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Ask failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: enrich
    // -----------------------------------------------------------------------

    /**
     * Generates companion files for binary assets.
     *
     * @param params JSON object with: filePath (optional), level, force, workspace
     * @return JSON object with: generated, companionPath, metadata
     */
    public ObjectNode handleEnrich(JsonNode params) throws McpToolException {
        String filePath = params != null && params.has("filePath")
                ? params.get("filePath").asText() : null;
        String levelStr = params != null && params.has("level")
                ? params.get("level").asText() : "basic";
        boolean force = params != null && params.has("force")
                && params.get("force").asBoolean(false);

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            EnrichmentLevel level = switch (levelStr.toLowerCase()) {
                case "local" -> EnrichmentLevel.LOCAL;
                case "ai" -> EnrichmentLevel.AI;
                default -> EnrichmentLevel.BASIC;
            };

            // Get optional AI client
            ClaudeClient aiClient = null;
            if (level.hasAI()) {
                SynthesisConfig config = ConfigLoader.load(workspacePath);
                aiClient = ClaudeClient.create(config.getAi()).orElse(null);
                if (aiClient == null) {
                    level = EnrichmentLevel.BASIC;
                }
            }

            CompanionFileGenerator generator = new CompanionFileGenerator(level, force, aiClient);
            AnalyzerRegistry analyzers = new AnalyzerRegistry();

            if (filePath != null && !filePath.isBlank()) {
                // Single file enrichment
                Path resolved = Path.of(filePath);
                if (!resolved.isAbsolute()) {
                    resolved = workspacePath.resolve(resolved);
                }

                if (!Files.exists(resolved)) {
                    throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                            "File not found: " + filePath);
                }

                BasicFileAttributes attrs = Files.readAttributes(resolved, BasicFileAttributes.class);
                FileMetadata metadata = FileMetadata.of(
                        resolved, workspacePath, attrs.size(),
                        attrs.lastModifiedTime().toInstant(), null);

                io.exoreaction.synthesis.analyzer.AnalysisResult analysis = analyzers.analyze(metadata);
                Optional<Path> companionPath = generator.generate(metadata, analysis, List.of());

                ObjectNode response = mapper.createObjectNode();
                response.put("generated", companionPath.isPresent());
                response.put("sourcePath", resolved.toString());
                if (companionPath.isPresent()) {
                    response.put("companionPath", companionPath.get().toString());
                }
                response.put("level", level.name());

                return response;
            } else {
                // Batch mode: process all binary files
                int generated = 0;
                int skipped = 0;
                int errors = 0;

                try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                    List<SearchResult> allFiles = index.listAll(null, 50000);

                    for (SearchResult file : allFiles) {
                        String ft = file.fileType();
                        if (ft == null || !(ft.equals("VIDEO") || ft.equals("IMAGE") || ft.equals("PDF") || ft.equals("AUDIO"))) {
                            continue;
                        }

                        try {
                            Path fp = file.path();
                            if (!Files.exists(fp)) { errors++; continue; }

                            BasicFileAttributes attrs = Files.readAttributes(fp, BasicFileAttributes.class);
                            FileMetadata metadata = FileMetadata.of(
                                    fp, workspacePath, attrs.size(),
                                    attrs.lastModifiedTime().toInstant(), null);

                            io.exoreaction.synthesis.analyzer.AnalysisResult analysis = analyzers.analyze(metadata);
                            Optional<Path> companion = generator.generate(metadata, analysis, List.of());

                            if (companion.isPresent()) generated++;
                            else skipped++;
                        } catch (Exception e) {
                            errors++;
                        }
                    }
                }

                ObjectNode response = mapper.createObjectNode();
                response.put("generated", generated);
                response.put("skipped", skipped);
                response.put("errors", errors);
                response.put("level", level.name());
                response.put("workspace", workspacePath.toString());

                return response;
            }
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Enrich failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Enrich failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: explain
    // -----------------------------------------------------------------------

    /**
     * AI-powered code explanation.
     *
     * @param params JSON object with: target (required), includeContext, depth, workspace
     * @return JSON object with: explanation, mode, target, contextDocuments, durationMs
     */
    public ObjectNode handleExplain(JsonNode params) throws McpToolException {
        if (params == null || !params.has("target")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: target");
        }

        String target = params.get("target").asText();
        if (target == null || target.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Parameter 'target' must not be empty");
        }

        boolean includeContext = params.has("includeContext") && params.get("includeContext").asBoolean(true);
        String depthStr = params.has("depth") ? params.get("depth").asText() : "standard";

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);
            Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
            if (clientOpt.isEmpty()) {
                throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                        "AI not configured. Set ANTHROPIC_API_KEY environment variable.");
            }

            CodeExplainer.Depth depth = switch (depthStr.toLowerCase()) {
                case "brief" -> CodeExplainer.Depth.BRIEF;
                case "deep" -> CodeExplainer.Depth.DEEP;
                default -> CodeExplainer.Depth.STANDARD;
            };

            CodeExplainer explainer = new CodeExplainer(clientOpt.get(), config.getAi().getMaxTokens());

            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                CodeExplainer.ExplanationResult result;

                // Determine mode: file, module, or pattern
                Path targetPath = Path.of(target);
                if (!targetPath.isAbsolute()) {
                    targetPath = workspacePath.resolve(target);
                }

                if (Files.isRegularFile(targetPath)) {
                    result = explainer.explainFile(targetPath, index, workspacePath, depth);
                } else if (Files.isDirectory(targetPath)) {
                    result = explainer.explainModule(targetPath, index, workspacePath, depth);
                } else {
                    // Treat as pattern/concept
                    result = explainer.explainPattern(target, index, workspacePath, depth);
                }

                ObjectNode response = mapper.createObjectNode();
                response.put("target", result.target());
                response.put("mode", result.mode());
                response.put("explanation", result.explanation());
                response.put("contextDocuments", result.contextDocuments());
                response.put("durationMs", result.durationMs());

                return response;
            }
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Explain failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Explain failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: summary (Phase 4)
    // -----------------------------------------------------------------------

    /**
     * Generate executive summary of the codebase.
     *
     * @param params JSON object with: level (executive/manager/developer),
     *               perspective (general/executive/architect/security/devops/product_manager/engineering_manager/developer),
     *               format (terminal/markdown/json), noAi (boolean), workspace
     * @return JSON object with: summary (text), level, perspective, fromCache, generationTimeMs
     */
    public ObjectNode handleSummary(JsonNode params) throws McpToolException {
        String level = params.has("level") ? params.get("level").asText() : "executive";
        String perspective = params.has("perspective") ? params.get("perspective").asText() : "general";
        String format = params.has("format") ? params.get("format").asText() : "markdown";
        String since = params.has("since") ? params.get("since").asText() : null;
        boolean noAi = params.has("noAi") && params.get("noAi").asBoolean(false);
        boolean noCache = params.has("noCache") && params.get("noCache").asBoolean(false);

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            long startTime = System.currentTimeMillis();

            // Parse parameters
            io.exoreaction.synthesis.summary.SummaryLevel summaryLevel =
                io.exoreaction.synthesis.summary.SummaryLevel.fromString(level);
            io.exoreaction.synthesis.summary.SummaryPerspective summaryPerspective =
                io.exoreaction.synthesis.summary.SummaryPerspective.fromString(perspective);

            // Phase 3: Check cache
            String indexFingerprint = io.exoreaction.synthesis.summary.SummaryCache
                .generateIndexFingerprint(workspace.getIndexPath());
            io.exoreaction.synthesis.summary.SummaryResult result = null;

            if (!noCache) {
                try {
                    io.exoreaction.synthesis.db.SynthesisDatabase db =
                        io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
                    java.sql.Connection conn = db.getConnection();
                    io.exoreaction.synthesis.summary.SummaryCache cache =
                        new io.exoreaction.synthesis.summary.SummaryCache(conn, 0);
                    Optional<io.exoreaction.synthesis.summary.SummaryResult> cached =
                        cache.get(workspacePath, summaryLevel, summaryPerspective, indexFingerprint);

                    if (cached.isPresent()) {
                        result = cached.get();
                    }
                } catch (Exception e) {
                    // Cache failures don't break functionality
                }
            }

            // Generate if not cached
            if (result == null) {
                // Generate profile
                io.exoreaction.synthesis.summary.CodebaseProfile profiler =
                    new io.exoreaction.synthesis.summary.CodebaseProfile();
                io.exoreaction.synthesis.summary.CodebaseProfile.Profile profile;
                try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                    profile = profiler.generate(index, workspacePath);
                }

                // Temporal context (if since provided)
                String temporalContext = null;
                if (since != null && !since.isBlank()) {
                    java.time.Instant sinceInstant =
                        io.exoreaction.synthesis.cli.ChangedCommand.parseSince(since);
                    if (sinceInstant != null) {
                        try {
                            io.exoreaction.synthesis.db.SynthesisDatabase changeDb =
                                io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
                            io.exoreaction.synthesis.changelog.SnapshotManager snapshots =
                                new io.exoreaction.synthesis.changelog.SnapshotManager(changeDb);
                            java.util.List<io.exoreaction.synthesis.changelog.ChangeEvent> events =
                                snapshots.getChangesForWorkspace(workspacePath.toString(), sinceInstant);
                            temporalContext = "Changes since " + since + ": " +
                                new io.exoreaction.synthesis.changelog.ChangeReportGenerator()
                                    .generateSummary(events);
                        } catch (Exception e) {
                            temporalContext = "Changes since " + since +
                                " (changelog not available — run 'synthesis maintain' first)";
                        }
                    }
                }

                // AI-enhanced summary
                String aiSummary = null;
                String modelUsed = null;
                if (!noAi) {
                    SynthesisConfig config = ConfigLoader.load(workspacePath);
                    Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());

                    if (clientOpt.isPresent()) {
                        io.exoreaction.synthesis.summary.SummaryEngine engine =
                            new io.exoreaction.synthesis.summary.SummaryEngine(clientOpt.get());
                        modelUsed = engine.getModel();
                        aiSummary = engine.generateSummary(
                            profile, summaryLevel, summaryPerspective, temporalContext);
                    }
                }

                long generationTime = System.currentTimeMillis() - startTime;

                // Create result
                if (temporalContext != null) {
                    result = io.exoreaction.synthesis.summary.SummaryResult.withTemporal(
                        profile, aiSummary, summaryLevel, summaryPerspective,
                        temporalContext, generationTime);
                } else if (aiSummary != null) {
                    result = io.exoreaction.synthesis.summary.SummaryResult.withAiSummary(
                        profile, aiSummary, summaryLevel, summaryPerspective, generationTime);
                } else {
                    result = io.exoreaction.synthesis.summary.SummaryResult.fromProfile(
                        profile, summaryLevel, summaryPerspective, generationTime);
                }

                // Store in cache
                if (!noCache) {
                    try {
                        io.exoreaction.synthesis.db.SynthesisDatabase db =
                            io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
                        java.sql.Connection conn = db.getConnection();
                        io.exoreaction.synthesis.summary.SummaryCache cache =
                            new io.exoreaction.synthesis.summary.SummaryCache(conn, 0);
                        cache.put(workspacePath, summaryLevel, summaryPerspective,
                            indexFingerprint, result, modelUsed);
                    } catch (Exception e) {
                        // Cache storage failures don't break functionality
                    }
                }
            }

            // Render output
            io.exoreaction.synthesis.summary.SummaryRenderer renderer =
                new io.exoreaction.synthesis.summary.SummaryRenderer();
            String output = switch (format.toLowerCase()) {
                case "markdown", "md" -> renderer.renderMarkdown(result);
                case "json" -> renderer.renderJson(result);
                default -> renderer.renderMarkdown(result);  // MCP prefers markdown
            };

            // Build response
            ObjectNode response = mapper.createObjectNode();
            response.put("summary", output);
            response.put("level", result.level().cliValue());
            response.put("perspective", result.perspective().cliValue());
            response.put("fromCache", result.fromCache());
            response.put("generationTimeMs", result.generationTimeMs());
            if (result.temporalContext() != null) {
                response.put("temporalContext", result.temporalContext());
            }

            return response;

        } catch (Exception e) {
            LOG.warning("Summary generation failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                "Summary generation failed: " + e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Shuts down the metrics collector.
     * Should be called when the MCP server is shutting down.
     */
    public void shutdown() {
        if (metrics != null) {
            metrics.shutdown();
        }
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
