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
 * Tests for {@link ExplainCommand}, specifically the file-resolution logic
 * that allows users to reference files by bare filename rather than full path.
 */
class ExplainCommandTest {

    @TempDir
    Path tempDir;

    // --- resolveFilePath ---

    @Test
    void resolveFilePath_absolutePath_returnsDirectly() throws IOException {
        Path file = Files.createFile(tempDir.resolve("Foo.java"));

        ExplainCommand cmd = new ExplainCommand();
        // Absolute path that exists: resolved immediately, no index needed
        Path result = cmd.resolveFilePath(file, tempDir, null);

        assertEquals(file, result);
    }

    @Test
    void resolveFilePath_workspaceRelative_resolves() throws IOException {
        Path subDir = Files.createDirectories(tempDir.resolve("src/main"));
        Path file = Files.createFile(subDir.resolve("Service.java"));

        ExplainCommand cmd = new ExplainCommand();
        Path result = cmd.resolveFilePath(Path.of("src/main/Service.java"), tempDir, null);

        assertEquals(file, result);
    }

    @Test
    void resolveFilePath_nonExistent_returnsNull() {
        ExplainCommand cmd = new ExplainCommand();
        // No index (null) and path doesn't exist on disk
        Path result = cmd.resolveFilePath(Path.of("NoSuchFile.java"), tempDir, null);

        assertNull(result);
    }

    @Test
    void resolveFilePath_basenameFoundInIndex_returnsResolvedPath() throws IOException {
        // File lives deep in the tree — user passes only "StagingCommand.java"
        Path deep = Files.createDirectories(tempDir.resolve("src/main/java/io/example"));
        Path file = Files.createFile(deep.resolve("StagingCommand.java"));

        // Build a stub index that returns one SearchResult matching the basename
        SearchResult result = new SearchResult(
                file,
                "src/main/java/io/example/StagingCommand.java",
                1.0f,
                "StagingCommand.java",
                "CODE", "Java", "", "", "", 1024
        );
        StubSearchIndex index = new StubSearchIndex(List.of(result));

        ExplainCommand cmd = new ExplainCommand();
        Path resolved = cmd.resolveFilePath(Path.of("StagingCommand.java"), tempDir, index);

        assertEquals(file, resolved);
    }

    @Test
    void resolveFilePath_basenameNotInIndex_returnsNull() throws IOException {
        StubSearchIndex index = new StubSearchIndex(List.of());

        ExplainCommand cmd = new ExplainCommand();
        Path resolved = cmd.resolveFilePath(Path.of("Unknown.java"), tempDir, index);

        assertNull(resolved);
    }

    @Test
    void resolveFilePath_multipleIndexHits_prefersExactFilenameMatch() throws IOException {
        // Two files returned from index — only the second is an exact filename match
        Path fileA = Files.createFile(tempDir.resolve("SomeOtherCommand.java"));
        Path fileB = Files.createFile(tempDir.resolve("StagingCommand.java"));

        SearchResult resultA = new SearchResult(
                fileA, "SomeOtherCommand.java", 0.9f, "SomeOtherCommand.java",
                "CODE", "Java", "", "", "", 100);
        SearchResult resultB = new SearchResult(
                fileB, "StagingCommand.java", 0.8f, "StagingCommand.java",
                "CODE", "Java", "", "", "", 200);
        // resultA scores higher but doesn't match the filename
        StubSearchIndex index = new StubSearchIndex(List.of(resultA, resultB));

        ExplainCommand cmd = new ExplainCommand();
        Path resolved = cmd.resolveFilePath(Path.of("StagingCommand.java"), tempDir, index);

        assertEquals(fileB, resolved);
    }

    @Test
    void resolveFilePath_ambiguousBareFilename_warnsOnStderr() throws IOException {
        // Two files share the bare filename (#448) — resolution should still pick one
        // (same behavior as before) but now warn on stderr about the other candidate.
        Path dirA = Files.createDirectories(tempDir.resolve("graph"));
        Path dirB = Files.createDirectories(tempDir.resolve("cli"));
        Path fileA = Files.createFile(dirA.resolve("ProbeMarker.java"));
        Path fileB = Files.createFile(dirB.resolve("ProbeMarker.java"));

        SearchResult resultA = new SearchResult(
                fileA, "graph/ProbeMarker.java", 0.9f, "ProbeMarker.java",
                "CODE", "Java", "", "", "", 100);
        SearchResult resultB = new SearchResult(
                fileB, "cli/ProbeMarker.java", 0.8f, "ProbeMarker.java",
                "CODE", "Java", "", "", "", 200);
        StubSearchIndex index = new StubSearchIndex(List.of(resultA, resultB));

        java.io.ByteArrayOutputStream errCapture = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(errCapture));
        Path resolved;
        try {
            ExplainCommand cmd = new ExplainCommand();
            resolved = cmd.resolveFilePath(Path.of("ProbeMarker.java"), tempDir, index);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(fileA, resolved, "Resolution behavior must not change — first candidate wins");
        String stderr = errCapture.toString();
        assertTrue(stderr.contains("also named"),
                "Ambiguous bare filename should warn on stderr, got: " + stderr);
        assertTrue(stderr.contains("cli/ProbeMarker.java"),
                "Warning should list the other candidate, got: " + stderr);
    }

    @Test
    void resolveFilePath_unambiguousBareFilename_noWarning() throws IOException {
        Path deep = Files.createDirectories(tempDir.resolve("src/main/java/io/example"));
        Path file = Files.createFile(deep.resolve("StagingCommand.java"));

        SearchResult result = new SearchResult(
                file, "src/main/java/io/example/StagingCommand.java", 1.0f,
                "StagingCommand.java", "CODE", "Java", "", "", "", 1024);
        StubSearchIndex index = new StubSearchIndex(List.of(result));

        java.io.ByteArrayOutputStream errCapture = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(errCapture));
        Path resolved;
        try {
            ExplainCommand cmd = new ExplainCommand();
            resolved = cmd.resolveFilePath(Path.of("StagingCommand.java"), tempDir, index);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(file, resolved);
        assertEquals("", errCapture.toString(), "Unambiguous resolution must not warn");
    }

    // ---------------------------------------------------------------------------
    // Stub SearchIndex — avoids spinning up a real Lucene directory
    // ---------------------------------------------------------------------------

    /**
     * Minimal SearchIndex stand-in that returns a canned result list.
     * Extends SearchIndex with a non-existent path so the Lucene directory
     * is never opened; only {@code search()} is overridden.
     */
    static class StubSearchIndex extends io.exoreaction.synthesis.index.SearchIndex {

        private final List<SearchResult> results;

        StubSearchIndex(List<SearchResult> results) throws IOException {
            super(Files.createTempDirectory("stub-index"));
            this.results = results;
        }

        @Override
        public List<SearchResult> search(String query, int maxResults) {
            return results;
        }

        @Override
        public void close() {
            // no-op — nothing to close in the stub
        }
    }
}
