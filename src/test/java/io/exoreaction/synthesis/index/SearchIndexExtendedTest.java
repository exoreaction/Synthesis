package io.exoreaction.synthesis.index;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for SearchIndex -- listAll, deleteByRelativePath.
 */
class SearchIndexExtendedTest {

    @TempDir
    Path tempDir;

    private SearchIndex index;
    private FileIndexer fileIndexer;

    @BeforeEach
    void setUp() throws IOException {
        index = new SearchIndex(tempDir.resolve("index"));
        fileIndexer = new FileIndexer();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (index != null) index.close();
    }

    @Test
    void testListAll() throws IOException {
        addTestDocument("file1.md", FileUtils.FileType.MARKDOWN, "Test document 1");
        addTestDocument("file2.java", FileUtils.FileType.CODE, "Java source");
        addTestDocument("file3.yaml", FileUtils.FileType.YAML, "Config file");
        index.commit();

        List<SearchResult> results = index.listAll(null, 100);
        assertEquals(3, results.size(), "Should list all documents");
    }

    @Test
    void testListAllWithFilter() throws IOException {
        addTestDocument("file1.md", FileUtils.FileType.MARKDOWN, "Test document 1");
        addTestDocument("file2.java", FileUtils.FileType.CODE, "Java source");
        addTestDocument("file3.yaml", FileUtils.FileType.YAML, "Config file");
        index.commit();

        List<SearchResult> codeOnly = index.listAll("CODE", 100);
        assertEquals(1, codeOnly.size(), "Should filter by type");
        assertEquals("file2.java", codeOnly.get(0).fileName());
    }

    @Test
    void testDeleteByRelativePath() throws IOException {
        addTestDocument("file1.md", FileUtils.FileType.MARKDOWN, "Document 1");
        addTestDocument("file2.md", FileUtils.FileType.MARKDOWN, "Document 2");
        index.commit();

        assertEquals(2, index.documentCount());

        index.deleteByRelativePath("file1.md");
        index.commit();

        assertEquals(1, index.documentCount());

        // The remaining document should be file2
        List<SearchResult> results = index.listAll(null, 100);
        assertEquals(1, results.size());
        assertEquals("file2.md", results.get(0).fileName());
    }

    @Test
    void testListAllWithLimit() throws IOException {
        for (int i = 0; i < 10; i++) {
            addTestDocument("file" + i + ".md", FileUtils.FileType.MARKDOWN, "Document " + i);
        }
        index.commit();

        List<SearchResult> limited = index.listAll(null, 5);
        assertEquals(5, limited.size(), "Should respect limit");
    }

    private void addTestDocument(String name, FileUtils.FileType type, String summary) throws IOException {
        FileMetadata fm = new FileMetadata(
                tempDir.resolve(name), name, name,
                name.substring(name.lastIndexOf('.')),
                type, type == FileUtils.FileType.CODE ? "Java" : null,
                100, Instant.now(), "hash"
        );
        AnalysisResult analysis = AnalysisResult.builder()
                .summary(summary)
                .contentPreview("content of " + name)
                .build();
        index.addDocument(fileIndexer.createDocument(fm, analysis));
    }
}
