package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownAnalyzerTest {

    @TempDir
    Path tempDir;

    private MarkdownAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new MarkdownAnalyzer();
    }

    @Test
    void canAnalyzeMarkdownFiles() {
        FileMetadata md = createMetadata("test.md");
        FileMetadata java = createMetadata("Test.java");

        assertTrue(analyzer.canAnalyze(md));
        assertFalse(analyzer.canAnalyze(java));
    }

    @Test
    void extractsHeadings() throws IOException {
        String content = """
                # Main Title

                Some content.

                ## Section One

                More content.

                ### Subsection

                ## Section Two
                """;
        Path file = writeFile("test.md", content);
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        assertEquals(4, result.headings().size());
        assertEquals("Main Title", result.headings().get(0));
        assertEquals("Section One", result.headings().get(1));
        assertEquals("Subsection", result.headings().get(2));
        assertEquals("Section Two", result.headings().get(3));
    }

    @Test
    void extractsLinks() throws IOException {
        String content = """
                # Test

                See [Google](https://google.com) and [GitHub](https://github.com).
                Also [local file](./README.md).
                """;
        Path file = writeFile("test.md", content);
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        assertEquals(3, result.links().size());
        assertTrue(result.links().contains("https://google.com"));
        assertTrue(result.links().contains("https://github.com"));
        assertTrue(result.links().contains("./README.md"));
    }

    @Test
    void countsWords() throws IOException {
        String content = """
                # Title

                This is a paragraph with several words in it.
                """;
        Path file = writeFile("test.md", content);
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        int wordCount = (int) result.metrics().get("wordCount");
        assertTrue(wordCount > 5, "Should count words correctly, got: " + wordCount);
    }

    @Test
    void detectsCodeBlocks() throws IOException {
        String content = """
                # Code Example

                ```java
                public class Main { }
                ```

                Some text.

                ```python
                def main():
                    pass
                ```
                """;
        Path file = writeFile("test.md", content);
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        assertEquals(2, result.metrics().get("codeBlockCount"));
    }

    @Test
    void buildsSummaryFromFirstHeading() throws IOException {
        String content = """
                # Knowledge Infrastructure Dashboard

                This is the dashboard content.
                """;
        Path file = writeFile("test.md", content);
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        assertEquals("Knowledge Infrastructure Dashboard", result.summary());
    }

    @Test
    void extractsKeywordsFromHeadingsAndBold() throws IOException {
        String content = """
                # Synthesis Architecture

                The **knowledge infrastructure** is important.
                """;
        Path file = writeFile("test.md", content);
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        assertTrue(result.keywords().contains("synthesis"));
        assertTrue(result.keywords().contains("architecture"));
        assertTrue(result.keywords().contains("knowledge infrastructure"));
    }

    @Test
    void handlesEmptyFile() throws IOException {
        Path file = writeFile("empty.md", "");
        FileMetadata metadata = createMetadata(file);

        AnalysisResult result = analyzer.analyze(metadata);

        assertTrue(result.headings().isEmpty());
        assertTrue(result.summary().isEmpty());
    }

    // Helper methods

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private FileMetadata createMetadata(String fileName) {
        return createMetadata(tempDir.resolve(fileName));
    }

    private FileMetadata createMetadata(Path file) {
        try {
            long size = Files.exists(file) ? Files.size(file) : 0;
            return FileMetadata.of(file, tempDir, size, Instant.now(), null);
        } catch (IOException e) {
            return FileMetadata.of(file, tempDir, 0, Instant.now(), null);
        }
    }
}
