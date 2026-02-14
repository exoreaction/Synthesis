package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enhanced export formats (architecture-doc, onboarding-guide).
 */
class ExportCommandEnhancedTest {

    @TempDir
    Path tempDir;

    @Test
    void buildFileIndexIncludesAllFileInfo() {
        List<SearchResult> results = List.of(
                makeResult("src/Main.java", "CODE", "Java", 2500, "Application entry point"),
                makeResult("README.md", "MARKDOWN", null, 1000, "Project overview"),
                makeResult("config.yaml", "YAML", null, 300, "Configuration file")
        );

        ExportCommand cmd = new ExportCommand();
        String index = cmd.buildFileIndex(results);

        assertTrue(index.contains("src/Main.java"), "Should include file path");
        assertTrue(index.contains("[CODE]"), "Should include file type");
        assertTrue(index.contains("(Java)"), "Should include language");
        assertTrue(index.contains("Application entry point"), "Should include summary");
        assertTrue(index.contains("README.md"), "Should include all files");
        assertTrue(index.contains("config.yaml"), "Should include all files");
    }

    @Test
    void buildFileIndexHandlesNullLanguage() {
        List<SearchResult> results = List.of(
                makeResult("notes.txt", "OTHER", null, 500, "Plain text notes")
        );

        ExportCommand cmd = new ExportCommand();
        String index = cmd.buildFileIndex(results);

        assertTrue(index.contains("notes.txt"), "Should include file path");
        assertFalse(index.contains("(null)"), "Should not include null language");
    }

    @Test
    void buildFileIndexHandlesEmptySummary() {
        List<SearchResult> results = List.of(
                makeResult("empty.txt", "OTHER", null, 100, "")
        );

        ExportCommand cmd = new ExportCommand();
        String index = cmd.buildFileIndex(results);

        assertTrue(index.contains("empty.txt"), "Should include file path");
    }

    @Test
    void buildFileIndexHandlesEmptyList() {
        ExportCommand cmd = new ExportCommand();
        String index = cmd.buildFileIndex(List.of());

        assertNotNull(index);
        assertTrue(index.isEmpty(), "Should return empty string for no files");
    }

    @Test
    void buildFileIndexTruncatesLongSummaries() {
        String longSummary = "x".repeat(200);
        List<SearchResult> results = List.of(
                makeResult("file.txt", "OTHER", null, 100, longSummary)
        );

        ExportCommand cmd = new ExportCommand();
        String index = cmd.buildFileIndex(results);

        // Summary should be truncated to ~80 chars
        assertTrue(index.length() < 300, "Should truncate long summaries");
    }

    @Test
    void buildFileIndexShowsFileSize() {
        List<SearchResult> results = List.of(
                makeResult("big.java", "CODE", "Java", 1_500_000, "Large file")
        );

        ExportCommand cmd = new ExportCommand();
        String index = cmd.buildFileIndex(results);

        // Should show formatted size (1.4 MB or similar)
        assertTrue(index.contains("MB") || index.contains("KB"),
                "Should include formatted file size");
    }

    // Helper method
    private SearchResult makeResult(String path, String type, String language,
                                     long size, String summary) {
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return new SearchResult(
                tempDir.resolve(path), path, 1.0f, fileName,
                type, language, summary, "", "", size
        );
    }
}
