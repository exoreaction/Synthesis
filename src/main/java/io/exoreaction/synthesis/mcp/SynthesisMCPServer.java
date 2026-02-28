package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.mcp.SynthesisToolHandler.McpToolException;
import io.exoreaction.synthesis.util.Version;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.*;

/**
 * Synthesis MCP (Model Context Protocol) Server.
 *
 * <p>Implements the MCP specification (v2024-11-05) over JSON-RPC 2.0 on stdio,
 * exposing Synthesis search, relate, graph, and stats capabilities to AI agents
 * such as Claude Code, Cursor, and Aider.
 *
 * <h2>Protocol Flow</h2>
 * <ol>
 *   <li>Client sends {@code initialize} request</li>
 *   <li>Server responds with capabilities (tools list)</li>
 *   <li>Client sends {@code initialized} notification</li>
 *   <li>Client invokes tools via {@code tools/call} requests</li>
 *   <li>Client sends {@code shutdown} or closes connection</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar synthesis-mcp-server.jar [--workspace /path/to/workspace]
 * </pre>
 *
 * <h2>Claude Code Integration</h2>
 * <pre>
 * ~/.claude/config.json:
 * {
 *   "mcpServers": {
 *     "synthesis": {
 *       "command": "synthesis-mcp-server",
 *       "args": ["--workspace", "/home/user/project"]
 *     }
 *   }
 * }
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 * @see <a href="https://modelcontextprotocol.org/">MCP Specification</a>
 */
