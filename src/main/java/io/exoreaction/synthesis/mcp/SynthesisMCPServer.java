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
    private final Logger log;
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

        server.run();
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
     * @param node the parsed JSON message
     * @return the response object, or null for notifications
     */
    private Object handleMessage(JsonNode node) {
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
                "Search Synthesis index across all file types (code, docs, videos, PDFs). " +
                        "Returns ranked results with snippets, metadata, and relevance scores. " +
                        "Supports Lucene query syntax: simple terms, exact phrases, boolean operators, wildcards.",
                createSearchSchema()
        ));

        // Tool: relate
        toolsArray.add(createToolDefinition(
                "relate",
                "Show bidirectional relationships for a file (imports, usages, references). " +
                        "Answers: 'What does this file depend on?' and 'What depends on this file?' " +
                        "Essential for understanding impact before making changes.",
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

    // -----------------------------------------------------------------------
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
