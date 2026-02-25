package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Embedded HTTP transport for the Synthesis MCP server (issue #260).
 *
 * <p>Starts a lightweight HTTP server using the JDK built-in
 * {@link com.sun.net.httpserver.HttpServer} (zero new dependencies).
 * All MCP tool requests arrive as HTTP POST to {@code /mcp}; the body
 * must be a JSON-RPC 2.0 object exactly as used over stdio.
 *
 * <p>Two endpoints are exposed:
 * <ul>
 *   <li>{@code POST /mcp} — JSON-RPC 2.0 request/response</li>
 *   <li>{@code GET  /health} — returns {@code {"status":"ok"}}</li>
 * </ul>
 *
 * <p>Design principle: {@link #handleMcp} delegates directly to
 * {@link SynthesisMCPServer#handleMessage(JsonNode)}, the same dispatch
 * method used by the stdio loop.  No protocol logic is duplicated.
 *
 * <p>Usage:
 * <pre>
 *   synthesis-mcp-server --workspace /path/to/ws --http-port 8765
 *
 *   curl -s -X POST http://localhost:8765/mcp \
 *     -H "Content-Type: application/json" \
 *     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
 *          "params":{"name":"search","arguments":{"query":"hazelcast"}}}'
 * </pre>
 *
 * <p>CORS headers are added to every response so that browser-based
 * clients (e.g. local dev tools) can reach the server without a proxy.
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class McpHttpServer {

    private static final String HEALTH_RESPONSE = "{\"status\":\"ok\"}";

    private final int port;
    private final SynthesisMCPServer delegate;
    private final Logger log;
    private final ObjectMapper mapper;

    /**
     * @param port     TCP port to listen on
     * @param delegate the MCP server instance whose {@code handleMessage} will be called
     * @param log      logger shared with the parent server
     */
    public McpHttpServer(int port, SynthesisMCPServer delegate, Logger log) {
        this.port = port;
        this.delegate = delegate;
        this.log = log;
        this.mapper = new ObjectMapper();
    }

    /**
     * Starts the HTTP server.  Returns immediately; the server runs on daemon threads.
     *
     * @throws IOException if the port is already in use or cannot be bound
     */
    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/mcp", this::handleMcp);
        server.createContext("/health", this::handleHealth);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("MCP HTTP server listening on http://0.0.0.0:" + port + "/mcp");
    }

    // -----------------------------------------------------------------------
    // Request handlers
    // -----------------------------------------------------------------------

    private void handleMcp(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            respond(exchange, 204, "");
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"Method Not Allowed — use POST\"}");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = mapper.readTree(body);
            log.fine("HTTP MCP request: " + body);

            Object result = delegate.handleMessage(request);

            String json = result != null ? mapper.writeValueAsString(result) : "{}";
            log.fine("HTTP MCP response: " + json);

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();

        } catch (Exception e) {
            log.warning("HTTP MCP error: " + e.getMessage());
            String errBody = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":"
                    + "\"Internal error: " + escapeJsonString(e.getMessage()) + "\"}}";
            respond(exchange, 500, errBody);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        respond(exchange, 200, HEALTH_RESPONSE);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
