package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the AnalyzeCommand (Smart Project Analysis).
 */
class AnalyzeCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void buildStatisticsShowsFileCount() {
        List<SearchResult> files = List.of(
                makeResult("src/Main.java", "CODE", "Java", 1000),
                makeResult("README.md", "MARKDOWN", null, 500),
                makeResult("config.yaml", "YAML", null, 200)
        );

        AnalyzeCommand cmd = new AnalyzeCommand();
        String stats = cmd.buildStatistics(files, tempDir);

        assertTrue(stats.contains("3"), "Should show total file count");
        assertTrue(stats.contains("Java"), "Should list Java language");
        assertTrue(stats.contains("CODE"), "Should list CODE type");
        assertTrue(stats.contains("MARKDOWN"), "Should list MARKDOWN type");
    }

    @Test
    void buildStatisticsShowsLargestFiles() {
        List<SearchResult> files = List.of(
                makeResult("small.txt", "OTHER", null, 100),
                makeResult("medium.java", "CODE", "Java", 5000),
                makeResult("large.md", "MARKDOWN", null, 50000)
        );

        AnalyzeCommand cmd = new AnalyzeCommand();
        String stats = cmd.buildStatistics(files, tempDir);

        assertTrue(stats.contains("large.md"), "Should list largest file");
        assertTrue(stats.contains("Largest files"), "Should have largest files section");
    }

    @Test
    void detectIssuesFindsLargeFiles() {
        List<SearchResult> files = List.of(
                makeResult("huge.java", "CODE", "Java", 200_000)
        );

        AnalyzeCommand cmd = new AnalyzeCommand();
        var issues = cmd.detectIssues(files, tempDir);

        assertTrue(issues.stream().anyMatch(i -> i.title().equals("Large file")),
                "Should detect large file");
    }

    @Test
    void detectIssuesFindsMissingReadme() {
        // Create 4 files in a directory without README
        List<SearchResult> files = new ArrayList<>();
        files.add(makeResult("src/A.java", "CODE", "Java", 1000));
        files.add(makeResult("src/B.java", "CODE", "Java", 1000));
        files.add(makeResult("src/C.java", "CODE", "Java", 1000));
        files.add(makeResult("src/D.java", "CODE", "Java", 1000));

        AnalyzeCommand cmd = new AnalyzeCommand();
        var issues = cmd.detectIssues(files, tempDir);

        assertTrue(issues.stream().anyMatch(i -> i.title().equals("Missing README")),
                "Should detect missing README for directory with 3+ files");
    }

    @Test
    void detectIssuesDoesNotFlagSmallDirectories() {
        // Only 1 file in directory -- should NOT flag missing README
        List<SearchResult> files = List.of(
                makeResult("docs/note.md", "MARKDOWN", null, 500)
        );

        AnalyzeCommand cmd = new AnalyzeCommand();
        var issues = cmd.detectIssues(files, tempDir);

        assertTrue(issues.stream().noneMatch(i -> i.title().equals("Missing README")),
                "Should not flag missing README for small directories");
    }

    @Test
    void detectIssuesFindsDeepNesting() {
        List<SearchResult> files = List.of(
                makeResult("a/b/c/d/e/f/g/h/i/deep.java", "CODE", "Java", 500)
        );

        AnalyzeCommand cmd = new AnalyzeCommand();
        var issues = cmd.detectIssues(files, tempDir);

        assertTrue(issues.stream().anyMatch(i -> i.title().equals("Deep nesting")),
                "Should detect deeply nested files");
    }

    @Test
    void detectIssuesDoNotFlagReadmePresent() {
        List<SearchResult> files = new ArrayList<>();
        files.add(makeResult("src/A.java", "CODE", "Java", 1000));
        files.add(makeResult("src/B.java", "CODE", "Java", 1000));
        files.add(makeResult("src/C.java", "CODE", "Java", 1000));
        files.add(makeResult("src/README.md", "MARKDOWN", null, 200));

        AnalyzeCommand cmd = new AnalyzeCommand();
        var issues = cmd.detectIssues(files, tempDir);

        assertTrue(issues.stream().noneMatch(i ->
                        i.title().equals("Missing README") && i.path().equals("src")),
                "Should not flag missing README when README.md exists");
    }

    @Test
    void buildStatisticsHandlesEmptyList() {
        AnalyzeCommand cmd = new AnalyzeCommand();
        String stats = cmd.buildStatistics(List.of(), tempDir);

        assertNotNull(stats, "Should handle empty list");
        assertTrue(stats.contains("0"), "Should show zero count");
    }

    @Test
    void detectIssuesHandlesEmptyList() {
        AnalyzeCommand cmd = new AnalyzeCommand();
        var issues = cmd.detectIssues(List.of(), tempDir);

        assertNotNull(issues, "Should handle empty list");
        assertTrue(issues.isEmpty(), "Should find no issues for empty list");
    }

    @Test
    void buildStatisticsShowsMaxDirectoryDepth() {
        List<SearchResult> files = List.of(
                makeResult("a/b/c/d.java", "CODE", "Java", 500),
                makeResult("x.md", "MARKDOWN", null, 100)
        );

        AnalyzeCommand cmd = new AnalyzeCommand();
        String stats = cmd.buildStatistics(files, tempDir);

        assertTrue(stats.contains("Max directory depth"), "Should show max depth");
    }

    // Helper method
    private SearchResult makeResult(String path, String type, String language, long size) {
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return new SearchResult(
                tempDir.resolve(path), path, 1.0f, fileName,
                type, language, "", "", "", size
        );
    }
}