public class SynthesisMCPServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "synthesis";

    private final ObjectMapper mapper;
    private final SynthesisToolHandler toolHandler;
    private final BufferedReader stdin;
    private final OutputStream stdout;
    final Logger log;
    private final String serverDisplayName;

    private volatile boolean running = true;

    /**
     * Creates an MCP server for a single workspace.
     */
    public SynthesisMCPServer(Path workspace) {
        this(workspace, List.of(), null);
    }

    /**
     * Creates an MCP server for multiple workspaces.
     *
     * @param defaultWorkspace  the primary workspace (first in list, or fallback)
     * @param additionalWorkspaces additional workspaces to search across
     * @param displayName optional display name for the server
     */
    public SynthesisMCPServer(Path defaultWorkspace, List<Path> additionalWorkspaces, String displayName) {
        this.mapper = new ObjectMapper();
        List<Path> allWorkspaces = new ArrayList<>();
        allWorkspaces.add(defaultWorkspace);
        allWorkspaces.addAll(additionalWorkspaces);
        this.toolHandler = new SynthesisToolHandler(mapper, defaultWorkspace, allWorkspaces);
        this.stdin = new BufferedReader(new InputStreamReader(System.in));
        this.stdout = System.out;
        this.log = setupLogging();
        this.serverDisplayName = displayName != null ? displayName : SERVER_NAME;
    }

    /**
     * Main entry point. Parses command-line arguments and starts the server.
     *
     * <p>Supports two modes:
     * <ul>
     *   <li>Single workspace: {@code --workspace /path/to/workspace}</li>
     *   <li>Multi-workspace: {@code --workspaces /path1,/path2,/path3 --name source}</li>
     * </ul>
     */
    public static void main(String[] args) {
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        List<Path> workspacesList = null;
        String logLevel = "WARNING";
        String displayName = null;
        int httpPort = 0;

        // Parse simple command-line flags
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workspace", "-w" -> {
                    if (i + 1 < args.length) {
                        workspace = Path.of(args[++i]).toAbsolutePath().normalize();
                    }
                }
                case "--workspaces" -> {
                    if (i + 1 < args.length) {
                        String paths = args[++i];
                        workspacesList = Arrays.stream(paths.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(s -> Path.of(s).toAbsolutePath().normalize())
                                .toList();
                    }
                }
                case "--name" -> {
                    if (i + 1 < args.length) {
                        displayName = args[++i];
                    }
                }
                case "--log-level" -> {
                    if (i + 1 < args.length) {
                        logLevel = args[++i].toUpperCase();
                    }
                }
                case "--http-port" -> {
                    if (i + 1 < args.length) {
                        try {
                            httpPort = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid --http-port value: " + args[i]);
                            System.exit(1);
                        }
                    }
                }
                case "--version", "-v" -> {
                    System.err.println(Version.getFullVersion() + " (MCP Server)");
                    System.exit(0);
                }
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
            }
        }

        // Configure root logger level
        Logger.getLogger("io.exoreaction.synthesis.mcp").setLevel(Level.parse(logLevel));

        SynthesisMCPServer server;
        if (workspacesList != null && !workspacesList.isEmpty()) {
            // Multi-workspace mode
            Path primary = workspacesList.get(0);
            List<Path> additional = workspacesList.size() > 1
                    ? workspacesList.subList(1, workspacesList.size())
                    : List.of();
            server = new SynthesisMCPServer(primary, additional, displayName);
            server.log.info("Starting Synthesis MCP Server v" + Version.getVersion() + " (multi-workspace)");
            for (Path ws : workspacesList) {
                server.log.info("  Workspace: " + ws);
            }
        } else {
            // Single workspace mode (backward compatible)
            server = new SynthesisMCPServer(workspace);
            server.log.info("Starting Synthesis MCP Server v" + Version.getVersion());
            server.log.info("Workspace: " + workspace);
        }
        server.log.info("Protocol: MCP " + PROTOCOL_VERSION);

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.running = false;
            server.log.info("Shutting down MCP server");
        }));

        if (httpPort > 0) {
            // HTTP mode: start embedded server and block the main thread
            try {
                new McpHttpServer(httpPort, server, server.log).start();
            } catch (java.io.IOException e) {
                server.log.severe("Failed to start HTTP server on port " + httpPort + ": " + e.getMessage());
                System.exit(1);
            }
            // Block until the process is killed; the HTTP server runs on virtual threads
            try {
                Thread.currentThread().join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } else {
            // Default stdio mode (unchanged)
            server.run();
        }
    }

    /**
     * Main protocol loop. Reads JSON-RPC requests from stdin,
     * dispatches them, and writes responses to stdout.
     */
    public void run() {
        try {
            while (running) {
                String line = stdin.readLine();
                if (line == null) {
                    log.info("Stdin closed, shutting down");
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                log.fine("Received: " + line);

                try {
                    JsonNode node = mapper.readTree(line);
                    Object response = handleMessage(node);

                    if (response != null) {
                        String responseStr = mapper.writeValueAsString(response);
                        log.fine("Sending: " + responseStr);
                        synchronized (stdout) {
                            stdout.write(responseStr.getBytes());
                            stdout.write('\n');
                            stdout.flush();
                        }
                    }
                } catch (JsonProcessingException e) {
                    log.warning("JSON parse error: " + e.getMessage());
                    sendError(null, JsonRpcMessage.PARSE_ERROR, "Invalid JSON: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.severe("IO error in main loop: " + e.getMessage());
        }
    }

    /**
     * Dispatches a JSON-RPC message to the appropriate handler.
     *
     * <p>Package-accessible so that {@link McpHttpServer} can reuse this
     * dispatch logic without duplicating protocol handling.
     *
     * @param node the parsed JSON message
     * @return the response object, or null for notifications
     */
    Object handleMessage(JsonNode node) {
        if (!node.has("jsonrpc") || !"2.0".equals(node.get("jsonrpc").asText())) {
            return JsonRpcMessage.ErrorResponse.error(
                    getMessageId(node), JsonRpcMessage.INVALID_REQUEST,
                    "Missing or invalid 'jsonrpc' field (must be '2.0')");
        }

        String method = node.has("method") ? node.get("method").asText() : null;
        if (method == null) {
            return JsonRpcMessage.ErrorResponse.error(
                    getMessageId(node), JsonRpcMessage.INVALID_REQUEST,
                    "Missing 'method' field");
        }

        Object id = getMessageId(node);
        JsonNode params = node.get("params");

        log.fine("Handling method: " + method + " (id=" + id + ")");

        return switch (method) {
            case "initialize" -> handleInitialize(id, params);
            case "initialized" -> null;  // Notification, no response
            case "shutdown" -> handleShutdown(id);
            case "notifications/cancelled" -> null;  // Notification, no response
            case "tools/list" -> handleToolsList(id, params);
            case "tools/call" -> handleToolsCall(id, params);
            case "ping" -> JsonRpcMessage.Response.success(id, Map.of());
            default -> {
                log.info("Unknown method: " + method);
                yield JsonRpcMessage.ErrorResponse.error(id, JsonRpcMessage.METHOD_NOT_FOUND,
                        "Unknown method: " + method);
            }
        };
    }

    // -----------------------------------------------------------------------
    // MCP Lifecycle Methods
    // -----------------------------------------------------------------------

    private Object handleInitialize(Object id, JsonNode params) {
        log.info("Client initializing MCP session");

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        // Server info
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", serverDisplayName != null ? serverDisplayName : SERVER_NAME);
        serverInfo.put("version", Version.getVersion());
        result.set("serverInfo", serverInfo);

        // Capabilities
        ObjectNode capabilities = mapper.createObjectNode();
        ObjectNode tools = mapper.createObjectNode();
        tools.put("listChanged", false);
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);

        return JsonRpcMessage.Response.success(id, result);
    }

    private Object handleShutdown(Object id) {
        log.info("Shutdown requested");
        running = false;
        return JsonRpcMessage.Response.success(id, Map.of());
    }

    // -----------------------------------------------------------------------
    // MCP Tools Methods
    // -----------------------------------------------------------------------

    private Object handleToolsList(Object id, JsonNode params) {
        ArrayNode toolsArray = mapper.createArrayNode();

        // Tool: search
        toolsArray.add(createToolDefinition(
                "search",
                "Search the pre-indexed codebase across all file types (code, docs, videos, PDFs). " +
                        "Faster than Grep for discovery: sub-second results regardless of codebase size. " +
                        "Unlike Grep, matches on semantic fields (summaries, headings, keywords) not just " +
                        "raw text — finds conceptually related files even without exact string matches. " +
                        "Use this INSTEAD OF Grep when discovering an unfamiliar area of the codebase " +
                        "or when you do not know which files contain what you need. " +
                        "Fall back to Grep for exact string/regex matching on known file locations, " +
                        "or when the answer is already in your context.",
                createSearchSchema()
        ));

        // Tool: relate
        toolsArray.add(createToolDefinition(
                "relate",
                "Show ALL bidirectional relationships for a file — every import, every caller, " +
                        "every reference across the entire codebase. Pre-computed and instant. " +
                        "Use this INSTEAD OF Grep when finding callers or dependents of a class or file: " +
                        "more complete because it understands import relationships, not just string matches. " +
                        "Returns both what this file depends on and what depends on it.",
                createRelateSchema()
        ));

        // Tool: graph
        toolsArray.add(createToolDefinition(
                "graph",
                "Generate architecture graph showing modules, dependencies, and cross-repo relationships. " +
                        "Returns Mermaid, DOT, or structured JSON. " +
                        "Use for understanding system architecture at a glance.",
                createGraphSchema()
        ));

        // Tool: stats
        toolsArray.add(createToolDefinition(
                "stats",
                "Get workspace statistics: file counts by type, index size, health status, " +
                        "and last scan time. Use to verify workspace is indexed and healthy.",
                createStatsSchema()
        ));

        // Tool: ask
        toolsArray.add(createToolDefinition(
                "ask",
                "Ask questions about the codebase using AI. Searches the Synthesis index for " +
                        "relevant files, builds context, and generates an answer with file citations. " +
                        "Requires ANTHROPIC_API_KEY.",
                createAskSchema()
        ));

        // Tool: enrich
        toolsArray.add(createToolDefinition(
                "enrich",
                "Generate .synthesis.md companion files for binary assets (images, videos, PDFs, audio). " +
                        "Makes binary content searchable by extracting metadata, text, and AI descriptions. " +
                        "Run with filePath for single file or without for batch mode.",
                createEnrichSchema()
        ));

        // Tool: explain
        toolsArray.add(createToolDefinition(
                "explain",
                "AI-powered explanation of files, directories, or architectural patterns. " +
                        "Generates comprehensive explanations with code references and context. " +
                        "Pass a file path, directory path, or pattern name. Requires ANTHROPIC_API_KEY.",
                createExplainSchema()
        ));

        // Tool: summary
        toolsArray.add(createToolDefinition(
                "summary",
                "Generate executive summary of the codebase with AI-enhanced analysis. " +
                        "Choose detail level (executive/manager/developer) and role perspective " +
                        "(architect/security/devops/etc). Results are cached for instant retrieval. " +
                        "Use this to quickly understand codebase health, risks, and priorities.",
                createSummarySchema()
        ));


        // Tool: changelog
        toolsArray.add(createToolDefinition(
                "changelog",
                "Show workspace change history. Returns added, modified, and deleted files " +
                        "with significance classification. Use to understand what changed recently.",
                createChangelogSchema()
        ));

        // Tool: report
        toolsArray.add(createToolDefinition(
                "report",
                "Generate AI-powered business reports. Topics: weekly executive, pipeline status, " +
                        "activities, decisions. Target audiences: CEO, board, investor. " +
                        "Requires ANTHROPIC_API_KEY.",
                createReportSchema()
        ));

        // Tool: health
        toolsArray.add(createToolDefinition(
                "health",
                "Run workspace structural health audit. Checks for phantom paths, build artifacts, " +
                        "empty directories, and loose root files. Returns a health score (0-100) and grade.",
                createHealthSchema()
        ));

        // Tool: security
        toolsArray.add(createToolDefinition(
                "security",
                "Security analysis findings for the workspace. Shows vulnerability counts by severity " +
                        "(HIGH/MEDIUM/LOW/INFO), including both traditional and agentic security signals. " +
                        "Use --refresh to re-scan.",
                createSecuritySchema()
        ));

        // Tool: impact
        toolsArray.add(createToolDefinition(
                "impact",
                "Find every file that would be affected if a given file changes — the full " +
                        "transitive blast radius. Use this INSTEAD OF manually running Grep for usages " +
                        "then following each reference chain. Returns the complete set of transitively " +
                        "dependent files in a single call, ranked by proximity to the change point.",
                createImpactSchema()
        ));

        // Tool: export
        toolsArray.add(createToolDefinition(
                "export",
                "Export the workspace index as Markdown, JSON, KCP, architecture doc, or onboarding guide. " +
                        "Useful for sharing workspace overviews, generating AI context, or creating documentation.",
                createExportSchema()
        ));


        // ---------------------------------------------------------------
        // Group 1: Analysis tools
        // ---------------------------------------------------------------

        // Tool: analyze
        toolsArray.add(createToolDefinition(
                "analyze",
                "Run comprehensive workspace analysis. Returns file type distribution, " +
                        "complexity metrics, and structural overview. Use to understand a codebase quickly.",
                createWorkspaceOnlySchema()
        ));

        // Tool: insights
        toolsArray.add(createToolDefinition(
                "insights",
                "Generate AI-powered codebase insights: patterns, anomalies, improvement suggestions. " +
                        "Higher-level than analyze — focuses on actionable observations.",
                createWorkspaceOnlySchema()
        ));

        // Tool: perspectives
        toolsArray.add(createToolDefinition(
                "perspectives",
                "Answer a question about the codebase from multiple role perspectives " +
                        "(architect, security, devops, product). Requires ANTHROPIC_API_KEY.",
                createPerspectivesSchema()
        ));

        // Tool: research
        toolsArray.add(createToolDefinition(
                "research",
                "Deep research into a codebase topic. Searches index, follows references, " +
                        "and synthesizes a comprehensive answer. Requires ANTHROPIC_API_KEY.",
                createResearchSchema()
        ));

        // Tool: architecture
        toolsArray.add(createToolDefinition(
                "architecture",
                "Generate an architecture overview of the workspace: layers, modules, " +
                        "key abstractions, and cross-cutting concerns.",
                createWorkspaceOnlySchema()
        ));

        // Tool: code-graph
        toolsArray.add(createToolDefinition(
                "code-graph",
                "Code-level architecture and dependency graph analysis. Returns the full module " +
                        "dependency graph, architectural layers, circular dependency detection, instability " +
                        "metrics, and quality violations. Use this FIRST when asked about architecture, " +
                        "module structure, package dependencies, or design violations — replaces dozens " +
                        "of manual Grep/Read/Bash calls with a single pre-computed analysis.",
                createCodeGraphSchema()
        ));

        // ---------------------------------------------------------------
        // Group 2: Workspace intelligence tools
        // ---------------------------------------------------------------

        // Tool: describe
        toolsArray.add(createToolDefinition(
                "describe",
                "Describe a file or directory within the workspace. Without a path, describes " +
                        "the workspace root. Returns purpose, contents, and key observations.",
                createDescribeSchema()
        ));

        // Tool: knowledge-graph
        toolsArray.add(createToolDefinition(
                "knowledge-graph",
                "Build and display a knowledge graph of concepts, entities, and relationships " +
                        "extracted from the workspace. Use to understand domain model and connections.",
                createWorkspaceOnlySchema()
        ));

        // Tool: structure
        toolsArray.add(createToolDefinition(
                "structure",
                "Show workspace directory structure with annotations: purpose of each directory, " +
                        "file counts, and notable patterns. A smart tree view.",
                createWorkspaceOnlySchema()
        ));

        // Tool: evolution
        toolsArray.add(createToolDefinition(
                "evolution",
                "Analyze how the workspace has evolved over time: growth trends, churn hotspots, " +
                        "and maturity assessment by module.",
                createWorkspaceOnlySchema()
        ));

        // Tool: scatter
        toolsArray.add(createToolDefinition(
                "scatter",
                "Detect scattered concerns: logic spread across many files that should be consolidated. " +
                        "Identifies code duplication patterns and cohesion issues.",
                createWorkspaceOnlySchema()
        ));

        // Tool: naming
        toolsArray.add(createToolDefinition(
                "naming",
                "Analyze naming conventions across the codebase. Detects inconsistencies, " +
                        "suggests improvements, and checks adherence to project naming patterns.",
                createWorkspaceOnlySchema()
        ));

        // Tool: upcoming
        toolsArray.add(createToolDefinition(
                "upcoming",
                "Show upcoming tasks, TODOs, FIXMEs, and deadlines found in the codebase. " +
                        "Extracts actionable items from comments and documentation.",
                createWorkspaceOnlySchema()
        ));

        // Tool: status
        toolsArray.add(createToolDefinition(
                "status",
                "Show current workspace status: index freshness, pending changes, scan state, " +
                        "and configuration summary.",
                createWorkspaceOnlySchema()
        ));

        // Tool: mcp-stats
        toolsArray.add(createToolDefinition(
                "mcp-stats",
                "Show MCP server usage statistics: tool invocation counts, response times, " +
                        "error rates, and popular queries. Reads the global MCP query log.",
                createMcpStatsSchema()
        ));

        // ---------------------------------------------------------------
        // Group 3: Change tracking tools
        // ---------------------------------------------------------------

        // Tool: diff
        toolsArray.add(createToolDefinition(
                "diff",
                "Show synthesis-aware diff against a git ref (e.g. HEAD~1, main, a commit SHA). " +
                        "Categorizes changes by type and significance.",
                createDiffSchema()
        ));

        // Tool: changed
        toolsArray.add(createToolDefinition(
                "changed",
                "List files changed since a date or duration (e.g. '2026-02-20' or '7d'). " +
                        "Groups by change type: added, modified, deleted.",
                createChangedSchema()
        ));

        // Tool: track
        toolsArray.add(createToolDefinition(
                "track",
                "Track file movements using hash-based detection. Shows files that were moved " +
                        "or renamed, with confidence scores and audit trail.",
                createWorkspaceOnlySchema()
        ));

        // ---------------------------------------------------------------
        // Group 4: Discovery & validation tools
        // ---------------------------------------------------------------

        // Tool: which
        toolsArray.add(createToolDefinition(
                "which",
                "Find which file(s) match a pattern or contain a symbol. Like 'which' for your codebase: " +
                        "resolves class names, function names, or path patterns to actual files.",
                createWhichSchema()
        ));

        // Tool: discover
        toolsArray.add(createToolDefinition(
                "discover",
                "Discover interesting patterns, hidden dependencies, and non-obvious relationships " +
                        "in the workspace. Surfaces things you did not know to look for.",
                createWorkspaceOnlySchema()
        ));

        // Tool: validate
        toolsArray.add(createToolDefinition(
                "validate",
                "Validate workspace integrity: broken links, missing references, orphaned files, " +
                        "and configuration issues. Returns pass/fail with actionable fixes.",
                createWorkspaceOnlySchema()
        ));

        // Tool: metrics
        toolsArray.add(createToolDefinition(
                "metrics",
                "Compute codebase metrics: lines of code, complexity, test coverage estimates, " +
                        "documentation ratio, and dependency counts.",
                createWorkspaceOnlySchema()
        ));

        // Tool: trace
        toolsArray.add(createToolDefinition(
                "trace",
                "Trace the call chain or dependency path between two classes, files, or symbols, " +
                        "showing all intermediate hops. Use this INSTEAD OF manually reading files " +
                        "sequentially to follow execution flow. If you only know the start point, " +
                        "use 'relate' first to discover reachable targets.",
                createTraceSchema()
        ));

        // Tool: cross-repo-deps
        toolsArray.add(createToolDefinition(
                "cross-repo-deps",
                "Analyze cross-repository dependencies across all repos in the workspace. " +
                        "Shows which repos depend on which, with version and artifact details.",
                createWorkspaceOnlySchema()
        ));

        // Tool: learn
        toolsArray.add(createToolDefinition(
                "learn",
                "Generate a learning guide for the codebase: key concepts, entry points, " +
                        "recommended reading order, and architectural patterns to understand first.",
                createWorkspaceOnlySchema()
        ));

        // ---------------------------------------------------------------
        // Group 5: Maintenance tools
        // ---------------------------------------------------------------

        // Tool: maintain
        toolsArray.add(createToolDefinition(
                "maintain",
                "Run full workspace maintenance: re-index changed files, update relations, " +
                        "refresh snapshots, and track movements. Long-running (may take minutes).",
                createWorkspaceOnlySchema()
        ));

        // Tool: scan
        toolsArray.add(createToolDefinition(
                "scan",
                "Scan and index all files in the workspace. Creates or updates the Synthesis index. " +
                        "Run after adding new files or on first setup.",
                createWorkspaceOnlySchema()
        ));
        // Group 6: Session lifecycle tools
        toolsArray.add(createToolDefinition(
                "session_context",
                "Generate compact codebase freshness snapshot for Claude Code session injection. " +
                        "Returns workspace stats, recent changes, and security posture.",
                createSessionContextSchema()
        ));
        toolsArray.add(createToolDefinition(
                "hooks_generate",
                "Generate Claude Code hook configuration JSON that injects Synthesis context at session start. " +
                        "Returns the settings.json hook entry. Always runs in dry-run mode (returns JSON, does not write to disk).",
                createHooksGenerateSchema()
        ));
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", toolsArray);

        return JsonRpcMessage.Response.success(id, result);
    }

    private Object handleToolsCall(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpcMessage.ErrorResponse.error(id, JsonRpcMessage.INVALID_PARAMS,
                    "Missing tool 'name' in tools/call request");
        }

        String toolName = params.get("name").asText();
        JsonNode toolArgs = params.has("arguments") ? params.get("arguments") : mapper.createObjectNode();

        log.info("Tool call: " + toolName);

        try {
            ObjectNode toolResult = switch (toolName) {
                case "search" -> toolHandler.handleSearch(toolArgs);
                case "relate" -> toolHandler.handleRelate(toolArgs);
                case "graph" -> toolHandler.handleGraph(toolArgs);
                case "stats" -> toolHandler.handleStats(toolArgs);
                case "ask" -> toolHandler.handleAsk(toolArgs);
                case "enrich" -> toolHandler.handleEnrich(toolArgs);
                case "explain" -> toolHandler.handleExplain(toolArgs);
                case "summary" -> toolHandler.handleSummary(toolArgs);
                case "changelog" -> toolHandler.handleChangelog(toolArgs);
                case "report" -> toolHandler.handleReport(toolArgs);
                case "health" -> toolHandler.handleHealth(toolArgs);
                case "security" -> toolHandler.handleSecurity(toolArgs);
                case "impact" -> toolHandler.handleImpact(toolArgs);
                case "export" -> toolHandler.handleExport(toolArgs);
                // Group 1: Analysis
                case "analyze" -> toolHandler.handleAnalyze(toolArgs);
                case "insights" -> toolHandler.handleInsights(toolArgs);
                case "perspectives" -> toolHandler.handlePerspectives(toolArgs);
                case "research" -> toolHandler.handleResearch(toolArgs);
                case "architecture" -> toolHandler.handleArchitecture(toolArgs);
                case "code-graph" -> toolHandler.handleCodeGraph(toolArgs);
                // Group 2: Workspace intelligence
                case "describe" -> toolHandler.handleDescribe(toolArgs);
                case "knowledge-graph" -> toolHandler.handleKnowledgeGraph(toolArgs);
                case "structure" -> toolHandler.handleStructure(toolArgs);
                case "evolution" -> toolHandler.handleEvolution(toolArgs);
                case "scatter" -> toolHandler.handleScatter(toolArgs);
                case "naming" -> toolHandler.handleNaming(toolArgs);
                case "upcoming" -> toolHandler.handleUpcoming(toolArgs);
                case "status" -> toolHandler.handleStatus(toolArgs);
                case "mcp-stats" -> toolHandler.handleMcpStats(toolArgs);
                // Group 3: Change tracking
                case "diff" -> toolHandler.handleDiff(toolArgs);
                case "changed" -> toolHandler.handleChanged(toolArgs);
                case "track" -> toolHandler.handleTrack(toolArgs);
                // Group 4: Discovery & validation
                case "which" -> toolHandler.handleWhich(toolArgs);
                case "discover" -> toolHandler.handleDiscover(toolArgs);
                case "validate" -> toolHandler.handleValidate(toolArgs);
                case "metrics" -> toolHandler.handleMetrics(toolArgs);
                case "trace" -> toolHandler.handleTrace(toolArgs);
                case "cross-repo-deps" -> toolHandler.handleCrossRepoDeps(toolArgs);
                case "learn" -> toolHandler.handleLearn(toolArgs);
                // Group 5: Maintenance
                case "maintain" -> toolHandler.handleMaintain(toolArgs);
                case "scan" -> toolHandler.handleScan(toolArgs);
                // Group 6: Session lifecycle
                case "session_context" -> toolHandler.handleSessionContext(toolArgs);
                case "hooks_generate" -> toolHandler.handleHooksGenerate(toolArgs);
                default -> throw new McpToolException(JsonRpcMessage.METHOD_NOT_FOUND,
                        "Unknown tool: " + toolName);
            };

            // Wrap in MCP tool result format
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode textContent = mapper.createObjectNode();
            textContent.put("type", "text");
            textContent.put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolResult));
            content.add(textContent);
            result.set("content", content);
            result.put("isError", false);

            return JsonRpcMessage.Response.success(id, result);
        } catch (McpToolException e) {
            log.warning("Tool error (" + toolName + "): " + e.getMessage());

            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode textContent = mapper.createObjectNode();
            textContent.put("type", "text");
            textContent.put("text", "Error: " + e.getMessage());
            content.add(textContent);
            result.set("content", content);
            result.put("isError", true);

            return JsonRpcMessage.Response.success(id, result);
        } catch (Exception e) {
            log.severe("Unexpected error in tool " + toolName + ": " + e.getMessage());
            return JsonRpcMessage.ErrorResponse.error(id, JsonRpcMessage.INTERNAL_ERROR,
                    "Internal error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Tool Schema Definitions
    // -----------------------------------------------------------------------

    private ObjectNode createToolDefinition(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode createSearchSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode query = mapper.createObjectNode();
        query.put("type", "string");
        query.put("description", "Search query (supports Lucene syntax: terms, phrases, booleans, wildcards, field:value)");
        properties.set("query", query);

        ObjectNode fileType = mapper.createObjectNode();
        fileType.put("type", "string");
        ArrayNode enumValues = mapper.createArrayNode();
        for (String t : new String[]{"CODE", "MARKDOWN", "PDF", "VIDEO", "YAML", "JSON", "CONFIG", "IMAGE", "AUDIO", "ALL"}) {
            enumValues.add(t);
        }
        fileType.set("enum", enumValues);
        fileType.put("default", "ALL");
        fileType.put("description", "Filter by file type");
        properties.set("fileType", fileType);

        ObjectNode limit = mapper.createObjectNode();
        limit.put("type", "number");
        limit.put("default", 20);
        limit.put("description", "Maximum number of results (1-200)");
        properties.set("limit", limit);

        ObjectNode previewLength = mapper.createObjectNode();
        previewLength.put("type", "number");
        previewLength.put("default", 300);
        previewLength.put("description",
                "Snippet length in characters (100-3000). Default 300. " +
                "Increase to 1000-2000 to reduce follow-up file reads. " +
                "Excerpt is centred on the matching section, not the file start.");
        properties.set("previewLength", previewLength);

        ObjectNode subWorkspace = mapper.createObjectNode();
        subWorkspace.put("type", "string");
        subWorkspace.put("description", "Scope search to a named sub-workspace (e.g. 'eXOReaction', 'Cantara'). " +
                "Useful in multi-workspace setups to limit results to a specific organisation or project.");
        properties.set("subWorkspace", subWorkspace);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("query");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createRelateSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode filePath = mapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "File name or path to analyze relationships for");
        properties.set("filePath", filePath);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "string");
        ArrayNode enumValues = mapper.createArrayNode();
        enumValues.add("json");
        enumValues.add("mermaid");
        format.set("enum", enumValues);
        format.put("default", "json");
        format.put("description", "Output format: json (structured) or mermaid (diagram)");
        properties.set("format", format);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("filePath");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createGraphSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode mode = mapper.createObjectNode();
        mode.put("type", "string");
        ArrayNode modeEnum = mapper.createArrayNode();
        modeEnum.add("modules");
        modeEnum.add("dependencies");
        modeEnum.add("cross-repo");
        mode.set("enum", modeEnum);
        mode.put("default", "modules");
        mode.put("description", "Graph type: modules (directory-level), dependencies, or cross-repo");
        properties.set("mode", mode);

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "string");
        ArrayNode fmtEnum = mapper.createArrayNode();
        fmtEnum.add("mermaid");
        fmtEnum.add("json");
        fmtEnum.add("dot");
        format.set("enum", fmtEnum);
        format.put("default", "mermaid");
        format.put("description", "Output format");
        properties.set("format", format);

        ObjectNode filter = mapper.createObjectNode();
        filter.put("type", "string");
        filter.put("description", "Filter to specific subsystem, directory, or repository pattern");
        properties.set("filter", filter);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createStatsSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createAskSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode query = mapper.createObjectNode();
        query.put("type", "string");
        query.put("description", "The question to ask about the codebase");
        properties.set("query", query);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("query");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createEnrichSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode filePath = mapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "Path to a specific file to enrich (omit for batch mode)");
        properties.set("filePath", filePath);

        ObjectNode level = mapper.createObjectNode();
        level.put("type", "string");
        ArrayNode levelEnum = mapper.createArrayNode();
        levelEnum.add("basic");
        levelEnum.add("local");
        levelEnum.add("ai");
        level.set("enum", levelEnum);
        level.put("default", "basic");
        level.put("description", "Enrichment level: basic (metadata only), local (with tools), ai (with Claude)");
        properties.set("level", level);

        ObjectNode force = mapper.createObjectNode();
        force.put("type", "boolean");
        force.put("default", false);
        force.put("description", "Force regeneration even if companion file exists");
        properties.set("force", force);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createExplainSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode target = mapper.createObjectNode();
        target.put("type", "string");
        target.put("description", "File path, directory path, or pattern name to explain");
        properties.set("target", target);

        ObjectNode includeContext = mapper.createObjectNode();
        includeContext.put("type", "boolean");
        includeContext.put("default", true);
        includeContext.put("description", "Include related files in the explanation context");
        properties.set("includeContext", includeContext);

        ObjectNode depth = mapper.createObjectNode();
        depth.put("type", "string");
        ArrayNode depthEnum = mapper.createArrayNode();
        depthEnum.add("brief");
        depthEnum.add("standard");
        depthEnum.add("deep");
        depth.set("enum", depthEnum);
        depth.put("default", "standard");
        depth.put("description", "Explanation depth: brief (3-5 sentences), standard (sections), deep (comprehensive)");
        properties.set("depth", depth);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("target");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createSummarySchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode level = mapper.createObjectNode();
        level.put("type", "string");
        ArrayNode levelEnum = mapper.createArrayNode();
        levelEnum.add("executive");
        levelEnum.add("manager");
        levelEnum.add("developer");
        level.set("enum", levelEnum);
        level.put("default", "executive");
        level.put("description", "Detail level: executive (30s overview), manager (5min briefing), developer (technical detail)");
        properties.set("level", level);

        ObjectNode perspective = mapper.createObjectNode();
        perspective.put("type", "string");
        ArrayNode perspectiveEnum = mapper.createArrayNode();
        perspectiveEnum.add("general");
        perspectiveEnum.add("executive");
        perspectiveEnum.add("engineering_manager");
        perspectiveEnum.add("architect");
        perspectiveEnum.add("security");
        perspectiveEnum.add("devops");
        perspectiveEnum.add("product_manager");
        perspectiveEnum.add("developer");
        perspective.set("enum", perspectiveEnum);
        perspective.put("default", "general");
        perspective.put("description", "Role-based perspective for interpreting metrics");
        properties.set("perspective", perspective);

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "string");
        ArrayNode formatEnum = mapper.createArrayNode();
        formatEnum.add("markdown");
        formatEnum.add("json");
        formatEnum.add("terminal");
        format.set("enum", formatEnum);
        format.put("default", "markdown");
        format.put("description", "Output format");
        properties.set("format", format);

        ObjectNode since = mapper.createObjectNode();
        since.put("type", "string");
        since.put("description", "Include recent changes in the AI analysis. " +
                "Supports durations (7d, 24h, 2w, 3m) and ISO dates (2026-01-15). " +
                "Loads changelog data and injects it into the AI prompt. " +
                "Bypasses cache — always generates fresh results.");
        properties.set("since", since);

        ObjectNode noAi = mapper.createObjectNode();
        noAi.put("type", "boolean");
        noAi.put("default", false);
        noAi.put("description", "Skip AI-enhanced summary (faster, metrics-only)");
        properties.set("noAi", noAi);

        ObjectNode noCache = mapper.createObjectNode();
        noCache.put("type", "boolean");
        noCache.put("default", false);
        noCache.put("description", "Skip cache and force fresh generation");
        properties.set("noCache", noCache);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        // No required parameters - all have defaults
        ArrayNode required = mapper.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createChangelogSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode since = mapper.createObjectNode();
        since.put("type", "string");
        ArrayNode sinceEnum = mapper.createArrayNode();
        sinceEnum.add("24h");
        sinceEnum.add("7d");
        sinceEnum.add("2w");
        sinceEnum.add("30d");
        since.set("enum", sinceEnum);
        since.put("default", "24h");
        since.put("description", "Time period to look back for changes");
        properties.set("since", since);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createReportSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode topic = mapper.createObjectNode();
        topic.put("type", "string");
        ArrayNode topicEnum = mapper.createArrayNode();
        topicEnum.add("weekly");
        topicEnum.add("pipeline");
        topicEnum.add("activities");
        topicEnum.add("executive");
        topicEnum.add("decisions");
        topic.set("enum", topicEnum);
        topic.put("default", "weekly");
        topic.put("description", "Report topic: weekly (full), pipeline, activities, executive, or decisions");
        properties.set("topic", topic);

        ObjectNode target = mapper.createObjectNode();
        target.put("type", "string");
        ArrayNode targetEnum = mapper.createArrayNode();
        targetEnum.add("ceo");
        targetEnum.add("board");
        targetEnum.add("investor");
        target.set("enum", targetEnum);
        target.put("default", "ceo");
        target.put("description", "Target audience for the report");
        properties.set("target", target);

        ObjectNode period = mapper.createObjectNode();
        period.put("type", "string");
        period.put("default", "1w");
        period.put("description", "Coverage period: 1w, 2w, 1m");
        properties.set("period", period);

        ObjectNode noCache = mapper.createObjectNode();
        noCache.put("type", "boolean");
        noCache.put("default", false);
        noCache.put("description", "Skip cache and force fresh generation");
        properties.set("noCache", noCache);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createHealthSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createSecuritySchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode severity = mapper.createObjectNode();
        severity.put("type", "string");
        ArrayNode severityEnum = mapper.createArrayNode();
        severityEnum.add("HIGH");
        severityEnum.add("MEDIUM");
        severityEnum.add("LOW");
        severityEnum.add("INFO");
        severity.set("enum", severityEnum);
        severity.put("description", "Filter findings by severity level");
        properties.set("severity", severity);

        ObjectNode refresh = mapper.createObjectNode();
        refresh.put("type", "boolean");
        refresh.put("default", false);
        refresh.put("description", "Re-run security analysis before returning results");
        properties.set("refresh", refresh);

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "string");
        ArrayNode formatEnum = mapper.createArrayNode();
        formatEnum.add("summary");
        formatEnum.add("json");
        format.set("enum", formatEnum);
        format.put("default", "summary");
        format.put("description", "Output format: summary (counts) or json (full findings)");
        properties.set("format", format);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createImpactSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode filePath = mapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "File path or class name to analyze change impact for");
        properties.set("filePath", filePath);

        ObjectNode depth = mapper.createObjectNode();
        depth.put("type", "number");
        depth.put("default", 3);
        depth.put("description", "Maximum transitive dependency depth (1-10, default 3)");
        properties.set("depth", depth);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("filePath");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createExportSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "string");
        ArrayNode formatEnum = mapper.createArrayNode();
        formatEnum.add("markdown");
        formatEnum.add("json");
        formatEnum.add("kcp");
        formatEnum.add("architecture-doc");
        formatEnum.add("onboarding-guide");
        format.set("enum", formatEnum);
        format.put("default", "markdown");
        format.put("description", "Export format: markdown, json, kcp, architecture-doc, or onboarding-guide");
        properties.set("format", format);

        ObjectNode fileType = mapper.createObjectNode();
        fileType.put("type", "string");
        fileType.put("description", "Filter by file type (e.g., CODE, MARKDOWN, YAML, PDF)");
        properties.set("fileType", fileType);

        ObjectNode limit = mapper.createObjectNode();
        limit.put("type", "number");
        limit.put("default", 1000);
        limit.put("description", "Maximum number of entries to export (1-50000)");
        properties.set("limit", limit);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // New Tool Schema Definitions (Groups 1-5)
    // -----------------------------------------------------------------------

    /**
     * Schema for tools that only need an optional workspace parameter.
     * Used by: analyze, insights, architecture, knowledge-graph, structure,
     * evolution, scatter, naming, upcoming, status, track, discover, validate,
     * metrics, cross-repo-deps, learn, maintain, scan.
     */
    private ObjectNode createSessionContextSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode since = mapper.createObjectNode();
        since.put("type", "string");
        since.put("default", "24h");
        since.put("description", "How far back to look for changes (e.g., 1h, 24h, 7d, 2w)");
        properties.set("since", since);

        ObjectNode compact = mapper.createObjectNode();
        compact.put("type", "boolean");
        compact.put("default", true);
        compact.put("description", "Single-line output for hook injection (default true)");
        properties.set("compact", compact);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);
        return schema;
    }

    private ObjectNode createHooksGenerateSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode type = mapper.createObjectNode();
        type.put("type", "string");
        ArrayNode typeEnum = mapper.createArrayNode();
        typeEnum.add("UserPromptSubmit");
        typeEnum.add("PreToolUse");
        type.set("enum", typeEnum);
        type.put("default", "UserPromptSubmit");
        type.put("description", "Hook type: UserPromptSubmit (default) or PreToolUse");
        properties.set("type", type);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);
        return schema;
    }

    private ObjectNode createWorkspaceOnlySchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createPerspectivesSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode question = mapper.createObjectNode();
        question.put("type", "string");
        question.put("description", "Question to answer from multiple role perspectives");
        properties.set("question", question);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("question");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createResearchSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode query = mapper.createObjectNode();
        query.put("type", "string");
        query.put("description", "Research query to investigate in the codebase");
        properties.set("query", query);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("query");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createCodeGraphSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode subcommand = mapper.createObjectNode();
        subcommand.put("type", "string");
        ArrayNode subEnum = mapper.createArrayNode();
        subEnum.add("");
        subEnum.add("describe");
        subEnum.add("health");
        subEnum.add("gaps");
        subEnum.add("security");
        subcommand.set("enum", subEnum);
        subcommand.put("default", "");
        subcommand.put("description", "Subcommand: describe (overview), health (quality), gaps (coverage), security (vuln paths)");
        properties.set("subcommand", subcommand);

        ObjectNode flags = mapper.createObjectNode();
        flags.put("type", "string");
        flags.put("description", "Optional extra flags (e.g., '--cycles --hotspots')");
        properties.set("flags", flags);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createDescribeSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode path = mapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "File or directory path to describe (defaults to workspace root)");
        properties.set("path", path);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createMcpStatsSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        // No parameters needed — reads global log

        schema.set("properties", properties);

        return schema;
    }

    private ObjectNode createDiffSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode ref = mapper.createObjectNode();
        ref.put("type", "string");
        ref.put("description", "Git ref to diff against (e.g., 'HEAD~1', 'main', a commit SHA)");
        properties.set("ref", ref);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("ref");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createChangedSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode since = mapper.createObjectNode();
        since.put("type", "string");
        since.put("description", "Date (e.g., '2026-02-20') or duration (e.g., '7d', '24h', '2w')");
        properties.set("since", since);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("since");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createWhichSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode pattern = mapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "Class name, function name, or file path pattern to locate");
        properties.set("pattern", pattern);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("pattern");
        schema.set("required", required);

        return schema;
    }

    private ObjectNode createTraceSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode from = mapper.createObjectNode();
        from.put("type", "string");
        from.put("description", "Source file or symbol to trace from");
        properties.set("from", from);

        ObjectNode to = mapper.createObjectNode();
        to.put("type", "string");
        to.put("description", "Target file or symbol to trace to");
        properties.set("to", to);

        ObjectNode workspace = mapper.createObjectNode();
        workspace.put("type", "string");
        workspace.put("description", "Workspace path (defaults to server's configured workspace)");
        properties.set("workspace", workspace);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("from");
        required.add("to");
        schema.set("required", required);

        return schema;
    }
    // Utility Methods
    // -----------------------------------------------------------------------

    private Object getMessageId(JsonNode node) {
        if (!node.has("id") || node.get("id").isNull()) return null;
        JsonNode idNode = node.get("id");
        if (idNode.isNumber()) return idNode.asLong();
        return idNode.asText();
    }

    private void sendError(Object id, int code, String message) {
        try {
            String response = mapper.writeValueAsString(
                    JsonRpcMessage.ErrorResponse.error(id, code, message));
            synchronized (stdout) {
                stdout.write(response.getBytes());
                stdout.write('\n');
                stdout.flush();
            }
        } catch (IOException e) {
            log.severe("Failed to send error response: " + e.getMessage());
        }
    }

    /**
     * Sets up logging to a file (not stdout, which is used for JSON-RPC protocol).
     */
    private Logger setupLogging() {
        Logger logger = Logger.getLogger("io.exoreaction.synthesis.mcp");

        try {
            Path logDir = Path.of(System.getProperty("user.home"), ".synthesis", "logs");
            java.nio.file.Files.createDirectories(logDir);

            FileHandler fileHandler = new FileHandler(
                    logDir.resolve("mcp-server.log").toString(),
                    5_000_000, 3, true);  // 5MB, 3 files, append
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);

            // Remove console handler (would interfere with JSON-RPC on stdout)
            Logger rootLogger = Logger.getLogger("");
            for (Handler handler : rootLogger.getHandlers()) {
                if (handler instanceof ConsoleHandler) {
                    rootLogger.removeHandler(handler);
                }
            }

            logger.setUseParentHandlers(false);
        } catch (Exception e) {
            // If we can't set up file logging, use stderr
            System.err.println("Warning: Could not set up file logging: " + e.getMessage());
            Handler stderrHandler = new StreamHandler(System.err, new SimpleFormatter()) {
                @Override
                public synchronized void publish(LogRecord record) {
                    super.publish(record);
                    flush();
                }
            };
            logger.addHandler(stderrHandler);
            logger.setUseParentHandlers(false);
        }

        return logger;
    }

    private static void printHelp() {
        System.err.println("Synthesis MCP Server v" + Version.getVersion());
        System.err.println();
        System.err.println("Usage: synthesis-mcp-server [OPTIONS]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --workspace, -w <path>     Single workspace root directory (default: current dir)");
        System.err.println("  --workspaces <p1,p2,...>    Multiple workspace paths (comma-separated)");
        System.err.println("  --name <name>              Display name for this MCP server");
        System.err.println("  --http-port <port>         Enable HTTP transport on the given port (in addition to stdio)");
        System.err.println("  --log-level <level>        Logging level: FINE, INFO, WARNING, SEVERE");
        System.err.println("  --version, -v              Print version and exit");
        System.err.println("  --help, -h                 Print this help and exit");
        System.err.println();
        System.err.println("MCP Protocol: JSON-RPC 2.0 over stdio");
        System.err.println("Tools: search, relate, graph, stats, ask, enrich, explain");
        System.err.println();
        System.err.println("Single workspace (~/.claude/config.json):");
        System.err.println("  {");
        System.err.println("    \"mcpServers\": {");
        System.err.println("      \"synthesis\": {");
        System.err.println("        \"command\": \"synthesis-mcp-server\",");
        System.err.println("        \"args\": [\"--workspace\", \"/path/to/project\"]");
        System.err.println("      }");
        System.err.println("    }");
        System.err.println("  }");
        System.err.println();
        System.err.println("Multi-workspace (unified source server):");
        System.err.println("  {");
        System.err.println("    \"mcpServers\": {");
        System.err.println("      \"synthesis-source\": {");
        System.err.println("        \"command\": \"synthesis-mcp-server\",");
        System.err.println("        \"args\": [\"--workspaces\", \"/src/a,/src/b,/src/c\", \"--name\", \"source\"]");
        System.err.println("      }");
        System.err.println("    }");
        System.err.println("  }");
    }
}
