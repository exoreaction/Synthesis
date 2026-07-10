package io.exoreaction.synthesis.index;

import io.exoreaction.synthesis.ai.EmbeddingService;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for persisted vector search (HNSW) via Lucene KnnFloatVectorField.
 *
 * <p>Validates fix for issue #376: persist embeddings in the Lucene index
 * so semantic search uses O(log N) HNSW instead of O(N) brute-force
 * re-embedding on every query.
 */
class VectorSearchTest {

    @TempDir
    Path tempDir;

    private Path indexPath;
    private FileIndexer indexer;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        indexPath = tempDir.resolve("index");
        indexer = new FileIndexer();
        embeddingService = new EmbeddingService("local", null, null);
    }

    // --- FileIndexer: addEmbedding ---

    @Test
    void addEmbedding_addsVectorFieldToDocument() throws IOException {
        var doc = createTestDocument("auth.java", "Authentication service");
        float[] embedding = embeddingService.embed("authentication service");

        FileIndexer.addEmbedding(doc, embedding, "text-embedding-3-small");

        // Verify the vector field exists
        assertNotNull(doc.getField(DocumentFields.EMBEDDING),
                "Document should have embedding field");
        assertEquals("text-embedding-3-small",
                doc.get(DocumentFields.EMBEDDING_MODEL),
                "Document should have embedding model field");
    }

    @Test
    void addEmbedding_nullEmbeddingIsNoOp() throws IOException {
        var doc = createTestDocument("file.java", "Some file");
        FileIndexer.addEmbedding(doc, null, "model");
        assertNull(doc.getField(DocumentFields.EMBEDDING));
    }

    @Test
    void addEmbedding_zeroLengthEmbeddingIsNoOp() throws IOException {
        var doc = createTestDocument("file.java", "Some file");
        FileIndexer.addEmbedding(doc, new float[0], "model");
        assertNull(doc.getField(DocumentFields.EMBEDDING));
    }

    // --- SearchIndex: searchByVector ---

    @Test
    void searchByVector_findsNearestNeighbors() throws IOException {
        // Index 3 documents with embeddings
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "AuthService.java", "Java authentication service");
            addDocWithEmbedding(index, "DbMigration.py", "Python database migration script");
            addDocWithEmbedding(index, "LoginController.java", "User login controller");
            index.commit();
        }

        // Search for "authentication login"
        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            float[] queryVec = embeddingService.embed("authentication login");
            List<SearchResult> results = index.searchByVector(queryVec, 3);

            assertFalse(results.isEmpty(), "Should find results");
            // Auth-related files should rank higher than database migration
            String topFile = results.get(0).fileName();
            assertTrue(topFile.contains("Auth") || topFile.contains("Login"),
                    "Top result should be auth-related, got: " + topFile);
        }
    }

    @Test
    void searchByVector_respectsLimit() throws IOException {
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "A.java", "First file about testing");
            addDocWithEmbedding(index, "B.java", "Second file about testing");
            addDocWithEmbedding(index, "C.java", "Third file about testing");
            index.commit();
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            float[] queryVec = embeddingService.embed("testing");
            List<SearchResult> results = index.searchByVector(queryVec, 2);
            assertEquals(2, results.size(), "Should respect limit");
        }
    }

    @Test
    void searchByVector_emptyIndexReturnsEmptyList() throws IOException {
        try (SearchIndex index = new SearchIndex(indexPath)) {
            index.commit(); // create empty index
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            float[] queryVec = embeddingService.embed("anything");
            List<SearchResult> results = index.searchByVector(queryVec, 10);
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void searchByVector_worksWithMixedDocuments() throws IOException {
        // Some docs have embeddings, some don't
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "WithVector.java", "File with embedding");

            // Doc without embedding
            var doc = createTestDocument("NoVector.java", "File without embedding");
            index.addDocument(doc);

            index.commit();
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            float[] queryVec = embeddingService.embed("embedding");
            List<SearchResult> results = index.searchByVector(queryVec, 10);
            // Should find at least the one with a vector
            assertFalse(results.isEmpty());
        }
    }

    // --- Embedding model tracking ---

    @Test
    void searchByVector_resultIncludesScore() throws IOException {
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "Match.java", "Exact query match text");
            index.commit();
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            float[] queryVec = embeddingService.embed("Exact query match text");
            List<SearchResult> results = index.searchByVector(queryVec, 1);
            assertEquals(1, results.size());
            assertTrue(results.get(0).score() > 0, "Score should be positive");
        }
    }

    @Test
    void hasVectors_returnsTrueWhenVectorsPresent() throws IOException {
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "A.java", "File with vector");
            index.commit();
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            assertTrue(index.hasVectors(), "Should detect vectors in index");
        }
    }

    @Test
    void hasVectors_returnsFalseWhenNoVectors() throws IOException {
        try (SearchIndex index = new SearchIndex(indexPath)) {
            var doc = createTestDocument("A.java", "No vector");
            index.addDocument(doc);
            index.commit();
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            assertFalse(index.hasVectors(), "Should detect no vectors in index");
        }
    }

    // --- Round-trip persistence ---

    @Test
    void vectorsPersistAcrossCloseAndReopen() throws IOException {
        // Write
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "Persisted.java", "This should persist");
            index.commit();
        }

        // Reopen read-only and query
        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            float[] queryVec = embeddingService.embed("This should persist");
            List<SearchResult> results = index.searchByVector(queryVec, 1);
            assertEquals(1, results.size());
            assertEquals("Persisted.java", results.get(0).fileName());
        }
    }

    @Test
    void keywordSearchUnaffectedByVectors() throws IOException {
        // Adding vectors must NOT break existing keyword search
        try (SearchIndex index = new SearchIndex(indexPath)) {
            addDocWithEmbedding(index, "AuthService.java", "Authentication service class");
            index.commit();
        }

        try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
            // Keyword search should still work
            List<SearchResult> results = index.search("authentication", null, 10);
            assertFalse(results.isEmpty(), "Keyword search should still work");
            assertTrue(results.get(0).fileName().contains("Auth"));
        }
    }

    // --- Helpers ---

    private void addDocWithEmbedding(SearchIndex index, String fileName, String content) throws IOException {
        var doc = createTestDocument(fileName, content);
        float[] embedding = embeddingService.embed(content);
        FileIndexer.addEmbedding(doc, embedding, "local");
        index.addDocument(doc);
    }

    private org.apache.lucene.document.Document createTestDocument(String fileName, String content) {
        Path filePath = tempDir.resolve(fileName);
        try { Files.writeString(filePath, content); } catch (IOException ignored) {}

        FileMetadata metadata = new FileMetadata(
                filePath, fileName, fileName,
                ".java", FileUtils.FileType.CODE, "java",
                content.length(), Instant.now(), null
        );
        AnalysisResult analysis = new AnalysisResult(
                content,    // summary
                List.of(),  // headings
                List.of(),  // keywords
                List.of(),  // links
                "",         // structure
                Map.of(),   // metrics
                content     // contentPreview
        );
        return indexer.createDocument(metadata, analysis);
    }
}
