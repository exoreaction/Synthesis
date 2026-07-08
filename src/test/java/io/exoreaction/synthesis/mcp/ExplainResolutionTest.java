package io.exoreaction.synthesis.mcp;

import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #373A: MCP explain target resolution should mirror CLI behaviour.
 *
 * <p>When the target is a bare filename (e.g. {@code StagingCommand.java}) that does
 * not exist as a direct file or directory on disk, {@code handleExplain} should search
 * the index by filename — exactly like {@code ExplainCommand.resolveFilePath()} — and
 * resolve to file mode, rather than silently falling through to pattern mode.
 */
class ExplainResolutionTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveExplainTarget_bareFilename_inIndex_resolvesToFilePath() throws IOException {
        // File lives deep in the tree — user passes only "StagingCommand.java"
        Path deep = Files.createDirectories(tempDir.resolve("src/main/java/io/example"));
        Path file = Files.createFile(deep.resolve("StagingCommand.java"));

        SearchResult indexHit = new SearchResult(
                file,
                "src/main/java/io/example/StagingCommand.java",
                1.0f,
                "StagingCommand.java",
                "CODE", "Java", "", "", "", 1024
        );
        StubSearchIndex index = new StubSearchIndex(List.of(indexHit));

        // The MCP handler's resolveExplainTarget should find the file via the index
        Path resolved = SynthesisToolHandler.resolveExplainTarget(
                "StagingCommand.java", tempDir, index);

        assertNotNull(resolved, "Bare filename in index should resolve to a path");
        assertTrue(Files.isRegularFile(resolved),
                "Resolved path should be a regular file: " + resolved);
        assertEquals(file, resolved);
    }

    @Test
    void resolveExplainTarget_absolutePath_returnsDirectly() throws IOException {
        Path file = Files.createFile(tempDir.resolve("Direct.java"));
        StubSearchIndex index = new StubSearchIndex(List.of());

        Path resolved = SynthesisToolHandler.resolveExplainTarget(
                file.toString(), tempDir, index);

        assertEquals(file, resolved);
    }

    @Test
    void resolveExplainTarget_relativePath_resolves() throws IOException {
        Path subDir = Files.createDirectories(tempDir.resolve("src/main"));
        Path file = Files.createFile(subDir.resolve("Service.java"));
        StubSearchIndex index = new StubSearchIndex(List.of());

        Path resolved = SynthesisToolHandler.resolveExplainTarget(
                "src/main/Service.java", tempDir, index);

        assertEquals(file, resolved);
    }

    @Test
    void resolveExplainTarget_notFound_returnsNull() throws IOException {
        StubSearchIndex index = new StubSearchIndex(List.of());

        Path resolved = SynthesisToolHandler.resolveExplainTarget(
                "NoSuchFile.java", tempDir, index);

        assertNull(resolved, "Non-existent file not in index should return null (pattern mode)");
    }

    @Test
    void resolveExplainTarget_directory_returnsDirectly() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("src/main"));
        StubSearchIndex index = new StubSearchIndex(List.of());

        // Directories are handled by the caller before this method — but ensure
        // direct paths still resolve
        Path resolved = SynthesisToolHandler.resolveExplainTarget(
                dir.toString(), tempDir, index);

        assertEquals(dir, resolved);
    }

    @Test
    void resolveExplainTarget_multipleHits_prefersExactFilename() throws IOException {
        Path fileA = Files.createFile(tempDir.resolve("SomeOtherCommand.java"));
        Path fileB = Files.createDirectories(tempDir.resolve("deep")).resolve("StagingCommand.java");
        Files.createFile(fileB);

        SearchResult resultA = new SearchResult(
                fileA, "SomeOtherCommand.java", 0.9f, "SomeOtherCommand.java",
                "CODE", "Java", "", "", "", 100);
        SearchResult resultB = new SearchResult(
                fileB, "deep/StagingCommand.java", 0.8f, "StagingCommand.java",
                "CODE", "Java", "", "", "", 200);
        StubSearchIndex index = new StubSearchIndex(List.of(resultA, resultB));

        Path resolved = SynthesisToolHandler.resolveExplainTarget(
                "StagingCommand.java", tempDir, index);

        assertEquals(fileB, resolved,
                "Should prefer exact filename match over higher-scored non-match");
    }

    // ---------------------------------------------------------------------------
    // Stub SearchIndex
    // ---------------------------------------------------------------------------

    static class StubSearchIndex extends SearchIndex {
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
        public List<SearchResult> listAll(String fileTypeFilter, int maxResults) {
            return results;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
