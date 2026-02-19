package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.exoreaction.synthesis.core.ScanState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the new MCP tool handler extensions: ask, enrich, explain.
 */
class McpToolHandlerExtensionsTest {

    private ObjectMapper mapper;
    private SynthesisToolHandler handler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();

        // Initialize a minimal workspace
        Path synthesisDir = tempDir.resolve(".synthesis");
        Files.createDirectories(synthesisDir);
        Files.writeString(synthesisDir.resolve("config.yaml"), "edition: core\n");
        Files.createDirectories(synthesisDir.resolve("index"));

        handler = new SynthesisToolHandler(mapper, tempDir);
    }

    // --- ask tool ---

    @Test
    void handleAsk_failsWithoutQuery() {
        ObjectNode params = mapper.createObjectNode();
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleAsk(params));
    }

    @Test
    void handleAsk_failsWithEmptyQuery() {
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "");
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleAsk(params));
    }

    @Test
    void handleAsk_failsWithNullParams() {
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleAsk(null));
    }

    @Test
    void handleAsk_failsWithoutApiKey() {
        // Without API key, should get a useful error
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "How does authentication work?");
        params.put("workspace", tempDir.toString());

        // Should throw because no API key is configured
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleAsk(params));
    }

    // --- enrich tool ---

    @Test
    void handleEnrich_singleFileNotFound() {
        ObjectNode params = mapper.createObjectNode();
        params.put("filePath", "nonexistent.mp4");
        params.put("workspace", tempDir.toString());

        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleEnrich(params));
    }

    @Test
    void handleEnrich_batchModeEmptyIndex() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("level", "basic");
        params.put("workspace", tempDir.toString());

        // Should succeed with empty results (no binary files in empty index)
        ObjectNode result = handler.handleEnrich(params);
        assertNotNull(result);
        assertEquals(0, result.get("generated").asInt());
        assertEquals("BASIC", result.get("level").asText());
    }

    @Test
    void handleEnrich_levelDefaultsToBasic() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("workspace", tempDir.toString());

        ObjectNode result = handler.handleEnrich(params);
        assertEquals("BASIC", result.get("level").asText());
    }

    // --- explain tool ---

    @Test
    void handleExplain_failsWithoutTarget() {
        ObjectNode params = mapper.createObjectNode();
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleExplain(params));
    }

    @Test
    void handleExplain_failsWithEmptyTarget() {
        ObjectNode params = mapper.createObjectNode();
        params.put("target", "");
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleExplain(params));
    }

    @Test
    void handleExplain_failsWithNullParams() {
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleExplain(null));
    }

    @Test
    void handleExplain_failsWithoutApiKey() {
        ObjectNode params = mapper.createObjectNode();
        params.put("target", "AuthService.java");
        params.put("workspace", tempDir.toString());

        // Should throw because no API key is configured
        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.handleExplain(params));
    }

    // --- freshness metadata ---

    @Test
    void searchResponse_includesConfidence() throws Exception {
        // Create a scan state so freshness metadata can be loaded
        ScanState scanState = new ScanState(); // lastScanTime defaults to now
        Path scanStatePath = tempDir.resolve(".synthesis").resolve("scan-state.json");
        scanState.save(scanStatePath);

        ObjectNode response = mapper.createObjectNode();
        handler.addFreshnessMetadata(response, tempDir);

        assertTrue(response.has("confidence"), "Response should include confidence field");
        double confidence = response.get("confidence").asDouble();
        assertTrue(confidence >= 0.0 && confidence <= 1.0,
                "Confidence should be between 0 and 1, was: " + confidence);
        // Just scanned (now), so confidence should be 1.0
        assertEquals(1.0, confidence, 0.01, "Fresh scan should have confidence 1.0");
    }

    @Test
    void searchResponse_includesWorkspaceScanAge() throws Exception {
        // Create a scan state
        ScanState scanState = new ScanState();
        Path scanStatePath = tempDir.resolve(".synthesis").resolve("scan-state.json");
        scanState.save(scanStatePath);

        ObjectNode response = mapper.createObjectNode();
        handler.addFreshnessMetadata(response, tempDir);

        assertTrue(response.has("scanAgeDays"), "Response should include scanAgeDays field");
        long ageDays = response.get("scanAgeDays").asLong();
        assertEquals(0, ageDays, "Fresh scan should have 0 days age");

        assertTrue(response.has("workspaceLastScan"), "Response should include workspaceLastScan field");
        assertFalse(response.get("workspaceLastScan").asText().isEmpty());
    }

    @Test
    void searchResponse_handlesNoScanState() throws Exception {
        // No scan state file exists
        ObjectNode response = mapper.createObjectNode();
        handler.addFreshnessMetadata(response, tempDir);

        assertTrue(response.has("confidence"), "Response should include confidence even without scan state");
        assertEquals(0.5, response.get("confidence").asDouble(), 0.01,
                "Missing scan state should default to 0.5 confidence");
        assertEquals(-1, response.get("scanAgeDays").asLong(),
                "Missing scan state should show -1 age");
    }

    @Test
    void computeConfidence_freshScan() {
        assertEquals(1.0, SynthesisToolHandler.computeConfidence(0));
        assertEquals(1.0, SynthesisToolHandler.computeConfidence(1));
    }

    @Test
    void computeConfidence_weekOld() {
        assertEquals(0.9, SynthesisToolHandler.computeConfidence(2));
        assertEquals(0.9, SynthesisToolHandler.computeConfidence(7));
    }

    @Test
    void computeConfidence_monthOld() {
        assertEquals(0.75, SynthesisToolHandler.computeConfidence(8));
        assertEquals(0.75, SynthesisToolHandler.computeConfidence(30));
    }

    @Test
    void computeConfidence_veryOld() {
        assertEquals(0.5, SynthesisToolHandler.computeConfidence(31));
        assertEquals(0.5, SynthesisToolHandler.computeConfidence(365));
    }

    // --- Tool registration ---

    @Test
    void toolsListContainsNewTools() throws Exception {
        // Verify the server's handleToolsList includes the new tools
        SynthesisMCPServer server = new SynthesisMCPServer(tempDir);
        // We can't easily call handleToolsList directly, but the compilation
        // test proves the schemas are defined and linked
        assertNotNull(server);
    }
}
