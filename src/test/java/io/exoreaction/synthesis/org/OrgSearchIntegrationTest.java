package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for organization-scoped search and indexing.
 * Verifies that the ORGANIZATION and CLIENT Lucene fields work correctly
 * for filtering search results.
 */
class OrgSearchIntegrationTest {

    @TempDir
    Path tempDir;

    private SearchIndex index;
    private FileIndexer fileIndexer;

    @BeforeEach
    void setUp() throws IOException {
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);
        index = new SearchIndex(indexDir);
        fileIndexer = new FileIndexer();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void search_withOrgFilter_onlyReturnsMatchingOrg() throws IOException {
        // Index files from two organizations
        indexFile("eXOReaction/business/strategy.md", "business strategy plan",
                "eXOReaction", null);
        indexFile("Quadim/business/roadmap.md", "product roadmap strategy",
                "Quadim", null);
        indexFile("Cantara/docs/readme.md", "framework strategy docs",
                "Cantara", null);
        index.commit();

        // Search with org filter
        List<SearchResult> results = index.search("strategy", null, null,
                "eXOReaction", null, 20);

        assertEquals(1, results.size());
        assertTrue(results.get(0).relativePath().startsWith("eXOReaction"));
    }

    @Test
    void search_withClientFilter_onlyReturnsMatchingClient() throws IOException {
        indexFile("eXOReaction/clients/Elprint/project.md", "Elprint velocity project",
                "eXOReaction", "Elprint");
        indexFile("eXOReaction/clients/SpareBank1/meeting.md", "SpareBank meeting notes project",
                "eXOReaction", "SpareBank1");
        indexFile("eXOReaction/business/general.md", "general business project",
                "eXOReaction", null);
        index.commit();

        List<SearchResult> results = index.search("project", null, null,
                null, "Elprint", 20);

        assertEquals(1, results.size());
        assertTrue(results.get(0).relativePath().contains("Elprint"));
    }

    @Test
    void search_withOrgAndClientFilter_narrowsCorrectly() throws IOException {
        indexFile("eXOReaction/clients/Elprint/doc.md", "authentication setup docs",
                "eXOReaction", "Elprint");
        indexFile("Quadim/clients/CatalystOne/doc.md", "authentication config docs",
                "Quadim", "CatalystOne");
        indexFile("eXOReaction/business/auth.md", "authentication policy docs",
                "eXOReaction", null);
        index.commit();

        List<SearchResult> results = index.search("authentication", null, null,
                "eXOReaction", "Elprint", 20);

        assertEquals(1, results.size());
        assertTrue(results.get(0).relativePath().contains("Elprint"));
    }

    @Test
    void search_withoutOrgFilter_returnsAll() throws IOException {
        indexFile("eXOReaction/doc.md", "testing methodology docs",
                "eXOReaction", null);
        indexFile("Quadim/doc.md", "testing framework docs",
                "Quadim", null);
        index.commit();

        List<SearchResult> results = index.search("testing", null, null,
                null, null, 20);

        assertEquals(2, results.size());
    }

    @Test
    void search_nonExistentOrg_returnsEmpty() throws IOException {
        indexFile("eXOReaction/doc.md", "some content docs",
                "eXOReaction", null);
        index.commit();

        List<SearchResult> results = index.search("content", null, null,
                "NonExistent", null, 20);

        assertTrue(results.isEmpty());
    }

    @Test
    void listAll_withOrgFilter_filtersCorrectly() throws IOException {
        indexFile("eXOReaction/a.md", "content a", "eXOReaction", null);
        indexFile("eXOReaction/b.md", "content b", "eXOReaction", null);
        indexFile("Quadim/c.md", "content c", "Quadim", null);
        index.commit();

        List<SearchResult> results = index.listAll(null, null,
                "eXOReaction", null, 100);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.relativePath().startsWith("eXOReaction")));
    }

    @Test
    void listAll_withClientFilter_filtersCorrectly() throws IOException {
        indexFile("eXOReaction/clients/Elprint/a.md", "data", "eXOReaction", "Elprint");
        indexFile("eXOReaction/clients/Elprint/b.md", "data", "eXOReaction", "Elprint");
        indexFile("eXOReaction/clients/SpareBank1/c.md", "data", "eXOReaction", "SpareBank1");
        indexFile("eXOReaction/business/d.md", "data", "eXOReaction", null);
        index.commit();

        List<SearchResult> results = index.listAll(null, null,
                null, "Elprint", 100);

        assertEquals(2, results.size());
    }

    @Test
    void search_combinesOrgAndTypeFilter() throws IOException {
        indexFile("eXOReaction/code.java", "public class Auth", "eXOReaction", null);
        indexFile("eXOReaction/doc.md", "authentication docs", "eXOReaction", null);
        indexFile("Quadim/code.java", "public class Auth", "Quadim", null);
        index.commit();

        // Search for "auth" in eXOReaction, only MARKDOWN
        List<SearchResult> results = index.search("authentication", "MARKDOWN", null,
                "eXOReaction", null, 20);

        assertEquals(1, results.size());
        assertEquals("MARKDOWN", results.get(0).fileType());
    }

    // --- Helper ---

    private void indexFile(String relativePath, String content,
                           String org, String client) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);

        FileMetadata metadata = FileMetadata.of(file, tempDir,
                content.length(), Instant.now(), null);

        AnalysisResult analysis = AnalysisResult.minimal(content, content);

        index.addDocument(fileIndexer.createDocument(metadata, analysis, null, org, client));
    }
}
