package io.exoreaction.synthesis.index;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {

    @TempDir
    Path tempDir;

    private SearchIndex index;
    private FileIndexer fileIndexer;
    private Path indexPath;

    @BeforeEach
    void setUp() throws IOException {
        indexPath = tempDir.resolve("index");
        index = new SearchIndex(indexPath);
        fileIndexer = new FileIndexer();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void addAndSearchDocument() throws IOException {
        // Create a test file
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Knowledge Infrastructure\n\nThis is about organizing knowledge.");

        FileMetadata metadata = FileMetadata.of(testFile, tempDir, 60, Instant.now(), "abc123");
        AnalysisResult analysis = AnalysisResult.builder()
                .summary("Knowledge Infrastructure")
                .headings(List.of("Knowledge Infrastructure"))
                .keywords(List.of("knowledge", "infrastructure"))
                .contentPreview("# Knowledge Infrastructure\n\nThis is about organizing knowledge.")
                .build();

        index.addDocument(fileIndexer.createDocument(metadata, analysis));
        index.commit();

        // Search for it
        List<SearchResult> results = index.search("knowledge infrastructure", 10);

        assertFalse(results.isEmpty(), "Should find at least one result");
        assertEquals("test.md", results.get(0).fileName());
        assertTrue(results.get(0).score() > 0, "Score should be positive");
    }

    @Test
    void searchByFileName() throws IOException {
        addTestDocument("README.md", "Project documentation", "readme", List.of("documentation"));
        addTestDocument("Main.java", "Java main class", "java", List.of("main", "java"));

        List<SearchResult> results = index.search("README", 10);

        assertFalse(results.isEmpty());
        assertEquals("README.md", results.get(0).fileName());
    }

    @Test
    void searchWithTypeFilter() throws IOException {
        addTestDocument("README.md", "Documentation", "documentation", List.of("docs"));
        addTestDocument("App.java", "Java application", "java code", List.of("app"));
        addTestDocument("config.yaml", "Configuration", "yaml config", List.of("config"));

        // Search only CODE type
        List<SearchResult> results = index.search("app", "CODE", 10);

        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(r -> "CODE".equals(r.fileType())),
                "All results should be CODE type");
    }

    @Test
    void searchReturnsEmptyForNoMatch() throws IOException {
        addTestDocument("test.md", "Test file", "some content", List.of("test"));

        List<SearchResult> results = index.search("xyznonexistent", 10);

        assertTrue(results.isEmpty(), "Should return no results for non-matching query");
    }

    @Test
    void searchRespectsLimit() throws IOException {
        for (int i = 0; i < 10; i++) {
            addTestDocument("file" + i + ".md", "File " + i, "content " + i, List.of("file"));
        }

        List<SearchResult> results = index.search("file", 3);

        assertEquals(3, results.size(), "Should return at most 3 results");
    }

    @Test
    void documentCountIsAccurate() throws IOException {
        assertEquals(0, index.documentCount());

        addTestDocument("file1.md", "First", "content1", List.of());
        addTestDocument("file2.md", "Second", "content2", List.of());
        addTestDocument("file3.md", "Third", "content3", List.of());

        assertEquals(3, index.documentCount());
    }

    @Test
    void updateDocumentReplacesExisting() throws IOException {
        addTestDocument("test.md", "Original", "original content", List.of("original"));

        // Update with new content
        addTestDocument("test.md", "Updated", "updated content", List.of("updated"));

        assertEquals(1, index.documentCount(), "Should have 1 document (updated, not duplicated)");

        List<SearchResult> results = index.search("updated", 10);
        assertFalse(results.isEmpty());
        assertEquals("Updated", results.get(0).summary());
    }

    @Test
    void deleteAllClearsIndex() throws IOException {
        addTestDocument("file1.md", "First", "content1", List.of());
        addTestDocument("file2.md", "Second", "content2", List.of());

        index.deleteAll();

        assertEquals(0, index.documentCount());
    }

    @Test
    void searchHandlesBlankQuery() throws IOException {
        addTestDocument("test.md", "Test", "content", List.of());

        List<SearchResult> results = index.search("", 10);
        assertTrue(results.isEmpty());

        results = index.search("   ", 10);
        assertTrue(results.isEmpty());

        results = index.search(null, 10);
        assertTrue(results.isEmpty());
    }

    // Helper method to add a test document
    private void addTestDocument(String fileName, String summary, String content,
                                  List<String> keywords) throws IOException {
        Path testFile = tempDir.resolve(fileName);
        Files.writeString(testFile, content);

        FileMetadata metadata = FileMetadata.of(testFile, tempDir,
                content.length(), Instant.now(), null);

        AnalysisResult analysis = AnalysisResult.builder()
                .summary(summary)
                .keywords(keywords)
                .contentPreview(content)
                .build();

        index.addDocument(fileIndexer.createDocument(metadata, analysis));
        index.commit();
    }

    // --- Read-only mode tests (#86) ---

    @Test
    void openReadOnly_searchesWithoutWriteLock() throws IOException {
        addTestDocument("guide.md", "deployment guide", "deployment", List.of("deployment", "guide"));
        index.close();
        index = null;

        // Read-only open should work even though we just closed the writer
        try (SearchIndex ro = SearchIndex.openReadOnly(indexPath)) {
            List<SearchResult> results = ro.search("deployment", 10);
            assertFalse(results.isEmpty(), "Read-only search must find results");
            assertEquals("guide.md", results.get(0).fileName());
        }
    }

    @Test
    void openReadOnly_multipleInstancesConcurrently() throws Exception {
        addTestDocument("api.md", "API reference documentation", "api reference", List.of("api", "reference"));
        index.close();
        index = null;

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try (SearchIndex ro = SearchIndex.openReadOnly(indexPath)) {
                    return ro.search("api", 10).size();
                }
            });
        }

        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        for (Future<Integer> f : futures) {
            assertTrue(f.get() > 0, "Every concurrent read-only search must return results");
        }
    }

    @Test
    void openReadOnly_throwsOnWriteAttempt() throws IOException {
        addTestDocument("doc.md", "some content", "summary", List.of());
        index.close();
        index = null;

        try (SearchIndex ro = SearchIndex.openReadOnly(indexPath)) {
            assertThrows(IllegalStateException.class, () -> ro.deleteAll());
            assertThrows(IllegalStateException.class, () -> ro.commit());
        }
    }

    @Test
    void openReadOnly_documentCountMatchesWriter() throws IOException {
        addTestDocument("a.md", "alpha content", "alpha", List.of());
        addTestDocument("b.md", "beta content", "beta", List.of());
        int writerCount = index.documentCount();
        index.close();
        index = null;

        try (SearchIndex ro = SearchIndex.openReadOnly(indexPath)) {
            assertEquals(writerCount, ro.documentCount(),
                    "documentCount() in read-only mode must match committed document count");
        }
    }
}
