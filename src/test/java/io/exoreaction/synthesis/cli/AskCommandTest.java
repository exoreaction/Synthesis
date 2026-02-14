package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the AskCommand (AI-powered Q&A).
 * Tests focus on context building since AI calls require real API keys.
 */
class AskCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void buildContextIncludesFilePathAndContent() throws IOException {
        // Create a test file
        Path testFile = tempDir.resolve("Main.java");
        Files.writeString(testFile, "public class Main {\n    public static void main(String[] args) {\n    }\n}");

        SearchResult result = new SearchResult(
                testFile, "Main.java", 1.0f, "Main.java",
                "CODE", "Java", "Main entry point", "", "", Files.size(testFile)
        );

        AskCommand cmd = new AskCommand();
        String context = cmd.buildContext(List.of(result), tempDir);

        assertTrue(context.contains("Main.java"), "Context should include file path");
        assertTrue(context.contains("Java"), "Context should include language");
        assertTrue(context.contains("public class Main"), "Context should include file content");
        assertTrue(context.contains("L1:"), "Context should include line numbers");
    }

    @Test
    void buildContextHandlesMultipleFiles() throws IOException {
        Path file1 = tempDir.resolve("A.java");
        Files.writeString(file1, "class A {}");

        Path file2 = tempDir.resolve("B.java");
        Files.writeString(file2, "class B {}");

        SearchResult r1 = new SearchResult(
                file1, "A.java", 2.0f, "A.java", "CODE", "Java", "Class A", "", "", 10
        );
        SearchResult r2 = new SearchResult(
                file2, "B.java", 1.5f, "B.java", "CODE", "Java", "Class B", "", "", 10
        );

        AskCommand cmd = new AskCommand();
        String context = cmd.buildContext(List.of(r1, r2), tempDir);

        assertTrue(context.contains("A.java"), "Should include first file");
        assertTrue(context.contains("B.java"), "Should include second file");
        assertTrue(context.contains("class A"), "Should include first file content");
        assertTrue(context.contains("class B"), "Should include second file content");
    }

    @Test
    void buildContextHandlesNonExistentFile() {
        SearchResult result = new SearchResult(
                tempDir.resolve("nonexistent.txt"), "nonexistent.txt", 1.0f,
                "nonexistent.txt", "OTHER", null, "Missing file", "", "", 0
        );

        AskCommand cmd = new AskCommand();
        String context = cmd.buildContext(List.of(result), tempDir);

        assertTrue(context.contains("nonexistent.txt"), "Should still include file path");
        assertTrue(context.contains("not readable"), "Should indicate file not readable");
    }

    @Test
    void buildContextIncludesSummary() throws IOException {
        Path testFile = tempDir.resolve("config.yaml");
        Files.writeString(testFile, "name: test\nversion: 1.0");

        SearchResult result = new SearchResult(
                testFile, "config.yaml", 1.0f, "config.yaml",
                "YAML", null, "Application configuration file", "", "", 25
        );

        AskCommand cmd = new AskCommand();
        String context = cmd.buildContext(List.of(result), tempDir);

        assertTrue(context.contains("Application configuration file"),
                "Context should include the file summary");
    }

    @Test
    void buildContextRespectsMaxBytesLimit() throws IOException {
        // Create a large file
        Path largeFile = tempDir.resolve("large.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            content.append("Line ").append(i).append(": ").append("x".repeat(100)).append("\n");
        }
        Files.writeString(largeFile, content.toString());

        SearchResult result = new SearchResult(
                largeFile, "large.txt", 1.0f, "large.txt",
                "OTHER", null, "Large file", "", "", Files.size(largeFile)
        );

        AskCommand cmd = new AskCommand();
        String context = cmd.buildContext(List.of(result), tempDir);

        // Context should be truncated (not full file content)
        assertTrue(context.length() < content.length(),
                "Context should be shorter than full file content");
    }

    @Test
    void buildContextHandlesEmptyResultList() {
        AskCommand cmd = new AskCommand();
        String context = cmd.buildContext(List.of(), tempDir);

        assertNotNull(context, "Context should not be null for empty list");
    }
}
