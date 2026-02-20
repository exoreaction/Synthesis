package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PruneCommand} static helpers.
 */
class PruneCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // buildProtectedPaths
    // -------------------------------------------------------------------------

    @Test
    void buildProtectedPaths_includesAllSubWorkspacePaths() {
        SynthesisConfig config = configWith("clients", "archive/2024");
        Set<String> protected_ = PruneCommand.buildProtectedPaths(workspace, config);
        assertTrue(protected_.contains("clients"));
        assertTrue(protected_.contains("archive/2024"));
    }

    @Test
    void buildProtectedPaths_emptyConfig_returnsEmptySet() {
        SynthesisConfig config = new SynthesisConfig();
        assertTrue(PruneCommand.buildProtectedPaths(workspace, config).isEmpty());
    }

    // -------------------------------------------------------------------------
    // isEmptyTree
    // -------------------------------------------------------------------------

    @Test
    void isEmptyTree_trueForDirWithNoFiles() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("empty"));
        assertTrue(PruneCommand.isEmptyTree(dir));
    }

    @Test
    void isEmptyTree_falseForDirWithFile() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("withfile"));
        Files.writeString(dir.resolve("README.md"), "placeholder");
        assertFalse(PruneCommand.isEmptyTree(dir));
    }

    @Test
    void isEmptyTree_trueForNestedEmptyDirs() throws IOException {
        Files.createDirectories(workspace.resolve("outer/inner/deep"));
        assertTrue(PruneCommand.isEmptyTree(workspace.resolve("outer")));
    }

    @Test
    void isEmptyTree_falseWhenDeepFileExists() throws IOException {
        Path deep = Files.createDirectories(workspace.resolve("outer/inner/deep"));
        Files.writeString(deep.resolve("note.txt"), "content");
        assertFalse(PruneCommand.isEmptyTree(workspace.resolve("outer")));
    }

    // -------------------------------------------------------------------------
    // findPruneable
    // -------------------------------------------------------------------------

    @Test
    void findPruneable_findsEmptyLeafDir() throws IOException {
        Files.createDirectories(workspace.resolve("empty-scaffolding"));
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertEquals(1, result.size());
    }

    @Test
    void findPruneable_findsNestedEmptyDirs() throws IOException {
        Files.createDirectories(workspace.resolve("outer/inner"));
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        // Both outer and inner should be found (both are empty trees)
        assertEquals(2, result.size());
    }

    @Test
    void findPruneable_sortedDeepestFirst() throws IOException {
        Files.createDirectories(workspace.resolve("outer/inner/deep"));
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        // deep should come before inner, inner before outer
        assertEquals(3, result.size());
        String first = result.get(0).toString();
        String last = result.get(result.size() - 1).toString();
        assertTrue(first.length() >= last.length(),
                "Deepest path should be first: " + first + " vs " + last);
    }

    @Test
    void findPruneable_skipsProtectedPaths() throws IOException {
        Files.createDirectories(workspace.resolve("protected-sw"));
        Set<String> protected_ = Set.of("protected-sw");
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, protected_);
        assertTrue(result.isEmpty());
    }

    @Test
    void findPruneable_skipsDotDirs() throws IOException {
        Files.createDirectories(workspace.resolve(".hidden-empty"));
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void findPruneable_skipsNonEmptyDirs() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("has-content"));
        Files.writeString(dir.resolve("file.txt"), "data");
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void findPruneable_dirWithReadme_isNotPruned() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("placeholder"));
        Files.writeString(dir.resolve("README.md"), "# placeholder");
        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertTrue(result.isEmpty(), "Dir with README.md should not be pruned");
    }

    @Test
    void findPruneable_emptyMixedWithNonEmpty() throws IOException {
        Path empty = Files.createDirectories(workspace.resolve("empty"));
        Path notEmpty = Files.createDirectories(workspace.resolve("filled"));
        Files.writeString(notEmpty.resolve("doc.txt"), "content");

        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertEquals(1, result.size());
        assertEquals(empty, result.get(0));
    }

    // -------------------------------------------------------------------------
    // pruneDirectories
    // -------------------------------------------------------------------------

    @Test
    void pruneDirectories_removesEmptyDirs() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("to-remove"));
        int removed = PruneCommand.pruneDirectories(List.of(dir));
        assertEquals(1, removed);
        assertFalse(Files.exists(dir));
    }

    @Test
    void pruneDirectories_removesDeepestFirst() throws IOException {
        Files.createDirectories(workspace.resolve("outer/inner"));
        Path outer = workspace.resolve("outer");
        Path inner = workspace.resolve("outer/inner");
        // Sort deepest first and prune
        List<Path> toRemove = List.of(inner, outer);
        int removed = PruneCommand.pruneDirectories(toRemove);
        assertEquals(2, removed);
        assertFalse(Files.exists(outer));
    }

    @Test
    void pruneDirectories_skipsAlreadyRemovedDirs() throws IOException {
        Path dir = workspace.resolve("already-gone");
        // Don't create it — delete non-existent dir should not throw
        int removed = PruneCommand.pruneDirectories(List.of(dir));
        assertEquals(0, removed);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SynthesisConfig configWith(String... paths) {
        SynthesisConfig config = new SynthesisConfig();
        List<SubWorkspaceConfig> subs = new ArrayList<>();
        for (String path : paths) {
            SubWorkspaceConfig sw = new SubWorkspaceConfig();
            sw.setName(path);
            sw.setPath(path);
            subs.add(sw);
        }
        config.setSubWorkspaces(subs);
        return config;
    }
}
