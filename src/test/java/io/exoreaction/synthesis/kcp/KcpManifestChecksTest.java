package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpManifestChecks} (issue #309): detecting KCP manifests
 * that are indexed locally but gitignored, so they never reach the remote.
 */
class KcpManifestChecksTest {

    @TempDir
    Path tempDir;

    private FileMetadata metadataFor(Path root, String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "id: test\nunits: []\n");
        return FileMetadata.of(file, root, Files.size(file), Instant.now(), null);
    }

    private void gitInit(Path root) throws Exception {
        run(root, "git", "init", "-q");
        run(root, "git", "config", "user.email", "test@example.com");
        run(root, "git", "config", "user.name", "Test");
    }

    private void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    // -------------------------------------------------------------------
    // Pure filtering logic (injected predicate, no real git subprocess)
    // -------------------------------------------------------------------

    @Test
    void filtersToKnowledgeYamlOnly() throws Exception {
        FileMetadata manifest = metadataFor(tempDir, "knowledge.yaml");
        FileMetadata other = metadataFor(tempDir, "README.md");

        List<String> result = KcpManifestChecks.findGitignoredManifests(
                tempDir, List.of(manifest, other), path -> true);

        assertEquals(List.of("knowledge.yaml"), result);
    }

    @Test
    void nestedKnowledgeYamlPathMatches() throws Exception {
        FileMetadata manifest = metadataFor(tempDir, "sub/knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(
                tempDir, List.of(manifest), path -> true);

        assertEquals(List.of("sub/knowledge.yaml"), result);
    }

    @Test
    void respectsPredicateFalse() throws Exception {
        FileMetadata manifest = metadataFor(tempDir, "knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(
                tempDir, List.of(manifest), path -> false);

        assertTrue(result.isEmpty(), "Predicate returning false should exclude the manifest");
    }

    @Test
    void emptyScanResult_returnsEmpty() {
        List<String> result = KcpManifestChecks.findGitignoredManifests(
                tempDir, List.of(), path -> true);
        assertTrue(result.isEmpty());
    }

    @Test
    void windowsStyleRelativePath_matchesByFileName() throws Exception {
        // Simulates what Path.relativize().toString() produces on Windows (backslash-separated),
        // paired with the fileName Path.getFileName() would actually yield there (separator-agnostic).
        Path dummy = tempDir.resolve("knowledge.yaml");
        Files.writeString(dummy, "id: test\nunits: []\n");
        FileMetadata manifest = new FileMetadata(
                dummy, "sub\\knowledge.yaml", "knowledge.yaml", ".yaml",
                FileUtils.FileType.YAML, null, Files.size(dummy), Instant.now(), null);

        List<String> result = KcpManifestChecks.findGitignoredManifests(
                tempDir, List.of(manifest), path -> true);

        assertEquals(List.of("sub\\knowledge.yaml"), result,
                "Should match by fileName regardless of relativePath's separator style");
    }

    // -------------------------------------------------------------------
    // Real git integration (real subprocess, real repo)
    // -------------------------------------------------------------------

    @Test
    void nonGitWorkspace_returnsEmpty() throws Exception {
        // tempDir has no .git — guard correctly identifies non-repo via non-zero
        // `git rev-parse --is-inside-work-tree` exit and returns empty.
        FileMetadata manifest = metadataFor(tempDir, "knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(tempDir, List.of(manifest));

        assertTrue(result.isEmpty(), "Non-git workspace must not be flagged");
    }

    @Test
    void linkedWorktree_gitignoredManifest_isFlagged() throws Exception {
        // In a linked git worktree, .git is a FILE (gitdir: pointer), not a directory
        Path mainRepo = tempDir.resolve("main");
        Files.createDirectories(mainRepo);
        gitInit(mainRepo);
        Files.writeString(mainRepo.resolve("README.md"), "x");
        run(mainRepo, "git", "add", "README.md");
        run(mainRepo, "git", "commit", "-q", "-m", "init");

        Path worktree = tempDir.resolve("wt");
        run(mainRepo, "git", "worktree", "add", worktree.toString());
        assertTrue(Files.isRegularFile(worktree.resolve(".git")),
                "Precondition: .git must be a file (not a directory) in a linked worktree");

        Files.writeString(worktree.resolve(".gitignore"), "knowledge.yaml\n");
        FileMetadata manifest = metadataFor(worktree, "knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(worktree, List.of(manifest));

        assertEquals(List.of("knowledge.yaml"), result,
                "A linked git worktree is a real repo and must be checked, not skipped");
    }

    @Test
    void nonExistentWorkspaceRoot_returnsEmptyWithoutThrowing() {
        // Pins the fail-safe contract: any failure to run git (missing binary, bad
        // directory, etc.) must be swallowed, never propagated -- this is what
        // prevents a `maintain --quiet` cron run from being reported as ERROR.
        Path missing = tempDir.resolve("does-not-exist");
        FileMetadata manifest = new FileMetadata(
                missing.resolve("knowledge.yaml"), "knowledge.yaml", "knowledge.yaml", ".yaml",
                FileUtils.FileType.YAML, null, 10L, Instant.now(), null);

        List<String> result = assertDoesNotThrow(
                () -> KcpManifestChecks.findGitignoredManifests(missing, List.of(manifest)));

        assertTrue(result.isEmpty());
    }

    @Test
    void realGitRepo_trackedManifest_notFlagged() throws Exception {
        gitInit(tempDir);
        FileMetadata manifest = metadataFor(tempDir, "knowledge.yaml");
        run(tempDir, "git", "add", "knowledge.yaml");
        run(tempDir, "git", "commit", "-q", "-m", "add manifest");

        List<String> result = KcpManifestChecks.findGitignoredManifests(tempDir, List.of(manifest));

        assertTrue(result.isEmpty(), "A tracked knowledge.yaml should not be flagged");
    }

    @Test
    void realGitRepo_gitignoredManifest_isFlagged() throws Exception {
        gitInit(tempDir);
        Files.writeString(tempDir.resolve(".gitignore"), "knowledge.yaml\n");
        FileMetadata manifest = metadataFor(tempDir, "knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(tempDir, List.of(manifest));

        assertEquals(List.of("knowledge.yaml"), result);
    }

    @Test
    void realGitRepo_dashPrefixedDirectory_isFlagged() throws Exception {
        // A top-level directory named starting with "-" must not be misparsed as a git option
        gitInit(tempDir);
        Files.writeString(tempDir.resolve(".gitignore"), "-staging/knowledge.yaml\n");
        FileMetadata manifest = metadataFor(tempDir, "-staging/knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(tempDir, List.of(manifest));

        assertEquals(List.of("-staging/knowledge.yaml"), result,
                "A dash-prefixed directory name should not break git check-ignore parsing");
    }

    // -------------------------------------------------------------------
    // Warning message
    // -------------------------------------------------------------------

    @Test
    void warningFor_includesRemedyText() {
        String warning = KcpManifestChecks.warningFor("knowledge.yaml");

        assertTrue(warning.contains("knowledge.yaml"), "Should mention the path");
        assertTrue(warning.contains(".gitignore"), "Should mention .gitignore");
        assertTrue(warning.contains("git add -f knowledge.yaml"), "Should give the exact remedy command");
    }

    // -------------------------------------------------------------------
    // printWarnings (shared print helper, replaces duplicated blocks in
    // ScanCommand/MaintainCommand)
    // -------------------------------------------------------------------

    @Test
    void printWarnings_emptyList_printsNothing() {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream saved = System.out;
        System.setOut(new java.io.PrintStream(captured));
        try {
            KcpManifestChecks.printWarnings(List.of());
        } finally {
            System.setOut(saved);
        }
        assertEquals("", captured.toString());
    }

    @Test
    void printWarnings_nonEmptyList_printsHeaderAndEachWarning() {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream saved = System.out;
        System.setOut(new java.io.PrintStream(captured));
        try {
            KcpManifestChecks.printWarnings(List.of("knowledge.yaml", "sub/knowledge.yaml"));
        } finally {
            System.setOut(saved);
        }
        String out = captured.toString();
        assertTrue(out.contains("Manifest coverage issues:"), "Should print the section header");
        assertTrue(out.contains("knowledge.yaml"), "Should print first path's warning");
        assertTrue(out.contains("sub/knowledge.yaml"), "Should print second path's warning");
    }
}
