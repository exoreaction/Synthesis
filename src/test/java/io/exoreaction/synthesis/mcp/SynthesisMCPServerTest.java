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
    // Tool Description Contract Tests
    // -----------------------------------------------------------------------

    @Test
    void diffToolDocumentsTheRefRangeFormatTheEngineAccepts() throws Exception {
        // GitIntegration.diffRefs() rejects anything that is not 'old..new'
        // (see GitIntegrationTest.diffRefsThrowsForInvalidFormat). The tool
        // description and schema must advertise that format, not bare refs.
        JsonNode diff = findToolDefinition("diff");

        String description = diff.get("description").asText();
        String refDescription = diff.get("inputSchema")
                .get("properties").get("ref").get("description").asText();

        assertTrue(description.contains(".."),
                "diff description must document the 'old..new' range format: " + description);
        assertTrue(refDescription.contains(".."),
                "ref parameter must document the 'old..new' range format: " + refDescription);
        assertFalse(description.contains("'HEAD~1',") || description.contains("HEAD~1,"),
                "diff description must not advertise bare single refs the engine rejects: "
                        + description);
    }

    @Test
    void recallToolAdvertisesPerClaimFreshnessVerdicts() throws Exception {
        JsonNode recall = findToolDefinition("recall");
        String description = recall.get("description").asText();

        assertTrue(description.contains("fresh"),
                "recall description should mention the fresh verdict: " + description);
        assertTrue(description.contains("stale"),
                "recall description should mention the stale verdict: " + description);
        assertTrue(description.contains("unit-missing"),
                "recall description should mention the unit-missing verdict: " + description);
    }

    private JsonNode findToolDefinition(String name) throws Exception {
        SynthesisMCPServer server = new SynthesisMCPServer(tempDir);
        JsonNode request = mapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        Object response = server.handleMessage(request);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(response));

        JsonNode tools = node.get("result").get("tools");
        assertNotNull(tools, "tools/list must return a tools array");
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return tool;
            }
        }
        fail("Tool '" + name + "' not found in tools/list");
        return null; // unreachable
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
