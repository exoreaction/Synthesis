package io.exoreaction.synthesis.validate;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
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
 * Tests for {@link GapDetector}.
 *
 * <p>Integration tests use a real (in-process) Lucene index written to a TempDir.
 */
class GapDetectorTest {

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
    void detectGaps_returnsEmptyWhenAllCovered() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}", "src/main/java/index/SearchIndex.java");
        addCodeDocument("FileIndexer.java", "public class FileIndexer {}", "src/main/java/index/FileIndexer.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "Use SearchIndex for queries and FileIndexer to add documents.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        assertTrue(gaps.isEmpty(), "No gaps expected when all classes are mentioned in skills");
    }

    @Test
    void detectGaps_findsUncoveredFile() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}", "src/main/java/index/SearchIndex.java");
        addCodeDocument("FileIndexer.java", "public class FileIndexer {}", "src/main/java/index/FileIndexer.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        // Only mentions SearchIndex, not FileIndexer
        Files.writeString(skillFile, "Use SearchIndex for queries.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        assertFalse(gaps.isEmpty(), "FileIndexer should be flagged as uncovered");
        assertTrue(gaps.stream().anyMatch(g -> g.className().equals("FileIndexer")),
                "FileIndexer should appear in gap results");
    }

    @Test
    void detectGaps_skipsTestClasses() throws IOException {
        addCodeDocument("SearchIndexTest.java", "public class SearchIndexTest {}", "src/test/java/SearchIndexTest.java");
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}", "src/main/java/SearchIndex.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "Use SearchIndex.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        assertTrue(gaps.stream().noneMatch(g -> g.className().endsWith("Test")),
                "Test classes should be excluded from gap analysis");
    }

    @Test
    void detectGaps_skipsPackageInfo() throws IOException {
        addCodeDocument("package-info.java", "/** Package info */", "src/main/java/package-info.java");
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}", "src/main/java/SearchIndex.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "Use SearchIndex.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        assertTrue(gaps.stream().noneMatch(g -> g.className().equals("package-info")),
                "package-info should be excluded from gap analysis");
    }

    @Test
    void detectGaps_prioritizesCliFiles() throws IOException {
        addCodeDocument("CliHandler.java", "public class CliHandler {}", "src/main/java/cli/CliHandler.java");
        addCodeDocument("UtilHelper.java", "public class UtilHelper {}", "src/main/java/util/UtilHelper.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        // Neither mentioned in skills
        Files.writeString(skillFile, "No classes mentioned here.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        GapDetector.GapResult cliResult = gaps.stream()
                .filter(g -> g.className().equals("CliHandler"))
                .findFirst().orElse(null);
        GapDetector.GapResult utilResult = gaps.stream()
                .filter(g -> g.className().equals("UtilHelper"))
                .findFirst().orElse(null);

        assertNotNull(cliResult, "CliHandler should be in gaps");
        assertNotNull(utilResult, "UtilHelper should be in gaps");
        assertTrue(cliResult.priority() > utilResult.priority(),
                "CLI file should have higher priority than util file");
    }

    @Test
    void detectGaps_prioritizesMcpFiles() throws IOException {
        addCodeDocument("McpHandler.java", "public class McpHandler {}", "src/main/java/mcp/McpHandler.java");
        addCodeDocument("UtilHelper.java", "public class UtilHelper {}", "src/main/java/util/UtilHelper.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "No classes mentioned here.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        GapDetector.GapResult mcpResult = gaps.stream()
                .filter(g -> g.className().equals("McpHandler"))
                .findFirst().orElse(null);
        GapDetector.GapResult utilResult = gaps.stream()
                .filter(g -> g.className().equals("UtilHelper"))
                .findFirst().orElse(null);

        assertNotNull(mcpResult, "McpHandler should be in gaps");
        assertNotNull(utilResult, "UtilHelper should be in gaps");
        assertTrue(mcpResult.priority() > utilResult.priority(),
                "MCP file should have higher priority than util file");
    }

    @Test
    void detectGaps_resultIsSortedByPriority() throws IOException {
        // Create files in different priority directories
        addCodeDocument("McpHandler.java", "public class McpHandler {}", "src/main/java/mcp/McpHandler.java");
        addCodeDocument("CliHandler.java", "public class CliHandler {}", "src/main/java/cli/CliHandler.java");
        addCodeDocument("UtilHelper.java", "public class UtilHelper {}", "src/main/java/util/UtilHelper.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "No classes mentioned here.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        assertFalse(gaps.isEmpty(), "Should have gaps");
        // Verify sorted by priority descending
        for (int i = 1; i < gaps.size(); i++) {
            assertTrue(gaps.get(i - 1).priority() >= gaps.get(i).priority(),
                    "Results should be sorted by priority descending: " +
                    gaps.get(i - 1).className() + " (p=" + gaps.get(i - 1).priority() + ") before " +
                    gaps.get(i).className() + " (p=" + gaps.get(i).priority() + ")");
        }
    }

    @Test
    void detectGaps_deduplicatesClassNames() throws IOException {
        // Add the same class name twice (e.g., different content matches)
        addCodeDocument("SearchIndex.java", "public class SearchIndex { void search() {} }",
                "src/main/java/index/SearchIndex.java");
        // Index another document whose filename is different but the content search
        // might return "SearchIndex" again — we use a separate named file to ensure
        // both appear in wildcard results
        addCodeDocument("SearchIndex.java", "public class SearchIndex { void commit() {} }",
                "src/main/java/other/SearchIndex.java");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "No classes mentioned.\n");

        GapDetector detector = new GapDetector();
        List<GapDetector.GapResult> gaps = detector.detectGaps(index, List.of(skillFile));

        long searchIndexCount = gaps.stream()
                .filter(g -> g.className().equals("SearchIndex"))
                .count();
        assertTrue(searchIndexCount <= 1,
                "SearchIndex should appear at most once in results, but found " + searchIndexCount);
    }

    // -----------------------------------------------------------------------
    // Priority computation tests
    // -----------------------------------------------------------------------

    @Test
    void computePriority_cliFilesScoreHigher() {
        GapDetector detector = new GapDetector();
        int cliPriority = detector.computePriority("src/main/java/cli/MyCommand.java", 100);
        int utilPriority = detector.computePriority("src/main/java/util/Helper.java", 100);
        assertTrue(cliPriority > utilPriority, "CLI files should have higher priority");
    }

    @Test
    void computePriority_largeFilesScoreHigher() {
        GapDetector detector = new GapDetector();
        int largePriority = detector.computePriority("src/main/java/util/Helper.java", 30000);
        int smallPriority = detector.computePriority("src/main/java/util/Helper.java", 100);
        assertTrue(largePriority > smallPriority, "Large files should have higher priority");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void addCodeDocument(String fileName, String content, String relativePath) throws IOException {
        // Create the file at the relative path within tempDir so FileMetadata.of
        // computes the correct relative path including directory structure
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        FileMetadata metadata = FileMetadata.of(file, tempDir, content.length(), Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.builder()
                .summary(fileName)
                .contentPreview(content)
                .build();
        index.addDocument(fileIndexer.createDocument(metadata, analysis));
        index.commit();
    }
}
