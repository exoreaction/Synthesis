package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.core.FileMetadata;
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

    // -------------------------------------------------------------------
    // Real git integration (real subprocess, real repo)
    // -------------------------------------------------------------------

    @Test
    void nonGitWorkspace_returnsEmptyWithoutSpawningGit() throws Exception {
        // tempDir has no .git — guard must short-circuit before any subprocess call
        FileMetadata manifest = metadataFor(tempDir, "knowledge.yaml");

        List<String> result = KcpManifestChecks.findGitignoredManifests(tempDir, List.of(manifest));

        assertTrue(result.isEmpty(), "Non-git workspace must not be flagged");
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
}
