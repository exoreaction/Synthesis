package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.ai.CodeExplainer;
import io.exoreaction.synthesis.ai.DirectedSynthesisEngine;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.graph.RelationService;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.ScanState;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;
import io.exoreaction.synthesis.enrichment.EnrichmentLevel;
import io.exoreaction.synthesis.graph.GraphBuilder;
import io.exoreaction.synthesis.graph.GraphBuilder.FileGraph;
import io.exoreaction.synthesis.graph.GraphRenderer;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.metrics.MetricsCollector;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch;
import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.sessions.SessionStore;
import static io.exoreaction.synthesis.sessions.SessionStore.sanitizeFtsQuery;
import io.exoreaction.synthesis.skills.SkillMatcher;
import io.exoreaction.synthesis.skills.SkillMatcher.SkillMatch;
import io.exoreaction.synthesis.agents.TeamReader;
import io.exoreaction.synthesis.agents.TeamReader.TeamContext;
import io.exoreaction.synthesis.agents.TeamReader.TeamNotFoundException;
import io.exoreaction.synthesis.agents.TeamContextBuilder;
import io.exoreaction.synthesis.agents.TeamContextBuilder.TeamBriefing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
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
    private final McpQueryLogger queryLogger;

    /**
     * Single workspace constructor (backward compatible).
     */
    public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace) {
        this(mapper, defaultWorkspace, List.of(defaultWorkspace));
    }

    /**
     * Multi-workspace constructor (backward compatible — no query logging).
     *
     * @param mapper           Jackson ObjectMapper
     * @param defaultWorkspace primary workspace (fallback)
     * @param allWorkspaces    all workspaces to search across
     */
    public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace, List<Path> allWorkspaces) {
        this(mapper, defaultWorkspace, allWorkspaces, McpQueryLogger.create());
    }

    /**
     * Full constructor with optional query logger injection.
     *
     * @param mapper           Jackson ObjectMapper
     * @param defaultWorkspace primary workspace (fallback)
     * @param allWorkspaces    all workspaces to search across
     * @param queryLogger      query logger ({@code null} disables logging)
     */
    public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace,
                                List<Path> allWorkspaces, McpQueryLogger queryLogger) {
        this.mapper = mapper;
        this.defaultWorkspace = defaultWorkspace;
        this.allWorkspaces = allWorkspaces != null && !allWorkspaces.isEmpty()
                ? allWorkspaces : List.of(defaultWorkspace);
        this.multiWorkspaceMode = this.allWorkspaces.size() > 1;
        this.metrics = MetricsCollector.create();
        this.queryLogger = queryLogger != null ? queryLogger : McpQueryLogger.noOp();
    }

    /**
     * Resolves the workspace path from params or falls back to default.
     *
     * <p>Accepts:
     * <ul>
     *   <li>Absolute paths (existing directory) — used directly.</li>
     *   <li>Workspace names or directory basenames — resolved against {@code allWorkspaces}
     *       in multi-workspace mode.</li>
     * </ul>
     *
     * @throws McpToolException if the requested name matches more than one workspace
     */
    private Path resolveWorkspace(JsonNode params) throws McpToolException {
        if (params != null && params.has("workspace") && !params.get("workspace").isNull()) {
            String ws = params.get("workspace").asText();
            if (!ws.isBlank()) {
                Path asPath = Path.of(ws).toAbsolutePath().normalize();
                if (Files.exists(asPath)) {
                    return asPath;
                }
                if (multiWorkspaceMode) {
                    List<Path> matches = allWorkspaces.stream()
                            .filter(p -> workspaceMatches(p, ws))
                            .toList();
                    if (matches.size() == 1) {
                        return matches.get(0);
                    }
                    if (matches.size() > 1) {
                        List<String> matchStrings = matches.stream().map(Path::toString).toList();
                        throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                                "Ambiguous workspace '" + ws + "'. Use an absolute path. Matches: " + matchStrings);
                    }
                }
                return asPath;
            }
        }
        return defaultWorkspace;
    }

    /**
     * Returns true if {@code workspacePath} matches the requested name by either
     * directory basename or the workspace name configured in .synthesis/config.yaml.
     */
    private boolean workspaceMatches(Path workspacePath, String requested) {
        if (workspacePath.getFileName() != null
                && workspacePath.getFileName().toString().equals(requested)) {
            return true;
        }
        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);
            if (config.getWorkspace() != null) {
                return requested.equals(config.getWorkspace().getName());
            }
        } catch (Exception e) {
            // Config not loadable — basename-only matching
        }
        return false;
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
     * <p>Supports KCP temporal filtering via {@code as_of} (ISO 8601 date)
     * and {@code include_all_temporal} parameters. When temporal parameters
     * are present, search results are enriched with KCP temporal metadata
     * and optionally filtered to exclude expired/not-yet-valid knowledge units.
     *
     * @param params JSON object with: query (required), fileType, limit, workspace,
     *               as_of, include_all_temporal
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

        int previewLength = params.has("previewLength") ? params.get("previewLength").asInt(300) : 300;
        if (previewLength < 100) previewLength = 100;
        if (previewLength > 3000) previewLength = 3000;

        // --- KCP temporal parameters ---
        String asOf = params.has("as_of") && !params.get("as_of").isNull()
                ? params.get("as_of").asText() : null;
        boolean includeAllTemporal = params.has("include_all_temporal")
                && params.get("include_all_temporal").asBoolean(false);

        if (asOf != null && includeAllTemporal) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                    "as_of and include_all_temporal are mutually exclusive");
        }

        // Validate as_of format if provided
        if (asOf != null && !asOf.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                    "as_of must be ISO 8601 date format: YYYY-MM-DD");
        }

        // Default as_of: today (unless include_all_temporal is set)
        String effectiveAsOf = asOf != null ? asOf
                : (!includeAllTemporal ? java.time.LocalDate.now().toString() : null);

        // Multi-workspace search
        if (multiWorkspaceMode && !hasExplicitWorkspace(params)) {
            return handleMultiWorkspaceSearch(query, fileType, subWorkspace, limit, previewLength, startTime);
        }

        // Single workspace search (original behavior)
        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
            List<SearchResult> results;
            if (subWorkspace != null && !subWorkspace.isBlank()) {
                results = index.searchWithSubWorkspace(query, fileType, null,
                        null, null, subWorkspace, limit);
            } else {
                results = index.search(query, fileType, limit);
            }
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            // Record metrics and query log
            metrics.recordMcpInvocation("search", workspacePath.toString(), elapsedMs,
                                      results.size(), true, null);
            queryLogger.log(query, workspacePath.toString(), results.size(), elapsedMs);

            ObjectNode response = buildSearchResponse(results, elapsedMs, workspacePath.toString(),
                    query, previewLength);

            // Enrich with KCP temporal metadata
            if (effectiveAsOf != null || includeAllTemporal) {
                enrichWithKcpTemporal(response, workspacePath.toString(), effectiveAsOf, includeAllTemporal);
            }

            return response;
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
     * Enriches search response results with KCP temporal metadata.
     *
     * <p>For each result that matches a file path in a KCP manifest unit,
     * adds a {@code kcpTemporal} object with validity window and provenance.
     * When {@code asOf} is provided and temporal filtering is active,
     * results for expired or not-yet-valid KCP units are annotated with
     * {@code "active": false} (not removed — the consumer decides).
     */
    private void enrichWithKcpTemporal(ObjectNode response, String workspacePath,
                                        String asOf, boolean includeAllTemporal) {
        try {
            io.exoreaction.synthesis.db.SynthesisDatabase db =
                    io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
            java.sql.Connection conn = db.getConnection();

            io.exoreaction.synthesis.kcp.KcpRepository kcpRepo =
                    new io.exoreaction.synthesis.kcp.KcpRepository();

            // Get all manifests for this workspace
            List<io.exoreaction.synthesis.kcp.KcpRepository.KcpManifestRow> manifests =
                    kcpRepo.getManifests(conn, workspacePath);

            if (manifests.isEmpty()) return;

            // Build a map: relativePath -> KcpUnitRow (for all manifests)
            java.util.Map<String, io.exoreaction.synthesis.kcp.KcpRepository.KcpUnitRow> unitByPath =
                    new java.util.HashMap<>();
            java.util.Set<String> supersededUnitIds = new java.util.HashSet<>();

            for (var manifest : manifests) {
                List<io.exoreaction.synthesis.kcp.KcpRepository.KcpUnitRow> units;
                if (includeAllTemporal) {
                    units = kcpRepo.getUnitsForManifest(conn, workspacePath, manifest.filePath());
                } else {
                    units = kcpRepo.getUnitsForManifest(conn, workspacePath, manifest.filePath());
                }
                for (var unit : units) {
                    if (unit.path() != null) {
                        unitByPath.put(unit.path(), unit);
                    }
                }

                // Collect superseded units
                if (asOf != null) {
                    supersededUnitIds.addAll(
                            kcpRepo.getSupersededUnitIds(conn, workspacePath, manifest.filePath(), asOf));
                }
            }

            if (unitByPath.isEmpty()) return;

            // Enrich each result
            JsonNode resultsArray = response.get("results");
            if (resultsArray == null || !resultsArray.isArray()) return;

            int temporallyExcluded = 0;
            for (JsonNode resultNode : resultsArray) {
                if (!(resultNode instanceof ObjectNode resultObj)) continue;

                String relativePath = resultObj.has("relativePath")
                        ? resultObj.get("relativePath").asText() : null;
                if (relativePath == null) continue;

                // Check if this result path matches a KCP unit path
                var matchedUnit = unitByPath.get(relativePath);
                if (matchedUnit == null) {
                    // Also try matching by filename only for fragment paths
                    String fileName = resultObj.has("fileName")
                            ? resultObj.get("fileName").asText() : null;
                    if (fileName != null) {
                        matchedUnit = unitByPath.get(fileName);
                    }
                }

                if (matchedUnit == null) continue;

                // Build temporal metadata object
                ObjectNode kcpMeta = mapper.createObjectNode();

                boolean active = true;
                if (asOf != null && !includeAllTemporal) {
                    // Check temporal validity
                    if (matchedUnit.validFrom() != null && matchedUnit.validFrom().compareTo(asOf) > 0) {
                        active = false; // not yet valid
                    }
                    if (matchedUnit.validUntil() != null && matchedUnit.validUntil().compareTo(asOf) < 0) {
                        active = false; // expired
                    }
                    // Check supersession
                    if (supersededUnitIds.contains(matchedUnit.unitId())) {
                        active = false;
                        kcpMeta.put("superseded", true);
                        kcpMeta.put("supersededBy", matchedUnit.supersededBy());
                    }
                }

                kcpMeta.put("active", active);
                if (!active) temporallyExcluded++;

                if (matchedUnit.validFrom() != null) {
                    kcpMeta.put("validFrom", matchedUnit.validFrom());
                }
                if (matchedUnit.validUntil() != null) {
                    kcpMeta.put("validUntil", matchedUnit.validUntil());
                }
                if (matchedUnit.recordedAt() != null) {
                    kcpMeta.put("recordedAt", matchedUnit.recordedAt());
                }

                // Content integrity
                if (matchedUnit.contentHashAlgorithm() != null) {
                    ObjectNode integrity = mapper.createObjectNode();
                    integrity.put("algorithm", matchedUnit.contentHashAlgorithm());
                    integrity.put("hash", matchedUnit.contentHashValue());
                    kcpMeta.set("contentHash", integrity);
                }

                // Discovery provenance
                if (matchedUnit.verificationStatus() != null) {
                    ObjectNode provenance = mapper.createObjectNode();
                    provenance.put("verificationStatus", matchedUnit.verificationStatus());
                    if (matchedUnit.confidence() >= 0) {
                        provenance.put("confidence", matchedUnit.confidence());
                    }
                    if (matchedUnit.verifiedBy() != null) {
                        provenance.put("verifiedBy", matchedUnit.verifiedBy());
                    }
                    kcpMeta.set("provenance", provenance);
                }

                // Content structure
                if (matchedUnit.contentStructurePrimary() != null) {
                    ObjectNode structure = mapper.createObjectNode();
                    structure.put("primary", matchedUnit.contentStructurePrimary());
                    if (matchedUnit.contentStructureDensity() != null) {
                        structure.put("density", matchedUnit.contentStructureDensity());
                    }
                    kcpMeta.set("contentStructure", structure);
                }

                resultObj.set("kcpTemporal", kcpMeta);
            }

            // Add summary to response
            if (temporallyExcluded > 0) {
                response.put("kcpTemporallyInactive", temporallyExcluded);
            }
            if (asOf != null) {
                response.put("kcpAsOf", asOf);
            }

        } catch (Exception e) {
            // KCP enrichment is best-effort; do not fail the search
            LOG.fine("KCP temporal enrichment skipped: " + e.getMessage());
        }
    }

    /**
     * Performs search across all configured workspaces, with optional sub-workspace scoping.
     */
    private ObjectNode handleMultiWorkspaceSearch(String query, String fileType,
                                                    String subWorkspace,
                                                    int limit, int previewLength,
                                                    long startTime) throws McpToolException {
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
                    ObjectNode item = buildSearchResultNode(result, query, previewLength);
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

            // Record metrics and query log
            metrics.recordMcpInvocation("search", "multi:" + allWorkspaces.size(),
                    elapsedMs, multiResult.totalResults(), true, null);
            queryLogger.log(query, "multi:" + allWorkspaces.size(), multiResult.totalResults(), elapsedMs);

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
    private ObjectNode buildSearchResultNode(SearchResult result, String query, int previewLength) {
        ObjectNode item = mapper.createObjectNode();
        item.put("path", result.path().toString());
        item.put("relativePath", result.relativePath());
        item.put("type", result.fileType() != null ? result.fileType() : "UNKNOWN");
        item.put("score", Math.round(result.score() * 100.0) / 100.0);
        item.put("fileName", result.fileName());

        if (!result.summary().isEmpty()) {
            item.put("snippet", smartExcerpt(result.summary(), query, previewLength));
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
     * Includes workspace-level freshness metadata (scan age, confidence).
     */
    private ObjectNode buildSearchResponse(List<SearchResult> results, long elapsedMs,
                                           String workspace, String query, int previewLength) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode resultsArray = mapper.createArrayNode();

        for (SearchResult result : results) {
            resultsArray.add(buildSearchResultNode(result, query, previewLength));
        }

        response.set("results", resultsArray);
        response.put("totalHits", results.size());
        response.put("searchTime", String.format("%.1fs", elapsedMs / 1000.0));
        response.put("workspace", workspace);

        // Add freshness metadata from scan state
        addFreshnessMetadata(response, Path.of(workspace));

        return response;
    }

    /**
     * Adds workspace-level freshness metadata to a search response.
     * Loads the scan state to determine when the workspace was last scanned,
     * and computes a confidence score based on scan age.
     *
     * <p>Confidence heuristic:
     * <ul>
     *   <li>scanAge &lt;= 1 day: 1.0</li>
     *   <li>scanAge &lt;= 7 days: 0.9</li>
     *   <li>scanAge &lt;= 30 days: 0.75</li>
     *   <li>older: 0.5</li>
     * </ul>
     */
    void addFreshnessMetadata(ObjectNode response, Path workspacePath) {
        try {
            WorkspaceManager workspace = new WorkspaceManager(workspacePath);
            Path scanStatePath = workspace.getScanStatePath();

            if (!ScanState.exists(scanStatePath)) {
                response.put("confidence", 0.5);
                response.put("scanAgeDays", -1);
                return;
            }

            ScanState scanState = ScanState.load(scanStatePath);
            Instant lastScan = scanState.getLastScanTime();

            if (lastScan != null) {
                response.put("workspaceLastScan", lastScan.toString());
                long ageDays = Duration.between(lastScan, Instant.now()).toDays();
                response.put("scanAgeDays", ageDays);
                response.put("confidence", computeConfidence(ageDays));
            } else {
                response.put("confidence", 0.5);
                response.put("scanAgeDays", -1);
            }
        } catch (Exception e) {
            // If we can't load scan state, still return the response without freshness
            LOG.fine("Could not load scan state for freshness metadata: " + e.getMessage());
            response.put("confidence", 0.5);
            response.put("scanAgeDays", -1);
        }
    }

    /**
     * Computes a confidence score (0.0-1.0) based on how many days since the last scan.
     */
    static double computeConfidence(long scanAgeDays) {
        if (scanAgeDays <= 1) return 1.0;
        if (scanAgeDays <= 7) return 0.9;
        if (scanAgeDays <= 30) return 0.75;
        return 0.5;
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
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                targetResults = index.search(filePath, 10);
            }

            // Use RelationService's matching logic
            RelationService relateCmd = new RelationService();
            SearchResult target = relateCmd.findBestMatch(targetResults, filePath);
            if (target == null) {
                throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                        "File not found in index: " + filePath);
            }

            // Get all files for cross-reference analysis
            List<SearchResult> allFiles;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                allFiles = index.listAll(null, 5000);
            }

            // Build file name index
            Map<String, List<String>> fileNameIndex = new HashMap<>();
            for (SearchResult f : allFiles) {
                fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>()).add(f.relativePath());
            }

            // Analyze relationships
            RelationService.RelationshipMap relationshipMap = new RelationService.RelationshipMap(target.relativePath());
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

            // Knowledge graph enrichment — documentation coverage and confidence
            try {
                io.exoreaction.synthesis.db.SynthesisDatabase keDb =
                    io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
                io.exoreaction.synthesis.graph.KnowledgeEnricher enricher =
                    new io.exoreaction.synthesis.graph.KnowledgeEnricher();
                io.exoreaction.synthesis.graph.KnowledgeEnricher.EnrichmentResult enrichment =
                    enricher.enrichForSource(target.relativePath(), keDb.getConnection());

                ObjectNode docNode = mapper.createObjectNode();
                docNode.put("hasGap", enrichment.hasGap());
                docNode.put("overallConfidence", enrichment.overallConfidence());
                ArrayNode skills = mapper.createArrayNode();
                for (Map.Entry<String, List<io.exoreaction.synthesis.graph.KnowledgeEdge>> e
                        : enrichment.bySkill().entrySet()) {
                    ObjectNode skillNode = mapper.createObjectNode();
                    skillNode.put("skillPath", e.getKey());
                    String worstConf = e.getValue().stream()
                        .map(io.exoreaction.synthesis.graph.KnowledgeEdge::confidence)
                        .min(java.util.Comparator.comparingInt(c -> switch (c) {
                            case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1; default -> 0;
                        })).orElse("NONE");
                    skillNode.put("confidence", worstConf);
                    int maxDrift = e.getValue().stream()
                        .mapToInt(io.exoreaction.synthesis.graph.KnowledgeEdge::driftDays)
                        .max().orElse(0);
                    skillNode.put("driftDays", maxDrift);
                    ArrayNode entities = mapper.createArrayNode();
                    e.getValue().stream()
                        .map(io.exoreaction.synthesis.graph.KnowledgeEdge::entityName)
                        .filter(n -> n != null && !n.isBlank())
                        .forEach(entities::add);
                    skillNode.set("coveredEntities", entities);
                    skills.add(skillNode);
                }
                docNode.set("skills", skills);
                response.set("documentation", docNode);
            } catch (Exception ignored) {
                // Enrichment is best-effort; never fail relate
            }

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
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
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
        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
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

            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
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

                // Enrich with session history (episodic memory)
                String sessionContext = buildSessionContext(query);

                // Generate answer using the ask prompt
                String prompt = sessionContext.isEmpty()
                        ? io.exoreaction.synthesis.ai.PromptTemplates.buildAskPrompt(query, context.toString())
                        : io.exoreaction.synthesis.ai.PromptTemplates.buildAskPrompt(query, context.toString(), sessionContext);
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
    // Session history helpers
    // -----------------------------------------------------------------------

    /**
     * Searches episodic memory for sessions relevant to the query.
     * Returns an empty string if no sessions are found or the database is unavailable.
     */
    private String buildSessionContext(String query) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            SessionStore store = new SessionStore(db);
            List<ClaudeSession> sessions = store.search(sanitizeFtsQuery(query), 3);
            if (sessions.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ClaudeSession s : sessions) {
                sb.append("\n--- Session: ")
                  .append(s.sessionId().length() > 8 ? s.sessionId().substring(0, 8) + "..." : s.sessionId());
                if (s.startedAt() != null) {
                    sb.append(" (").append(s.startedAt().toString(), 0, 10).append(")");
                }
                if (s.projectDir() != null) {
                    sb.append(" [").append(s.projectDir()).append("]");
                }
                sb.append(" ---\n");
                if (s.allUserText() != null && !s.allUserText().isBlank()) {
                    sb.append(s.allUserText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // Sessions DB not available — proceed without session context
            return "";
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
        boolean dryRun = params != null && params.has("dryRun")
                && params.get("dryRun").asBoolean(false);

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

                if (dryRun) {
                    ObjectNode response = mapper.createObjectNode();
                    response.put("generated", false);
                    response.put("dryRun", true);
                    response.put("sourcePath", resolved.toString());
                    response.put("message", "dryRun=true: no files written");
                    response.put("level", level.name());
                    return response;
                }

                Optional<Path> companionPath = generator.generate(metadata, analysis, List.of());

                ObjectNode response = mapper.createObjectNode();
                response.put("generated", companionPath.isPresent());
                response.put("dryRun", false);
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

                try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
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

                            if (dryRun) {
                                skipped++;
                                continue;
                            }

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
                response.put("dryRun", dryRun);
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

            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
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

            // Phase 3: Check cache (bypass when 'since' is provided — temporal results are always fresh)
            String indexFingerprint = io.exoreaction.synthesis.summary.SummaryCache
                .generateIndexFingerprint(workspace.getIndexPath());
            io.exoreaction.synthesis.summary.SummaryResult result = null;
            boolean useCache = !noCache && (since == null || since.isBlank());

            if (useCache) {
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
                try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
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

                // Store in cache (skip for temporal results — they are always fresh)
                if (useCache) {
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


    // -----------------------------------------------------------------------
    // Tool: changelog
    // -----------------------------------------------------------------------

    /**
     * Returns workspace change history for a given period.
     *
     * @param params JSON object with: since (default "24h"), workspace
     * @return JSON object with: period, workspace, report (string)
     */
    public ObjectNode handleChangelog(JsonNode params) throws McpToolException {
        String since = params != null && params.has("since") && !params.get("since").isNull()
                ? params.get("since").asText() : "24h";
        Path workspacePath = resolveWorkspace(params);

        try {
            // Try direct Java API first
            java.time.Instant sinceInstant = io.exoreaction.synthesis.cli.ChangedCommand.parseSince(since);
            if (sinceInstant != null) {
                io.exoreaction.synthesis.db.SynthesisDatabase db =
                        io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
                io.exoreaction.synthesis.changelog.SnapshotManager snapshots =
                        new io.exoreaction.synthesis.changelog.SnapshotManager(db);
                List<io.exoreaction.synthesis.changelog.ChangeEvent> events =
                        snapshots.getChangesForWorkspace(workspacePath.toString(), sinceInstant);

                io.exoreaction.synthesis.changelog.ChangeReportGenerator generator =
                        new io.exoreaction.synthesis.changelog.ChangeReportGenerator();
                String report = generator.generateReport(events, sinceInstant, java.time.Instant.now(), null);

                ObjectNode response = mapper.createObjectNode();
                response.put("period", since);
                response.put("workspace", workspacePath.toString());
                response.put("report", report);
                return response;
            }
        } catch (Exception e) {
            LOG.fine("Direct changelog API failed, falling back to CLI: " + e.getMessage());
        }

        // Fallback: subprocess
        try {
            String output = runSynthesisCli(List.of("changelog", "--since=" + since), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("period", since);
            response.put("workspace", workspacePath.toString());
            response.put("report", output);
            return response;
        } catch (Exception e) {
            LOG.warning("Changelog failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Changelog failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: report
    // -----------------------------------------------------------------------

    /**
     * Generates a business report using AI analysis.
     *
     * @param params JSON object with: topic, target, period, noCache, workspace
     * @return JSON object with: report (string), topic, target, workspace
     */
    public ObjectNode handleReport(JsonNode params) throws McpToolException {
        String topic = params != null && params.has("topic") && !params.get("topic").isNull()
                ? params.get("topic").asText() : "weekly";
        String target = params != null && params.has("target") && !params.get("target").isNull()
                ? params.get("target").asText() : "ceo";
        String period = params != null && params.has("period") && !params.get("period").isNull()
                ? params.get("period").asText() : "1w";
        boolean noCache = params != null && params.has("noCache")
                && params.get("noCache").asBoolean(false);
        Path workspacePath = resolveWorkspace(params);

        try {
            // Try direct Java API
            SynthesisConfig config = ConfigLoader.load(workspacePath);
            Optional<io.exoreaction.synthesis.ai.ClaudeClient> clientOpt =
                    io.exoreaction.synthesis.ai.ClaudeClient.create(config.getAi());

            if (clientOpt.isPresent()) {
                io.exoreaction.synthesis.report.ReportTarget reportTarget =
                        io.exoreaction.synthesis.report.ReportTarget.valueOf(target.toUpperCase());
                io.exoreaction.synthesis.report.ReportTopic reportTopic =
                        io.exoreaction.synthesis.report.ReportTopic.valueOf(topic.toUpperCase());

                io.exoreaction.synthesis.report.ReportEngine engine =
                        new io.exoreaction.synthesis.report.ReportEngine(clientOpt.get(), config.getAi().getMaxTokens());
                io.exoreaction.synthesis.report.ReportResult result =
                        engine.generate(workspacePath, reportTarget, reportTopic, period, false);

                ObjectNode response = mapper.createObjectNode();
                response.put("report", result.finalReport());
                response.put("topic", topic);
                response.put("target", target);
                response.put("workspace", workspacePath.toString());
                return response;
            }
        } catch (IllegalArgumentException e) {
            // Invalid enum value — fall through to subprocess
            LOG.fine("Invalid report parameter: " + e.getMessage());
        } catch (Exception e) {
            LOG.fine("Direct report API failed, falling back to CLI: " + e.getMessage());
        }

        // Fallback: subprocess
        try {
            List<String> args = new java.util.ArrayList<>();
            args.add("report");
            args.add("--topic");
            args.add(topic);
            if (noCache) args.add("--no-cache");
            String output = runSynthesisCli(args, workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("report", output);
            response.put("topic", topic);
            response.put("target", target);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (Exception e) {
            LOG.warning("Report failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Report failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: health
    // -----------------------------------------------------------------------

    /**
     * Runs workspace structural health audit.
     *
     * @param params JSON object with: workspace
     * @return JSON object with: score, grade, errorCount, warningCount, issues[], workspace
     */
    public ObjectNode handleHealth(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);

        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);

            List<io.exoreaction.synthesis.cli.HealthCommand.HealthIssue> issues = new ArrayList<>();

            // E001: Phantom sub-workspace paths
            var phantoms = io.exoreaction.synthesis.cli.HealthCommand.findPhantomSubWorkspaces(workspacePath, config);
            if (!phantoms.isEmpty()) {
                issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                        io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.ERROR,
                        "E001",
                        phantoms.size() + " phantom sub-workspace path(s) in config",
                        "synthesis health --fix-config"));
            }

            // E002: Build artifacts
            var artifacts = io.exoreaction.synthesis.cli.HealthCommand.findBuildArtifacts(workspacePath);
            if (!artifacts.isEmpty()) {
                issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                        io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.ERROR,
                        "E002",
                        "Build artifacts found: " + artifacts.size() + " location(s)"));
            }

            // W001: Empty directories
            var emptyDirs = io.exoreaction.synthesis.cli.HealthCommand.findEmptyDirectories(workspacePath);
            if (!emptyDirs.isEmpty()) {
                issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                        io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.WARNING,
                        "W001",
                        emptyDirs.size() + " empty director" + (emptyDirs.size() == 1 ? "y" : "ies")));
            }

            // W002: Excessive loose root files
            int looseFiles = io.exoreaction.synthesis.cli.HealthCommand.countLooseRootFiles(workspacePath);
            if (looseFiles > 3) {
                issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                        io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.WARNING,
                        "W002",
                        looseFiles + " files at workspace root (expected: 1-3)",
                        "synthesis sweep"));
            }

            // K001-K004: KCP integrity checks
            try {
                io.exoreaction.synthesis.db.SynthesisDatabase db =
                        io.exoreaction.synthesis.db.SynthesisDatabase.getDefaultIfExists();
                if (db != null) {
                    java.sql.Connection conn = db.getConnection();
                    io.exoreaction.synthesis.kcp.KcpRepository kcpRepo =
                            new io.exoreaction.synthesis.kcp.KcpRepository();
                    var manifests = kcpRepo.getManifests(conn, workspacePath.toString());
                    String today = java.time.LocalDate.now().toString();
                    int hashMismatches = 0, expiredNoSuccessor = 0, danglingSupersededBy = 0, rumoredUnits = 0;

                    for (var manifest : manifests) {
                        var units = kcpRepo.getUnitsForManifest(conn, workspacePath.toString(), manifest.filePath());
                        java.util.Set<String> unitIds = new java.util.HashSet<>();
                        for (var unit : units) unitIds.add(unit.unitId());

                        for (var unit : units) {
                            if (unit.validUntil() != null && unit.validUntil().compareTo(today) < 0
                                    && unit.supersededBy() == null) {
                                expiredNoSuccessor++;
                            }
                            if (unit.supersededBy() != null && !unitIds.contains(unit.supersededBy())) {
                                danglingSupersededBy++;
                            }
                            if ("rumored".equals(unit.verificationStatus())) {
                                rumoredUnits++;
                            }
                        }
                    }

                    if (hashMismatches > 0)
                        issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                                io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.ERROR,
                                "K001", hashMismatches + " KCP unit(s) with content hash mismatch"));
                    if (expiredNoSuccessor > 0)
                        issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                                io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.WARNING,
                                "K002", expiredNoSuccessor + " expired KCP unit(s) with no successor"));
                    if (danglingSupersededBy > 0)
                        issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                                io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.ERROR,
                                "K003", danglingSupersededBy + " KCP unit(s) with dangling superseded_by"));
                    if (rumoredUnits > 0)
                        issues.add(new io.exoreaction.synthesis.cli.HealthCommand.HealthIssue(
                                io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.INFO,
                                "K004", rumoredUnits + " KCP unit(s) with verification_status: rumored"));
                }
            } catch (Exception kcpEx) {
                // KCP health checks are best-effort
            }

            int score = io.exoreaction.synthesis.cli.HealthCommand.calculateScore(issues);
            String grade = io.exoreaction.synthesis.cli.HealthCommand.scoreGrade(score);

            long errorCount = issues.stream()
                    .filter(i -> i.severity() == io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.ERROR)
                    .count();
            long warningCount = issues.stream()
                    .filter(i -> i.severity() == io.exoreaction.synthesis.cli.HealthCommand.HealthIssue.Severity.WARNING)
                    .count();

            ObjectNode response = mapper.createObjectNode();
            response.put("score", score);
            response.put("grade", grade);
            response.put("errorCount", errorCount);
            response.put("warningCount", warningCount);
            response.put("workspace", workspacePath.toString());

            ArrayNode issuesArray = mapper.createArrayNode();
            for (var issue : issues) {
                ObjectNode issueNode = mapper.createObjectNode();
                issueNode.put("severity", issue.severity().name());
                issueNode.put("code", issue.code());
                issueNode.put("description", issue.description());
                if (issue.fix() != null) {
                    issueNode.put("fix", issue.fix());
                }
                if (!issue.details().isEmpty()) {
                    ArrayNode details = mapper.createArrayNode();
                    for (String detail : issue.details()) {
                        details.add(detail);
                    }
                    issueNode.set("details", details);
                }
                issuesArray.add(issueNode);
            }
            response.set("issues", issuesArray);

            return response;
        } catch (Exception e) {
            LOG.warning("Health check failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Health check failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: security
    // -----------------------------------------------------------------------

    /**
     * Returns security analysis findings for a workspace.
     *
     * @param params JSON object with: severity, refresh, format, workspace
     * @return JSON object with: totalCount, highCount, mediumCount, lowCount, summary, workspace
     */
    public ObjectNode handleSecurity(JsonNode params) throws McpToolException {
        String severity = params != null && params.has("severity") && !params.get("severity").isNull()
                ? params.get("severity").asText() : null;
        boolean refresh = params != null && params.has("refresh")
                && params.get("refresh").asBoolean(false);
        String format = params != null && params.has("format") && !params.get("format").isNull()
                ? params.get("format").asText() : "summary";
        Path workspacePath = resolveWorkspace(params);

        try {
            io.exoreaction.synthesis.db.SynthesisDatabase db =
                    io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
            java.sql.Connection conn = db.getConnection();
            io.exoreaction.synthesis.graph.SecurityRepository repo =
                    new io.exoreaction.synthesis.graph.SecurityRepository();

            // Optionally refresh by running analysis
            if (refresh) {
                try {
                    String output = runSynthesisCli(List.of("code-graph", "security"), workspacePath);
                    // Ignore output — we'll query the DB for fresh data
                } catch (Exception e) {
                    LOG.fine("Security refresh via CLI failed: " + e.getMessage());
                }
            }

            Map<String, Integer> counts = repo.countFindingsBySeverity(conn, workspacePath.toString());
            int total = repo.countFindings(conn, workspacePath.toString());
            int high = counts.getOrDefault("HIGH", 0);
            int medium = counts.getOrDefault("MEDIUM", 0);
            int low = counts.getOrDefault("LOW", 0);
            int info = counts.getOrDefault("INFO", 0);

            ObjectNode response = mapper.createObjectNode();
            response.put("totalCount", total);
            response.put("highCount", high);
            response.put("mediumCount", medium);
            response.put("lowCount", low);
            response.put("infoCount", info);
            response.put("workspace", workspacePath.toString());

            if ("json".equalsIgnoreCase(format)) {
                List<io.exoreaction.synthesis.graph.SecuritySignal> findings;
                if (severity != null && !severity.isBlank()) {
                    findings = repo.getFindingsBySeverity(conn, workspacePath.toString(), severity.toUpperCase());
                } else {
                    findings = repo.getFindings(conn, workspacePath.toString());
                }

                ArrayNode findingsArray = mapper.createArrayNode();
                for (var signal : findings) {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("signalId", signal.signalId());
                    node.put("severity", signal.severity());
                    node.put("filePath", signal.filePath());
                    node.put("description", signal.description());
                    if (signal.suggestion() != null) {
                        node.put("suggestion", signal.suggestion());
                    }
                    findingsArray.add(node);
                }
                response.set("findings", findingsArray);
            } else {
                // Summary format
                StringBuilder summary = new StringBuilder();
                summary.append("Security Analysis: ").append(total).append(" finding(s)\n");
                summary.append("  HIGH: ").append(high).append("\n");
                summary.append("  MEDIUM: ").append(medium).append("\n");
                summary.append("  LOW: ").append(low).append("\n");
                summary.append("  INFO: ").append(info).append("\n");
                response.put("summary", summary.toString());
            }

            return response;
        } catch (Exception e) {
            LOG.warning("Security analysis failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Security analysis failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: impact
    // -----------------------------------------------------------------------

    /**
     * Transitive change impact analysis for a file.
     *
     * @param params JSON object with: filePath (required), depth, workspace
     * @return JSON object with: target, totalImpact, riskLevel, report (string), workspace
     */
    public ObjectNode handleImpact(JsonNode params) throws McpToolException {
        if (params == null || !params.has("filePath")) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: filePath");
        }

        String filePath = params.get("filePath").asText();
        if (filePath == null || filePath.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Parameter 'filePath' must not be empty");
        }

        int depth = params.has("depth") ? params.get("depth").asInt(3) : 3;
        if (depth < 1) depth = 1;
        if (depth > 10) depth = 10;

        Path workspacePath = resolveWorkspace(params);

        // Use subprocess — ImpactCommand uses picocli injection and complex BFS
        try {
            List<String> args = new java.util.ArrayList<>();
            args.add("impact");
            args.add(filePath);
            args.add("--depth");
            args.add(String.valueOf(depth));
            args.add("--format");
            args.add("text");
            String output = runSynthesisCli(args, workspacePath);

            // Parse basic metrics from output
            int totalImpact = 0;
            String riskLevel = "UNKNOWN";
            for (String line : output.split("\n")) {
                if (line.contains("Total impact:")) {
                    try {
                        totalImpact = Integer.parseInt(line.replaceAll("[^0-9]", ""));
                    } catch (NumberFormatException ignored) {}
                }
                if (line.contains("Risk level:") || line.contains("Risk:")) {
                    riskLevel = line.replaceAll(".*(?:Risk level:|Risk:)\\s*", "").trim();
                }
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("target", filePath);
            response.put("totalImpact", totalImpact);
            response.put("riskLevel", riskLevel);
            response.put("report", output);
            response.put("depth", depth);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Impact analysis failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "Impact analysis failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: export
    // -----------------------------------------------------------------------

    /**
     * Exports the workspace index in various formats.
     *
     * @param params JSON object with: format, fileType, limit, workspace
     * @return JSON object with: content (string), format, fileCount, workspace
     */
    public ObjectNode handleExport(JsonNode params) throws McpToolException {
        String format = params != null && params.has("format") && !params.get("format").isNull()
                ? params.get("format").asText() : "markdown";
        String fileType = params != null && params.has("fileType") && !params.get("fileType").isNull()
                ? params.get("fileType").asText() : null;
        int limit = params != null && params.has("limit") ? params.get("limit").asInt(1000) : 1000;
        if (limit < 1) limit = 1;
        if (limit > 50000) limit = 50000;

        Path workspacePath = resolveWorkspace(params);
        WorkspaceManager workspace = validateWorkspace(workspacePath);

        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);

            List<SearchResult> results;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                results = index.listAll(fileType, limit);
            }

            if (results.isEmpty()) {
                throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                        "No files in index. Run 'synthesis scan' first.");
            }

            String content = io.exoreaction.synthesis.cli.ExportCommand.exportContent(
                    format, config, results, workspacePath, fileType);

            if (content == null) {
                throw new McpToolException(JsonRpcMessage.INVALID_PARAMS,
                        "Unknown export format: " + format
                                + ". Use 'markdown', 'json', 'architecture-doc', 'onboarding-guide', or 'kcp'.");
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("content", content);
            response.put("format", format);
            response.put("fileCount", results.size());
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Export failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "Export failed: " + e.getMessage());
        }
    }


    // -----------------------------------------------------------------------
    // Group 1: Analysis tools (subprocess-based)
    // -----------------------------------------------------------------------

    public ObjectNode handleAnalyze(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("analyze"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("analysis", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("analyze failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "analyze failed: " + e.getMessage());
        }
    }

    public ObjectNode handleInsights(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("insights"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("insights", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("insights failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "insights failed: " + e.getMessage());
        }
    }

    public ObjectNode handlePerspectives(JsonNode params) throws McpToolException {
        if (params == null || !params.has("question") || params.get("question").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: question");
        String question = params.get("question").asText();
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("perspectives", question), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("perspectives", output);
            response.put("question", question);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("perspectives failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "perspectives failed: " + e.getMessage());
        }
    }

    public ObjectNode handleResearch(JsonNode params) throws McpToolException {
        if (params == null || !params.has("query") || params.get("query").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: query");
        String query = params.get("query").asText();
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("research", query), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("research", output);
            response.put("query", query);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("research failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "research failed: " + e.getMessage());
        }
    }

    public ObjectNode handleArchitecture(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("architecture"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("architecture", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("architecture failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "architecture failed: " + e.getMessage());
        }
    }

    public ObjectNode handleCodeGraph(JsonNode params) throws McpToolException {
        String subcommand = params != null && params.has("subcommand") ? params.get("subcommand").asText("") : "";
        String flags = params != null && params.has("flags") ? params.get("flags").asText("") : "";
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            List<String> args = new ArrayList<>();
            args.add("code-graph");
            if (!subcommand.isBlank()) args.add(subcommand);
            if (!flags.isBlank()) {
                for (String f : flags.split("\\s+")) if (!f.isBlank()) args.add(f);
            }
            String output = runSynthesisCli(args, workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("graph", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("code-graph failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "code-graph failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Group 2: Workspace intelligence tools (subprocess-based)
    // -----------------------------------------------------------------------

    public ObjectNode handleDescribe(JsonNode params) throws McpToolException {
        String path = params != null && params.has("path") ? params.get("path").asText("") : "";
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            List<String> args = new ArrayList<>();
            args.add("describe");
            if (!path.isBlank()) args.add(path);
            String output = runSynthesisCli(args, workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("description", output);
            if (!path.isBlank()) response.put("path", path);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("describe failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "describe failed: " + e.getMessage());
        }
    }

    public ObjectNode handleKnowledgeGraph(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            List<String> args = new ArrayList<>(List.of("kg"));

            // Pass --scope if provided
            JsonNode filterNode = params.get("filter");
            if (filterNode != null && !filterNode.asText().isBlank()) {
                args.add("--scope");
                args.add(filterNode.asText());
            }

            // Pass --format if provided, default to mermaid
            JsonNode formatNode = params.get("format");
            String fmt = (formatNode != null && !formatNode.asText().isBlank())
                    ? formatNode.asText() : "mermaid";
            args.add("--format");
            args.add(fmt);

            String output = runSynthesisCli(args, workspacePath);

            // Safety net: truncate if output exceeds ~200K characters
            final int MAX_CHARS = 200_000;
            if (output.length() > MAX_CHARS) {
                output = output.substring(0, MAX_CHARS) +
                        "\n\n[Output truncated at " + MAX_CHARS + " characters. " +
                        "Use the 'filter' parameter to scope the graph to a specific directory or subsystem.]";
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("knowledgeGraph", output);
            response.put("workspace", workspacePath.toString());
            if (filterNode != null && !filterNode.asText().isBlank()) {
                response.put("filter", filterNode.asText());
            }
            response.put("format", fmt);
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("knowledge-graph failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "knowledge-graph failed: " + e.getMessage());
        }
    }

    public ObjectNode handleStructure(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("structure"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("structure", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("structure failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "structure failed: " + e.getMessage());
        }
    }

    public ObjectNode handleEvolution(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("evo"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("evolution", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("evolution failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "evolution failed: " + e.getMessage());
        }
    }

    public ObjectNode handleScatter(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("scatter"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("scatter", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("scatter failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "scatter failed: " + e.getMessage());
        }
    }

    public ObjectNode handleNaming(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("naming"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("naming", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("naming failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "naming failed: " + e.getMessage());
        }
    }

    public ObjectNode handleUpcoming(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("upcoming"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("upcoming", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("upcoming failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "upcoming failed: " + e.getMessage());
        }
    }

    public ObjectNode handleStatus(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("status"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("status", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("status failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "status failed: " + e.getMessage());
        }
    }

    public ObjectNode handleMcpStats(JsonNode params) throws McpToolException {
        try {
            String synthesisBin = System.getProperty("user.home") + "/.synthesis/bin/synthesis";
            ProcessBuilder pb = new ProcessBuilder(List.of(synthesisBin, "mcp-stats"));
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                String err = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "mcp-stats failed: " + err.trim());
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("stats", output);
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("mcp-stats failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "mcp-stats failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Group 3: Change tracking tools (subprocess-based)
    // -----------------------------------------------------------------------

    public ObjectNode handleDiff(JsonNode params) throws McpToolException {
        if (params == null || !params.has("ref") || params.get("ref").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: ref");
        String ref = params.get("ref").asText();
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("diff", ref), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("diff", output);
            response.put("ref", ref);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("diff failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "diff failed: " + e.getMessage());
        }
    }

    public ObjectNode handleChanged(JsonNode params) throws McpToolException {
        if (params == null || !params.has("since") || params.get("since").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: since");
        String since = params.get("since").asText();
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("changed", "--since=" + since), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("changed", output);
            response.put("since", since);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("changed failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "changed failed: " + e.getMessage());
        }
    }

    public ObjectNode handleTrack(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("track"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("tracking", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("track failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "track failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool: sessions (episodic memory)
    // -----------------------------------------------------------------------

    /**
     * Searches or lists indexed Claude Code session history.
     *
     * @param params JSON object with: action ("search"|"list"), query, project, since, limit
     * @return JSON object with: action, sessions (array), count
     */
    public ObjectNode handleSessions(JsonNode params) throws McpToolException {
        String action = params != null && params.has("action") && !params.get("action").isNull()
                ? params.get("action").asText() : "list";
        int limit = params != null && params.has("limit") ? params.get("limit").asInt(10) : 10;
        boolean includeSubagents = params != null && params.has("includeSubagents")
                && params.get("includeSubagents").asBoolean(false);

        try {
            List<String> args = new java.util.ArrayList<>();
            args.add("sessions");

            if ("search".equals(action)) {
                // Search always includes subagents — they contain valuable knowledge
                String query = params != null && params.has("query") && !params.get("query").isNull()
                        ? params.get("query").asText() : "";
                args.add("search");
                args.add(query);
                args.add("--limit=" + limit);
            } else {
                args.add("list");
                args.add("--limit=" + limit);
                if (includeSubagents) {
                    args.add("--include-subagents");
                }
                if (params != null && params.has("project") && !params.get("project").isNull()) {
                    args.add("--project=" + params.get("project").asText());
                }
                if (params != null && params.has("since") && !params.get("since").isNull()) {
                    args.add("--since=" + params.get("since").asText());
                }
            }

            String output = runSynthesisCli(args, defaultWorkspace);
            ObjectNode response = mapper.createObjectNode();
            response.put("action", action);
            response.put("sessions", output);
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("sessions failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "sessions failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Group 6: Session lifecycle tools
    // -----------------------------------------------------------------------

    public ObjectNode handleSessionContext(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        String since = params != null && params.has("since") && !params.get("since").isNull()
                ? params.get("since").asText() : "24h";
        boolean compact = params == null || !params.has("compact") || params.get("compact").asBoolean(true);
        try {
            List<String> args = new java.util.ArrayList<>();
            args.add("session-context");
            if (compact) args.add("--compact");
            args.add("--since=" + since);
            String output = runSynthesisCli(args, workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("context", output);
            response.put("workspace", workspacePath.toString());
            response.put("since", since);
            response.put("compact", compact);
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("session-context failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "session-context failed: " + e.getMessage());
        }
    }

    public ObjectNode handleHooksGenerate(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        String hookType = params != null && params.has("type") && !params.get("type").isNull()
                ? params.get("type").asText() : "UserPromptSubmit";
        try {
            List<String> args = List.of("hooks", "generate", "--dry-run", "--type", hookType);
            String output = runSynthesisCli(args, workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("hookConfig", output);
            response.put("type", hookType);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("hooks generate failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "hooks generate failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Group 4: Discovery & validation tools (subprocess-based)
    // -----------------------------------------------------------------------

    public ObjectNode handleWhich(JsonNode params) throws McpToolException {
        if (params == null || !params.has("pattern") || params.get("pattern").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: pattern");
        String pattern = params.get("pattern").asText();
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("which", pattern), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("result", output);
            response.put("pattern", pattern);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("which failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "which failed: " + e.getMessage());
        }
    }

    public ObjectNode handleDiscover(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("discover"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("discoveries", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("discover failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "discover failed: " + e.getMessage());
        }
    }

    public ObjectNode handleValidate(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("validate"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("validation", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("validate failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "validate failed: " + e.getMessage());
        }
    }

    public ObjectNode handleMetrics(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("metrics"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("metrics", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("metrics failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "metrics failed: " + e.getMessage());
        }
    }

    public ObjectNode handleTrace(JsonNode params) throws McpToolException {
        if (params == null || !params.has("from") || params.get("from").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: from");
        if (!params.has("to") || params.get("to").asText().isBlank())
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "Missing required parameter: to");
        String from = params.get("from").asText();
        String to = params.get("to").asText();
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("trace", from, to), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("trace", output);
            response.put("from", from);
            response.put("to", to);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("trace failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "trace failed: " + e.getMessage());
        }
    }

    public ObjectNode handleCrossRepoDeps(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("cross-repo-deps"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("dependencies", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("cross-repo-deps failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "cross-repo-deps failed: " + e.getMessage());
        }
    }

    public ObjectNode handleLearn(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("learn"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("learnings", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("learn failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "learn failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Group 5: Maintenance tools (subprocess-based)
    // -----------------------------------------------------------------------

    public ObjectNode handleMaintain(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("maintain"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("maintenance", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("maintain failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "maintain failed: " + e.getMessage());
        }
    }

    public ObjectNode handleScan(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);
        try {
            String output = runSynthesisCli(List.of("scan"), workspacePath);
            ObjectNode response = mapper.createObjectNode();
            response.put("scan", output);
            response.put("workspace", workspacePath.toString());
            return response;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("scan failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "scan failed: " + e.getMessage());
        }
    }
    // -----------------------------------------------------------------------
    // Subprocess helper
    // -----------------------------------------------------------------------

    /**
     * Runs a Synthesis CLI command as a subprocess, capturing stdout.
     *
     * @param args          command arguments (e.g., ["changelog", "--since=24h"])
     * @param workspacePath workspace directory to pass via -d flag
     * @return the stdout output as a string
     * @throws McpToolException if the process exits with a non-zero code
     */
    private String runSynthesisCli(java.util.List<String> args, Path workspacePath) throws Exception {
        String synthesisBin = System.getProperty("user.home") + "/.synthesis/bin/synthesis";
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(synthesisBin);
        cmd.addAll(args);
        cmd.add("-d");
        cmd.add(workspacePath.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            String err = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR,
                    "synthesis " + args.get(0) + " failed: " + err.trim());
        }
        return output;
    }
    /**
     * Returns a snippet of {@code text} of at most {@code maxLen} characters,
     * centred around the first occurrence of any term from {@code query}.
     *
     * <p>If no term is found the excerpt starts at position 0.
     * Leading/trailing ellipses (…) are added when text is cut.
     *
     * @param text    full text to excerpt from
     * @param query   search query (Lucene syntax — operators and field prefixes are stripped)
     * @param maxLen  maximum length of the returned excerpt
     */
    String smartExcerpt(String text, String query, int maxLen) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= maxLen) return text;

        // Extract individual terms: split on whitespace and Lucene boolean operators,
        // then strip punctuation that Lucene uses for syntax.
        String[] parts = query.split("[\\s]+|\\bAND\\b|\\bOR\\b|\\bNOT\\b");

        int matchPos = -1;
        for (String part : parts) {
            // Strip Lucene operator characters and field:value prefix
            String term = part.replaceAll("[+\\-*?~^\"()\\[\\]{}:\\\\]", "").trim().toLowerCase();
            if (term.length() < 2) continue;
            int pos = text.toLowerCase().indexOf(term);
            if (pos >= 0 && (matchPos < 0 || pos < matchPos)) {
                matchPos = pos;
            }
        }

        // Centre window around match (fall back to start of text)
        int center = matchPos >= 0 ? matchPos : 0;
        int half = maxLen / 2;
        int start = Math.max(0, center - half);
        int end = Math.min(text.length(), start + maxLen);
        // Adjust start if end was clamped
        start = Math.max(0, end - maxLen);

        String excerpt = text.substring(start, end);
        if (start > 0) excerpt = "\u2026" + excerpt;
        if (end < text.length()) excerpt = excerpt + "\u2026";
        return excerpt;
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

    // -----------------------------------------------------------------------
    // Group 7: Agent awareness tools
    // -----------------------------------------------------------------------

    /**
     * Builds a codebase-aware briefing for a Claude Code agent team.
     *
     * @param params JSON object with: team_name (optional), compact (optional), workspace (optional)
     * @return JSON object with: team, description, taskCount, compact, briefing
     */
    public ObjectNode handleTeamContext(JsonNode params) throws McpToolException {
        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);

        String teamName = params != null && params.has("team_name") && !params.get("team_name").isNull()
                ? params.get("team_name").asText() : null;
        boolean compact = params != null && params.has("compact") && params.get("compact").asBoolean(false);

        try {
            TeamContext context;
            if (teamName != null && !teamName.isBlank()) {
                context = TeamReader.read(teamName);
            } else {
                context = TeamReader.readAutoDetect();
            }

            WorkspaceManager workspace = new WorkspaceManager(workspacePath);
            Path skillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");

            TeamBriefing briefing;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                briefing = TeamContextBuilder.build(context, index, skillsDir);
            } catch (Exception e) {
                briefing = TeamContextBuilder.build(context, null, skillsDir);
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("team", context.teamName());
            response.put("description", context.description());
            response.put("taskCount", context.tasks().size());
            response.put("agentCount", context.agents().size());
            response.put("conflictCount", briefing.globalConflicts().size());
            response.put("briefing", compact ? briefing.toCompact() : briefing.toVerbose());
            response.put("compact", compact);
            return response;

        } catch (TeamNotFoundException e) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            LOG.warning("team_context failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "team_context failed: " + e.getMessage());
        }
    }

    /**
     * Finds Claude Code skills relevant to a task description.
     *
     * @param params JSON object with: query (required), top (optional), skills_dir (optional)
     * @return JSON object with: matches (array), count, skills_dir
     */
    public ObjectNode handleMatchSkills(JsonNode params) throws McpToolException {
        String query = params != null && params.has("query") && !params.get("query").isNull()
                ? params.get("query").asText() : "";
        if (query.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "query is required");
        }

        int top = params != null && params.has("top") ? params.get("top").asInt(5) : 5;

        Path skillsDir;
        if (params != null && params.has("skills_dir") && !params.get("skills_dir").isNull()) {
            skillsDir = Path.of(params.get("skills_dir").asText()).toAbsolutePath().normalize();
        } else {
            skillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        }

        try {
            List<SkillMatch> matches = SkillMatcher.match(skillsDir, query, top);

            ObjectNode response = mapper.createObjectNode();
            response.put("query", query);
            response.put("skills_dir", skillsDir.toString());
            response.put("count", matches.size());

            ArrayNode matchesArray = mapper.createArrayNode();
            for (SkillMatch m : matches) {
                ObjectNode item = mapper.createObjectNode();
                item.put("skill", m.skillName());
                item.put("file", m.filePath().toString());
                item.put("score", m.score());
                item.put("preview", m.firstLine());
                ArrayNode terms = mapper.createArrayNode();
                m.matchedTerms().forEach(terms::add);
                item.set("matched_terms", terms);
                matchesArray.add(item);
            }
            response.set("matches", matchesArray);

            return response;
        } catch (Exception e) {
            LOG.warning("match_skills failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "match_skills failed: " + e.getMessage());
        }
    }

    /**
     * Plans an agent dispatch: skills, related files, team conflicts, and token estimate.
     *
     * @param params JSON object with: query (required), top_skills (int, default 3),
     *               top_files (int, default 5), skills_dir (string, optional),
     *               workspace (string, optional)
     * @return JSON object with: query, skills, relatedFiles, conflicts, estimatedTokens, workspace
     */
    public ObjectNode handleDispatch(JsonNode params) throws McpToolException {
        String query = params != null && params.has("query") && !params.get("query").isNull()
                ? params.get("query").asText() : "";
        if (query.isBlank()) {
            throw new McpToolException(JsonRpcMessage.INVALID_PARAMS, "query is required");
        }

        int topSkills = params != null && params.has("top_skills") ? params.get("top_skills").asInt(3) : 3;
        int topFiles = params != null && params.has("top_files") ? params.get("top_files").asInt(5) : 5;

        Path skillsDir;
        if (params != null && params.has("skills_dir") && !params.get("skills_dir").isNull()) {
            skillsDir = Path.of(params.get("skills_dir").asText()).toAbsolutePath().normalize();
        } else {
            skillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        }

        Path workspacePath = resolveWorkspace(params);
        validateWorkspace(workspacePath);

        try {
            // Step 1: Skill matching
            List<SkillMatch> skillMatches = SkillMatcher.match(skillsDir, query, topSkills);

            // Step 2: Related files from index
            List<SearchResult> fileResults = List.of();
            try (SearchIndex index = SearchIndex.openReadOnly(new io.exoreaction.synthesis.core.WorkspaceManager(workspacePath).getIndexPath())) {
                fileResults = index.search(query, null, topFiles);
            } catch (Exception e) {
                // Index unavailable — continue without files
            }

            // Step 3: Team conflict check (graceful if no team)
            List<String> conflicts = new ArrayList<>();
            try {
                TeamContext teamCtx = TeamReader.readAutoDetect();
                TeamBriefing briefing = TeamContextBuilder.build(teamCtx, null, null);
                conflicts = briefing.globalConflicts();
            } catch (TeamNotFoundException ignored) {
                // No team — skip
            } catch (Exception ignored) {
                // Any other failure — skip
            }

            // Step 4: Token estimate
            long estimatedTokens = fileResults.stream().mapToLong(SearchResult::sizeBytes).sum() / 4;

            // Build response
            ObjectNode response = mapper.createObjectNode();
            response.put("query", query);

            ArrayNode skillsArray = mapper.createArrayNode();
            for (SkillMatch m : skillMatches) {
                ObjectNode item = mapper.createObjectNode();
                item.put("name", m.skillName());
                item.put("score", m.score());
                item.put("preview", m.firstLine());
                skillsArray.add(item);
            }
            response.set("skills", skillsArray);

            ArrayNode filesArray = mapper.createArrayNode();
            for (SearchResult r : fileResults) {
                ObjectNode item = mapper.createObjectNode();
                item.put("path", r.relativePath() != null ? r.relativePath() : r.path().toString());
                item.put("score", r.score());
                item.put("type", r.fileType() != null ? r.fileType() : "");
                item.put("sizeBytes", r.sizeBytes());
                filesArray.add(item);
            }
            response.set("relatedFiles", filesArray);

            ArrayNode conflictsArray = mapper.createArrayNode();
            conflicts.forEach(conflictsArray::add);
            response.set("conflicts", conflictsArray);

            response.put("estimatedTokens", estimatedTokens);
            response.put("workspace", workspacePath.toString());

            return response;

        } catch (Exception e) {
            LOG.warning("dispatch failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "dispatch failed: " + e.getMessage());
        }
    }

    /**
     * Analyzes recent Claude Code sessions and updates the skill library.
     *
     * @param params JSON with optional: since (string), skills_dir (string),
     *               dry_run (boolean), max_new (integer), min_confidence (number),
     *               workspace (string)
     * @return JSON object with: status, sessionsAnalyzed, patternsExtracted,
     *         skillsCreated, skillsUpdated, skillsSkipped, changes
     */
    public ObjectNode handleReflect(JsonNode params) throws McpToolException {
        String since = params != null && params.has("since") && !params.get("since").isNull()
                ? params.get("since").asText("7d") : "7d";
        boolean dryRun = params != null && params.has("dry_run") && params.get("dry_run").asBoolean(false);
        int maxNew = params != null && params.has("max_new") ? params.get("max_new").asInt(5) : 5;
        double minConfidence = params != null && params.has("min_confidence")
                ? params.get("min_confidence").asDouble(0.3) : 0.3;

        Path skillsDir;
        if (params != null && params.has("skills_dir") && !params.get("skills_dir").isNull()) {
            skillsDir = Path.of(params.get("skills_dir").asText()).toAbsolutePath().normalize();
        } else {
            skillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        }

        try {
            // Parse since duration
            java.time.Instant sinceInstant = io.exoreaction.synthesis.cli.SessionsCommand.parseSince(since);

            // Scan and load sessions
            io.exoreaction.synthesis.db.SynthesisDatabase db = io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
            io.exoreaction.synthesis.sessions.SessionStore store = new io.exoreaction.synthesis.sessions.SessionStore(db);
            io.exoreaction.synthesis.sessions.ClaudeSessionScanner scanner =
                    new io.exoreaction.synthesis.sessions.ClaudeSessionScanner(store);
            scanner.scan();

            java.util.List<io.exoreaction.synthesis.sessions.ClaudeSession> sessions =
                    store.listSince(sinceInstant, null);

            if (sessions.isEmpty()) {
                ObjectNode response = mapper.createObjectNode();
                response.put("status", "no-sessions");
                response.put("sessionsAnalyzed", 0);
                return response;
            }

            // Analyze patterns
            java.util.List<io.exoreaction.synthesis.skills.SessionAnalyzer.ExtractedPattern> patterns =
                    io.exoreaction.synthesis.skills.SessionAnalyzer.analyze(sessions, minConfidence);

            // Apply to skill library
            io.exoreaction.synthesis.skills.SkillUpdater.ReflectResult result =
                    io.exoreaction.synthesis.skills.SkillUpdater.apply(patterns, skillsDir, dryRun, maxNew);

            // Save state if not dry-run
            if (!dryRun) {
                io.exoreaction.synthesis.skills.ReflectState.save(
                        new io.exoreaction.synthesis.skills.ReflectState.State(
                                java.time.Instant.now(),
                                sessions.size(),
                                result.skillsCreated(),
                                result.skillsUpdated()));
            }

            // Build response
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("sessionsAnalyzed", sessions.size());
            response.put("patternsExtracted", patterns.size());
            response.put("skillsCreated", result.skillsCreated());
            response.put("skillsUpdated", result.skillsUpdated());
            response.put("skillsSkipped", result.skillsSkipped());
            response.put("dryRun", dryRun);

            ArrayNode changesArray = mapper.createArrayNode();
            for (io.exoreaction.synthesis.skills.SkillUpdater.SkillChange change : result.changes()) {
                ObjectNode item = mapper.createObjectNode();
                item.put("type", change.type().name());
                item.put("name", change.skillName());
                item.put("description", change.description() != null ? change.description() : "");
                if (change.newVersion() != null) item.put("version", change.newVersion());
                if (change.filePath() != null) item.put("path", change.filePath().toString());
                changesArray.add(item);
            }
            response.set("changes", changesArray);

            return response;

        } catch (Exception e) {
            LOG.warning("reflect failed: " + e.getMessage());
            throw new McpToolException(JsonRpcMessage.INTERNAL_ERROR, "reflect failed: " + e.getMessage());
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
