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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DriftDetector}.
 *
 * <p>Integration tests use a real (in-process) Lucene index written to a TempDir.
 * Unit tests for the parsing logic use only strings.
 */
class DriftDetectorTest {

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

    // -----------------------------------------------------------------------
    // extractCamelCaseIdentifiers — pure parsing unit tests
    // -----------------------------------------------------------------------

    @Test
    void extract_matchesStandardCamelCase() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("Use SearchIndex for queries");
        assertTrue(ids.contains("SearchIndex"));
    }

    @Test
    void extract_matchesMultiPartIdentifier() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("ActivityLogUpdater.update()");
        assertTrue(ids.contains("ActivityLogUpdater"));
    }

    @Test
    void extract_matchesMcpPrefixStyle() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("McpCommand handles tool calls");
        assertTrue(ids.contains("McpCommand"));
    }

    @Test
    void extract_rejectsSingleCapitalWord() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("Synthesis is a great tool");
        assertFalse(ids.contains("Synthesis"), "Single-group words must not be extracted");
    }

    @Test
    void extract_rejectsAllCapsAcronym() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("The MCP server handles API calls");
        assertFalse(ids.contains("MCP"));
        assertFalse(ids.contains("API"));
    }

    @Test
    void extract_rejectsShortIdentifiers() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("NaN Ok NoOp");
        assertTrue(ids.stream().allMatch(id -> id.length() >= DriftDetector.MIN_LENGTH),
                "All returned identifiers must meet minimum length");
    }

    @Test
    void extract_rejectsStopwords() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers(
                "Push to GitHub using GitLab CI via Docker");
        assertFalse(ids.contains("GitHub"), "GitHub is a stopword");
        assertFalse(ids.contains("GitLab"), "GitLab is a stopword");
        assertFalse(ids.contains("Docker"), "Docker is a stopword");
    }

    @Test
    void extract_deduplicatesWithinLine() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers(
                "SearchIndex.search() calls SearchIndex.commit()");
        assertEquals(1, ids.stream().filter(id -> id.equals("SearchIndex")).count());
    }

    @Test
    void extract_returnsEmptyForPlainText() {
        Set<String> ids = DriftDetector.extractCamelCaseIdentifiers("this is all lowercase text");
        assertTrue(ids.isEmpty());
    }

    // -----------------------------------------------------------------------
    // existsInIndex — index-aware unit tests
    // -----------------------------------------------------------------------

    @Test
    void existsInIndex_returnsTrueForIndexedFilename() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        DriftDetector detector = new DriftDetector();
        assertTrue(detector.existsInIndex("SearchIndex", index));
    }

    @Test
    void existsInIndex_returnsTrueForContentMatch() throws IOException {
        // File is named differently but content mentions the class
        addCodeDocument("IndexEngine.java", "class IndexEngine extends SearchIndex {}");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        DriftDetector detector = new DriftDetector();
        // "SearchIndex" appears in the content of IndexEngine.java
        assertTrue(detector.existsInIndex("SearchIndex", index));
    }

    @Test
    void existsInIndex_returnsFalseWhenAbsent() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        DriftDetector detector = new DriftDetector();
        assertFalse(detector.existsInIndex("SearchService", index));
    }

    @Test
    void existsInIndex_returnsFalseOnEmptyIndex() throws IOException {
        // No documents added
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        DriftDetector detector = new DriftDetector();
        assertFalse(detector.existsInIndex("SearchIndex", index));
    }

    // -----------------------------------------------------------------------
    // detect — full integration tests
    // -----------------------------------------------------------------------

    @Test
    void detect_returnsEmptyWhenAllIdentifiersFound() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}");
        addCodeDocument("FileIndexer.java", "public class FileIndexer {}");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile,
                "Use SearchIndex for search and FileIndexer to add documents.\n");

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertTrue(issues.isEmpty(), "No drift expected when all classes exist");
    }

    @Test
    void detect_flagsMissingClass() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "Use SearchService to search.\n");

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertFalse(issues.isEmpty(), "SearchService should be flagged");
        assertEquals("SearchService", issues.get(0).identifier());
    }

    @Test
    void detect_reportsCorrectLineNumber() throws IOException {
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile,
                "# Overview\n" +
                "\n" +
                "Use SearchService to query.\n");  // line 3

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertEquals(1, issues.size());
        assertEquals(3, issues.get(0).line());
    }

    @Test
    void detect_reportsAllLinesForRepeatedIdentifier() throws IOException {
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile,
                "SearchService is the entry point.\n" +   // line 1
                "You can use SearchService for queries.\n"); // line 2

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertEquals(2, issues.size(), "Both lines should be reported");
        assertEquals(1, issues.get(0).line());
        assertEquals(2, issues.get(1).line());
    }

    @Test
    void detect_deduplicatesIdentifierWithinSameLine() throws IOException {
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        // "SearchService" appears twice on the same line
        Files.writeString(skillFile,
                "SearchService extends SearchService.Base\n");

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertEquals(1, issues.size(), "Duplicate on same line must only be reported once");
    }

    @Test
    void detect_ignoresNonCamelCaseText() throws IOException {
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile, "this has no camelCase identifiers at all\n");

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertTrue(issues.isEmpty());
    }

    @Test
    void detect_mixedFoundAndMissing() throws IOException {
        addCodeDocument("SearchIndex.java", "public class SearchIndex {}");
        index.close();
        index = SearchIndex.openReadOnly(tempDir.resolve("index"));

        Path skillFile = tempDir.resolve("skill.md");
        Files.writeString(skillFile,
                "Use SearchIndex (exists) and SearchService (missing).\n");

        DriftDetector detector = new DriftDetector();
        List<DriftDetector.DriftIssue> issues = detector.detect(skillFile, index);

        assertEquals(1, issues.size());
        assertEquals("SearchService", issues.get(0).identifier());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void addCodeDocument(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
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
