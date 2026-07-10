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
    void findPruneable_honorsExcludePatterns() throws IOException {
        // Issue #329 (remaining half): a subtree the user excluded from indexing
        // (scan.excludePatterns) must also be left alone by prune, even when it is
        // an empty tree. The dot-ancestor half is covered separately (PR #379).
        Files.createDirectories(workspace.resolve("build/tmp/cache"));

        List<Path> withoutExclude =
                PruneCommand.findPruneable(workspace, workspace, Set.of(), List.of());
        assertFalse(withoutExclude.isEmpty(),
                "Sanity: empty build/ subtree is pruneable when nothing excludes it");

        List<Path> withExclude =
                PruneCommand.findPruneable(workspace, workspace, Set.of(), List.of("build/**"));
        assertTrue(withExclude.isEmpty(),
                "Dirs under an excluded pattern must not be pruned, but got: " + withExclude);
    }

    @Test
    void findPruneable_honorsSynthesisIgnore_bareName() throws IOException {
        // Issue #420: a subtree excluded via .synthesisignore must be left alone by
        // prune, exactly like scan.excludePatterns. Bare names match at any depth.
        // The non-ignored parent (vendor/) must also be withheld: node_modules stays
        // on disk, so rmdir(vendor) is a guaranteed "Could not remove" failure.
        Files.createDirectories(workspace.resolve("vendor/node_modules/pkg"));
        Files.writeString(workspace.resolve(".synthesisignore"), "node_modules/\n");

        List<Path> result =
                PruneCommand.findPruneable(workspace, workspace, Set.of(), List.of());
        assertTrue(result.isEmpty(),
                "Neither the ignored subtree nor its parent chain may be pruned, but got: "
                + result);
    }

    @Test
    void findPruneable_honorsSynthesisIgnore_globPattern() throws IOException {
        Files.createDirectories(workspace.resolve("out/generated/stubs"));
        Files.writeString(workspace.resolve(".synthesisignore"), "# comment\nout/**\n");

        List<Path> result =
                PruneCommand.findPruneable(workspace, workspace, Set.of(), List.of());
        assertTrue(result.isEmpty(),
                "Dirs under a .synthesisignore glob must not be pruned, but got: " + result);
    }

    @Test
    void findPruneable_noSynthesisIgnoreFile_unchangedBehavior() throws IOException {
        Files.createDirectories(workspace.resolve("empty/leaf"));

        List<Path> result =
                PruneCommand.findPruneable(workspace, workspace, Set.of(), List.of());
        assertEquals(2, result.size(),
                "Without a .synthesisignore file, empty trees remain pruneable");
    }

    // -------------------------------------------------------------------------
    // countPreserved
    // -------------------------------------------------------------------------

    @Test
    void countPreserved_countsProtectedEmptyTrees() throws IOException {
        Files.createDirectories(workspace.resolve("clients"));

        long preserved = PruneCommand.countPreserved(
                workspace, workspace, Set.of("clients"), List.of());
        assertEquals(1, preserved);
    }

    @Test
    void findPruneable_withholdsParentsOfProtectedDirs() throws IOException {
        // A protected dir stays on disk, so rmdir on its parent is a guaranteed
        // failure — the parent must be withheld, not listed and then warned about.
        Files.createDirectories(workspace.resolve("area/clients"));

        List<Path> result = PruneCommand.findPruneable(
                workspace, workspace, Set.of("area/clients"), List.of());
        assertTrue(result.isEmpty(),
                "Parents of protected dirs must not be pruned, but got: " + result);
    }

    @Test
    void countPreserved_countsPatternExcludedEmptyTrees() throws IOException {
        // Issue #419: empty trees withheld by scan.excludePatterns previously appeared
        // in neither the removal list nor the preserved count — they silently vanished.
        Files.createDirectories(workspace.resolve("build/tmp"));

        long withoutExclude = PruneCommand.countPreserved(
                workspace, workspace, Set.of(), List.of());
        assertEquals(0, withoutExclude,
                "Sanity: nothing is preserved when nothing protects or excludes");

        long withExclude = PruneCommand.countPreserved(
                workspace, workspace, Set.of(), List.of("build/**"));
        assertEquals(2, withExclude,
                "Empty trees withheld by an exclude pattern must be counted as preserved");
    }

    @Test
    void countPreserved_countsSynthesisIgnoredEmptyTrees() throws IOException {
        Files.createDirectories(workspace.resolve("vendor/node_modules"));
        Files.writeString(workspace.resolve(".synthesisignore"), "node_modules/\n");

        long preserved = PruneCommand.countPreserved(
                workspace, workspace, Set.of(), List.of());
        assertEquals(2, preserved,
                "Both the ignored empty tree and its withheld parent must be counted as preserved");
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
    void findPruneable_symlinkToDirectory_isNotPruned() throws IOException {
        // Regression: symlinks-to-directories at the workspace root were silently
        // deleted because Files.isDirectory() follows links (returns true) and
        // isEmptyTree() with no FOLLOW_LINKS visits only the symlink itself
        // (no regular files found → "empty" → prune). Bug report: Pål, 2026-05-22.
        Path target = Files.createDirectories(workspace.resolve("../external-target").normalize());
        Path link = workspace.resolve("devdata");
        Files.createSymbolicLink(link, target);

        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertFalse(result.contains(link), "Symlink to directory must never be pruned");
    }

    @Test
    void isEmptyTree_symlinkToDir_returnsFalse() throws IOException {
        // A symlink to a directory must not be treated as an empty tree — it is
        // user-managed infrastructure that prune has no business deleting.
        Path target = Files.createDirectories(workspace.resolve("../link-target").normalize());
        Path link = workspace.resolve("mylink");
        Files.createSymbolicLink(link, target);

        assertFalse(PruneCommand.isEmptyTree(link),
                "Symlink to directory should not be reported as an empty tree");
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
    // Issue #329: dot-ancestor paths must be excluded
    // -------------------------------------------------------------------------

    @Test
    void findPruneable_skipsNestedDotDirSubtrees() throws IOException {
        // Repro from issue #329: .claude/worktrees/agent-x/.claude/worktrees/agent-y
        // The leaf "agent-y" does NOT start with '.', but it lives under a dotdir
        // ancestor. findPruneable must skip it entirely.
        Files.createDirectories(workspace.resolve(
                ".claude/worktrees/agent-x/.claude/worktrees/agent-y"));

        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());

        // None of these should appear: worktrees, agent-x, agent-y, etc.
        for (Path p : result) {
            String rel = workspace.relativize(p).toString();
            assertFalse(rel.startsWith("."),
                    "Dotdir subtree path should not be pruneable: " + rel);
            assertFalse(rel.contains("/."),
                    "Path with dotdir ancestor should not be pruneable: " + rel);
        }
        assertTrue(result.isEmpty(),
                "No directories should be pruneable when all are under dotdir ancestors");
    }

    @Test
    void findPruneable_skipsNonLeafDotdirChildren() throws IOException {
        // A regular dir name nested under a dotdir ancestor should not be pruneable.
        // e.g., .hidden/visible/deep — "visible" and "deep" don't start with '.' but
        // .hidden is a dotdir ancestor.
        Files.createDirectories(workspace.resolve(".hidden/visible/deep"));

        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertTrue(result.isEmpty(),
                "Children of dotdirs should not be pruneable even if their own name is not dotted");
    }

    @Test
    void findPruneable_normalDirNextToDotdirIsStillPruned() throws IOException {
        // A normal empty dir that is a sibling of a dotdir should still be prunable.
        Files.createDirectories(workspace.resolve(".hidden/stuff"));
        Files.createDirectories(workspace.resolve("normal-empty"));

        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertEquals(1, result.size(), "Only the normal dir should be pruneable");
        assertEquals("normal-empty",
                workspace.relativize(result.get(0)).toString());
    }

    @Test
    void findPruneable_dirContainingOnlyEmptyDotdirsIsNotPruneable() throws IOException {
        // Issue #329 secondary case: a regular dir that contains ONLY dotdir
        // children. isEmptyTree sees "no regular files" and marks it empty, but
        // rmdir fails because the dotdir children are still there.
        // The parent "scaffold" must NOT appear in pruneable results because
        // it is not truly empty on disk.
        Path scaffold = Files.createDirectories(workspace.resolve("scaffold"));
        Files.createDirectories(scaffold.resolve(".claude"));
        Files.createDirectories(scaffold.resolve(".git"));

        List<Path> result = PruneCommand.findPruneable(workspace, workspace, Set.of());
        assertFalse(result.contains(scaffold),
                "Dir containing only dotdir children should not be pruneable (rmdir would fail)");
    }

    @Test
    void isEmptyTree_falseWhenOnlyDotdirChildrenExist() throws IOException {
        // A dir that contains only dotdirs is NOT empty from rmdir's perspective.
        // isEmptyTree should return false in this case.
        Path dir = Files.createDirectories(workspace.resolve("has-dotdirs"));
        Files.createDirectories(dir.resolve(".claude"));
        Files.createDirectories(dir.resolve(".git"));

        assertFalse(PruneCommand.isEmptyTree(dir),
                "Dir containing only dotdir children is not empty (rmdir would fail)");
    }

    @Test
    void isEmptyTree_falseWhenNestedDotdirExists() throws IOException {
        // Even a deeply nested dotdir child makes the parent not truly empty.
        Path dir = Files.createDirectories(workspace.resolve("parent"));
        Files.createDirectories(dir.resolve("child/.hidden-deep"));

        assertFalse(PruneCommand.isEmptyTree(dir),
                "Dir with nested dotdir descendants is not truly empty for rmdir");
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
