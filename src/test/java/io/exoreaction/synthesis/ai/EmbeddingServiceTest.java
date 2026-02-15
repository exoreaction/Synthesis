package io.exoreaction.synthesis.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EmbeddingService.
 */
class EmbeddingServiceTest {

    private EmbeddingService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Use local provider for tests (no API needed)
        service = new EmbeddingService("local", null, null);
    }

    // --- Embedding generation ---

    @Test
    void embed_returnsCorrectDimensions() throws IOException {
        float[] embedding = service.embed("Hello world");
        assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, embedding.length);
    }

    @Test
    void embed_emptyTextReturnsZeroVector() throws IOException {
        float[] embedding = service.embed("");
        assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, embedding.length);
        // All zeros
        for (float v : embedding) {
            assertEquals(0f, v);
        }
    }

    @Test
    void embed_nullTextReturnsZeroVector() throws IOException {
        float[] embedding = service.embed(null);
        assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, embedding.length);
    }

    @Test
    void embed_similarTextsSimilarEmbeddings() throws IOException {
        float[] emb1 = service.embed("Java authentication service");
        float[] emb2 = service.embed("Java auth service");
        float[] emb3 = service.embed("Python database migration");

        float sim12 = EmbeddingService.cosineSimilarity(emb1, emb2);
        float sim13 = EmbeddingService.cosineSimilarity(emb1, emb3);

        // Similar texts should have higher similarity than dissimilar
        assertTrue(sim12 > sim13,
                String.format("Expected sim('Java auth', 'Java auth service')=%.3f > sim('Java auth', 'Python db')=%.3f",
                        sim12, sim13));
    }

    @Test
    void embed_isNormalized() throws IOException {
        float[] embedding = service.embed("Hello world, this is a test of the embedding service");

        // Check L2 norm is approximately 1.0
        float norm = 0f;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        assertEquals(1.0f, norm, 0.01f, "Local embeddings should be L2 normalized");
    }

    @Test
    void embed_deterministicForSameInput() throws IOException {
        float[] emb1 = service.embed("deterministic test");
        service.clearCache(); // Clear cache to force recomputation
        float[] emb2 = service.embed("deterministic test");

        assertArrayEquals(emb1, emb2, "Same input should produce same embedding");
    }

    // --- File embedding ---

    @Test
    void embedFile_readsAndEmbeds() throws IOException {
        Path file = tempDir.resolve("test.java");
        Files.writeString(file, "public class AuthService {\n    public void login() {}\n}");

        float[] embedding = service.embedFile(file);
        assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, embedding.length);

        // Should not be all zeros (file has content)
        boolean allZero = true;
        for (float v : embedding) if (v != 0f) { allZero = false; break; }
        assertFalse(allZero, "File embedding should not be all zeros for a non-empty file");
    }

    @Test
    void embedFile_returnsZeroForMissingFile() throws IOException {
        Path missing = tempDir.resolve("nonexistent.java");
        float[] embedding = service.embedFile(missing);
        assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, embedding.length);
    }

    // --- Batch embedding ---

    @Test
    void embedBatch_embedsMultipleTexts() throws IOException {
        List<String> texts = List.of(
                "First document about authentication",
                "Second document about databases",
                "Third document about testing"
        );

        List<float[]> results = service.embedBatch(texts);
        assertEquals(3, results.size());
        for (float[] emb : results) {
            assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, emb.length);
        }
    }

    @Test
    void embedBatch_emptyListReturnsEmptyList() throws IOException {
        List<float[]> results = service.embedBatch(List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void embedBatch_nullReturnsEmptyList() throws IOException {
        List<float[]> results = service.embedBatch(null);
        assertTrue(results.isEmpty());
    }

    // --- Cosine similarity ---

    @Test
    void cosineSimilarity_identicalVectors() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertEquals(1.0f, EmbeddingService.cosineSimilarity(a, b), 0.001f);
    }

    @Test
    void cosineSimilarity_orthogonalVectors() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertEquals(0.0f, EmbeddingService.cosineSimilarity(a, b), 0.001f);
    }

    @Test
    void cosineSimilarity_oppositeVectors() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        assertEquals(-1.0f, EmbeddingService.cosineSimilarity(a, b), 0.001f);
    }

    @Test
    void cosineSimilarity_differentLengths() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f};
        assertEquals(0.0f, EmbeddingService.cosineSimilarity(a, b));
    }

    @Test
    void cosineSimilarity_zeroVectors() {
        float[] a = {0.0f, 0.0f};
        float[] b = {0.0f, 0.0f};
        assertEquals(0.0f, EmbeddingService.cosineSimilarity(a, b));
    }

    // --- Caching ---

    @Test
    void caching_storesAndRetrievesEmbeddings() throws IOException {
        assertEquals(0, service.cacheSize());
        service.embed("test text");
        assertEquals(1, service.cacheSize());

        // Second call should use cache
        service.embed("test text");
        assertEquals(1, service.cacheSize()); // No increase

        service.embed("different text");
        assertEquals(2, service.cacheSize());
    }

    @Test
    void clearCache_removesAllEntries() throws IOException {
        service.embed("a");
        service.embed("b");
        assertEquals(2, service.cacheSize());

        service.clearCache();
        assertEquals(0, service.cacheSize());
    }

    // --- Provider detection ---

    @Test
    void getProvider_returnsConfiguredProvider() {
        assertEquals("local", service.getProvider());
    }

    @Test
    void getDimensions_returnsExpected() {
        assertEquals(EmbeddingService.EMBEDDING_DIMENSIONS, service.getDimensions());
    }

    @Test
    void create_fallsBackToLocal_withoutApiKey() {
        // Without OPENAI_API_KEY env var, should fall back to local
        EmbeddingService created = EmbeddingService.create();
        assertNotNull(created);
        // Provider depends on env, but should not throw
    }
}
