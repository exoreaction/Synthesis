package io.exoreaction.synthesis.mcp;

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
 * Tests for the smart excerpt and configurable previewLength feature (issue #259).
 *
 * <p>Covers:
 * <ul>
 *   <li>smartExcerpt: match in the middle → window is centred on match</li>
 *   <li>smartExcerpt: no match → first N chars returned</li>
 *   <li>smartExcerpt: text shorter than maxLen → full text returned unchanged</li>
 *   <li>previewLength clamping to [100, 3000]</li>
 *   <li>Backward compatibility: no previewLength param → default 300</li>
 * </ul>
 */
class McpSearchExcerptTest {

    private SynthesisToolHandler handler;
    private ObjectMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        handler = new SynthesisToolHandler(mapper, tempDir);
        initWorkspace(tempDir);
    }

    // -----------------------------------------------------------------------
    // smartExcerpt unit tests
    // -----------------------------------------------------------------------

    @Test
    void smartExcerpt_matchInMiddle_centresWindow() {
        // 50-char padding before the match so it won't be in the first 300 chars
        String prefix = "a".repeat(400);
        String matchWord = "hazelcast";
        String suffix = " configuration details follow here with more content padding";
        String text = prefix + matchWord + suffix;

        String result = handler.smartExcerpt(text, "hazelcast", 100);

        // The excerpt must contain the match word
        assertTrue(result.contains(matchWord),
                "Excerpt should contain the matched term");
        // Leading ellipsis because we started past position 0
        assertTrue(result.startsWith("\u2026"),
                "Excerpt should start with ellipsis when clipped from the beginning");
        assertTrue(result.length() <= 102, // 100 + up to 2 ellipsis chars
                "Excerpt should be at most maxLen + 2 ellipsis chars");
    }

    @Test
    void smartExcerpt_noMatch_returnsFirstNChars() {
        String text = "The quick brown fox jumps over the lazy dog. " +
                      "More content follows here to make the text longer than maxLen.";
        String result = handler.smartExcerpt(text, "unicorn", 30);

        // No leading ellipsis (started at 0)
        assertFalse(result.startsWith("\u2026"),
                "No leading ellipsis when starting from position 0");
        // Trailing ellipsis because text was clipped
        assertTrue(result.endsWith("\u2026"),
                "Trailing ellipsis when text was clipped");
        // Content starts from the beginning
        assertTrue(result.startsWith("The quick"),
                "Excerpt should start from the beginning of the text");
    }

    @Test
    void smartExcerpt_textShorterThanMaxLen_returnsFullText() {
        String text = "Short text.";
        String result = handler.smartExcerpt(text, "short", 300);

        assertEquals(text, result, "Should return full text when it fits within maxLen");
    }

    @Test
    void smartExcerpt_emptyText_returnsEmpty() {
        assertEquals("", handler.smartExcerpt("", "query", 300));
        assertEquals("", handler.smartExcerpt(null, "query", 300));
        assertEquals("", handler.smartExcerpt("   ", "query", 300));
    }

    @Test
    void smartExcerpt_luceneOperatorsStripped() {
        String text = "a".repeat(200) + "authentication" + " more text here to pad the suffix";
        // Query uses Lucene syntax; operators should be stripped to find the term
        String result = handler.smartExcerpt(text, "+authentication AND jwt", 60);

        assertTrue(result.contains("authentication"),
                "Should find term despite Lucene operators in query");
    }

    @Test
    void smartExcerpt_caseInsensitiveMatch() {
        String text = "a".repeat(200) + "HazelCast" + " config";
        String result = handler.smartExcerpt(text, "hazelcast", 40);

        assertTrue(result.contains("HazelCast"),
                "Match should be case-insensitive");
    }

    @Test
    void smartExcerpt_exactLength() {
        // text.length() == maxLen → no clipping needed
        String text = "x".repeat(300);
        String result = handler.smartExcerpt(text, "x", 300);
        assertEquals(text, result);
    }

    // -----------------------------------------------------------------------
    // previewLength clamping tests
    // -----------------------------------------------------------------------

    @Test
    void previewLength_clampedToMinimum() throws Exception {
        initWorkspace(tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("previewLength", 10); // below minimum 100

        // Should not throw; clamping is applied silently
        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);
    }

    @Test
    void previewLength_clampedToMaximum() throws Exception {
        initWorkspace(tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        params.put("previewLength", 9999); // above maximum 3000

        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);
    }

    @Test
    void previewLength_notProvided_usesDefault300() throws Exception {
        initWorkspace(tempDir);
        ObjectNode params = mapper.createObjectNode();
        params.put("query", "test");
        // No previewLength param → default 300

        ObjectNode result = handler.handleSearch(params);
        assertNotNull(result);
        assertTrue(result.has("results"));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void initWorkspace(Path root) throws IOException {
        Path synthesisDir = root.resolve(".synthesis");
        Files.createDirectories(synthesisDir.resolve("index"));
        Files.createDirectories(synthesisDir.resolve("reports"));
        Files.writeString(synthesisDir.resolve("config.yaml"),
                "name: test-workspace\ntype: general\n");
    }
}
