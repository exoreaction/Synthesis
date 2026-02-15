package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Synthesis MCP Server JSON-RPC protocol handling
 * and tool invocations.
 */
class SynthesisMCPServerTest {

    private ObjectMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // JSON-RPC Message Tests
    // -----------------------------------------------------------------------

    @Test
    void testResponseSuccess() throws Exception {
        JsonRpcMessage.Response response = JsonRpcMessage.Response.success(1, "hello");
        String json = mapper.writeValueAsString(response);

        JsonNode node = mapper.readTree(json);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals(1, node.get("id").asInt());
        assertEquals("hello", node.get("result").asText());
    }

    @Test
    void testErrorResponse() throws Exception {
        JsonRpcMessage.ErrorResponse error = JsonRpcMessage.ErrorResponse.error(
                42, JsonRpcMessage.INVALID_PARAMS, "missing query");
        String json = mapper.writeValueAsString(error);

        JsonNode node = mapper.readTree(json);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals(42, node.get("id").asInt());
        assertEquals(-32602, node.get("error").get("code").asInt());
        assertEquals("missing query", node.get("error").get("message").asText());
    }

    @Test
    void testErrorResponseWithData() throws Exception {
        JsonRpcMessage.ErrorResponse error = JsonRpcMessage.ErrorResponse.error(
                1, JsonRpcMessage.INTERNAL_ERROR, "fail", "extra data");
        String json = mapper.writeValueAsString(error);

        JsonNode node = mapper.readTree(json);
        assertEquals(-32603, node.get("error").get("code").asInt());
        assertEquals("extra data", node.get("error").get("data").asText());
    }

    @Test
    void testRequestIsNotification() {
        JsonRpcMessage.Request notification = new JsonRpcMessage.Request(
                "2.0", null, "initialized", null);
        assertTrue(notification.isNotification());

        JsonRpcMessage.Request request = new JsonRpcMessage.Request(
                "2.0", 1, "tools/list", null);
        assertFalse(request.isNotification());
    }

    // -----------------------------------------------------------------------
    // Tool Handler Tests
    // -----------------------------------------------------------------------

    @Test
    void testSearchMissingQuery() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();

        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleSearch(params);
        });
    }

    @Test
    void testSearchEmptyQuery() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "");

        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleSearch(params);
        });
    }

    @Test
    void testSearchNoWorkspace() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");

        // Should fail because tempDir is not a Synthesis workspace
        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleSearch(params);
        });
    }

    @Test
    void testSearchWithInitializedWorkspace() throws Exception {
        // Set up a minimal Synthesis workspace
        initWorkspace(tempDir);

        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");

        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);
        assertTrue(result.has("results"));
        assertTrue(result.has("totalHits"));
        assertTrue(result.has("searchTime"));
        assertTrue(result.has("workspace"));
    }

    @Test
    void testRelateMissingFilePath() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();

        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleRelate(params);
        });
    }

    @Test
    void testGraphNoWorkspace() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();

        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleGraph(params);
        });
    }

    @Test
    void testStatsNoWorkspace() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();

        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleStats(params);
        });
    }

    @Test
    void testStatsWithInitializedWorkspace() throws Exception {
        initWorkspace(tempDir);

        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();

        ObjectNode result = handler.handleStats(params);
        assertNotNull(result);
        assertTrue(result.has("totalFiles"));
        assertTrue(result.has("workspace"));
        assertTrue(result.has("health"));
        assertTrue(result.has("timestamp"));
    }

    @Test
    void testSearchLimitClamping() throws Exception {
        initWorkspace(tempDir);

        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);

        // Test limit above max
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("limit", 500);
        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);

        // Test limit below min
        params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("limit", 0);
        result = handler.handleSearch(params);
        assertNotNull(result);
    }

    @Test
    void testSearchWithFileTypeFilter() throws Exception {
        initWorkspace(tempDir);

        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("fileType", "CODE");

        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);
        assertTrue(result.has("results"));
    }

    @Test
    void testSearchWithAllFileType() throws Exception {
        initWorkspace(tempDir);

        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("fileType", "ALL");

        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);
    }

    @Test
    void testSearchWithWorkspaceOverride() {
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("workspace", "/nonexistent/path");

        // Should fail because override workspace doesn't exist
        assertThrows(SynthesisToolHandler.McpToolException.class, () -> {
            handler.handleSearch(params);
        });
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    private void initWorkspace(Path root) throws IOException {
        Path synthesisDir = root.resolve(".synthesis");
        Files.createDirectories(synthesisDir.resolve("index"));
        Files.createDirectories(synthesisDir.resolve("reports"));
        Files.writeString(synthesisDir.resolve("config.yaml"),
                "name: test-workspace\ntype: general\n");
    }
}
