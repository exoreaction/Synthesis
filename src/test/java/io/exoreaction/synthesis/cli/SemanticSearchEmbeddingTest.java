package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.ai.EmbeddingService;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for semantic search embedding-input construction.
 *
 * <p>Validates fix for issue #375: semantic search should embed actual file
 * content, not just the heuristic summary. The summary-only approach misses
 * files whose body discusses the searched concept but whose 1-2 sentence
 * summary omits it.
 */
class SemanticSearchEmbeddingTest {

    private EmbeddingService embeddingService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService("local", null, null);
    }

    // --- Embedding text construction ---

    @Test
    void buildEmbeddingText_includesFileContent() throws IOException {
        Path file = tempDir.resolve("AuthService.java");
        Files.writeString(file, "public class AuthService {\n    public void authenticate() {}\n}");

        SearchResult result = makeResult(file, "AuthService.java",
                "Authentication service", "class AuthService", "");

        String text = SearchCommand.buildEmbeddingText(result);
        assertTrue(text.contains("public class AuthService"),
                "Should include file content: " + text);
        assertTrue(text.contains("authenticate"),
                "Should include method from file body: " + text);
    }

    @Test
    void buildEmbeddingText_includesMetadata() throws IOException {
        Path file = tempDir.resolve("Service.java");
        Files.writeString(file, "public class Service {}");

        SearchResult result = makeResult(file, "Service.java",
                "Core service class", "class Service", "module:core");

        String text = SearchCommand.buildEmbeddingText(result);
        assertTrue(text.contains("Service.java"),
                "Should include filename: " + text);
        assertTrue(text.contains("Core service class"),
                "Should include summary: " + text);
    }

    @Test
    void buildEmbeddingText_fallsBackToSummaryWhenFileMissing() throws IOException {
        Path missing = tempDir.resolve("deleted.java");
        SearchResult result = makeResult(missing, "deleted.java",
                "A deleted file", "class Deleted", "");

        String text = SearchCommand.buildEmbeddingText(result);
        assertTrue(text.contains("A deleted file"),
                "Should fall back to summary: " + text);
        assertTrue(text.contains("deleted.java"),
                "Should still include filename: " + text);
    }

    @Test
    void buildEmbeddingText_fallsBackForBinaryFiles() throws IOException {
        Path binary = tempDir.resolve("image.png");
        Files.write(binary, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}); // PNG magic

        SearchResult result = makeResult(binary, "image.png",
                "Architecture diagram", "", "");

        String text = SearchCommand.buildEmbeddingText(result);
        // Binary file → readPreview returns "" → should fall back to summary
        assertTrue(text.contains("Architecture diagram"),
                "Should fall back to summary for binary: " + text);
    }

    @Test
    void buildEmbeddingText_contentEmbeddingImprovesSimilarity() throws IOException {
        // Create a file about OAuth that has a generic summary
        Path file = tempDir.resolve("OAuthHandler.java");
        Files.writeString(file, """
                public class OAuthHandler {
                    // Implements OAuth 2.0 authorization code flow
                    // with PKCE extension for public clients.
                    // Handles token refresh, scope validation,
                    // and JWT signature verification.
                    public void handleAuthorizationCode(String code) {}
                    public void refreshAccessToken(String refreshToken) {}
                    public boolean validateScope(String scope) { return true; }
                }
                """);

        SearchResult result = makeResult(file, "OAuthHandler.java",
                "Request handler class", "", "");

        // With content-based embedding, "OAuth token refresh" should match better
        // than it would with just the generic summary "Request handler class"
        String contentText = SearchCommand.buildEmbeddingText(result);
        float[] contentEmb = embeddingService.embed(contentText);

        // Summary-only (the old behavior)
        String summaryText = "Request handler class OAuthHandler.java";
        float[] summaryEmb = embeddingService.embed(summaryText);

        float[] queryEmb = embeddingService.embed("OAuth token refresh");

        float contentSim = EmbeddingService.cosineSimilarity(queryEmb, contentEmb);
        float summarySim = EmbeddingService.cosineSimilarity(queryEmb, summaryEmb);

        assertTrue(contentSim > summarySim,
                String.format("Content embedding (%.3f) should match 'OAuth token refresh' "
                        + "better than summary-only (%.3f)", contentSim, summarySim));
    }

    @Test
    void buildEmbeddingText_truncatesLargeFiles() throws IOException {
        // Create a file larger than MAX_EMBEDDING_CONTENT_CHARS
        Path file = tempDir.resolve("Large.java");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append("// Line ").append(i).append(" with some padding content\n");
        }
        Files.writeString(file, huge.toString());

        SearchResult result = makeResult(file, "Large.java", "Large file", "", "");

        String text = SearchCommand.buildEmbeddingText(result);
        // Should not exceed a reasonable size (metadata + truncated content)
        assertTrue(text.length() < 40_000,
                "Embedding text should be truncated, was " + text.length() + " chars");
    }

    @Test
    void buildEmbeddingText_emptyContentFallsBackToSummary() throws IOException {
        Path empty = tempDir.resolve("empty.md");
        Files.writeString(empty, "");

        SearchResult result = makeResult(empty, "empty.md",
                "Empty document", "", "");

        String text = SearchCommand.buildEmbeddingText(result);
        assertTrue(text.contains("Empty document"),
                "Empty file should fall back to summary");
    }

    @Test
    void buildEmbeddingText_structureIncludedWhenPresent() throws IOException {
        Path file = tempDir.resolve("module.ts");
        Files.writeString(file, "export function greet() { return 'hello'; }");

        SearchResult result = makeResult(file, "module.ts",
                "Greeting module", "function greet()", "module:frontend");

        String text = SearchCommand.buildEmbeddingText(result);
        assertTrue(text.contains("module:frontend"),
                "Should include structure: " + text);
    }

    // --- Helper ---

    private SearchResult makeResult(Path path, String fileName, String summary,
                                     String headings, String structure) {
        return new SearchResult(path, fileName, 1.0f, fileName,
                "CODE", "java", summary, headings, structure, 100L);
    }
}
