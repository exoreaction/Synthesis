package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ArchiveCommand} static helpers.
 */
class ArchiveCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // findArchiveDir
    // -------------------------------------------------------------------------

    @Test
    void findArchiveDir_findsArchiveDir() throws IOException {
        Files.createDirectory(workspace.resolve("archive"));
        Path result = ArchiveCommand.findArchiveDir(workspace);
        assertNotNull(result);
        assertEquals("archive", result.getFileName().toString());
    }

    @Test
    void findArchiveDir_findsAtSymbolArchiveDir() throws IOException {
        Files.createDirectory(workspace.resolve("@archive"));
        Path result = ArchiveCommand.findArchiveDir(workspace);
        assertNotNull(result);
        assertEquals("@archive", result.getFileName().toString());
    }

    @Test
    void findArchiveDir_returnsNullWhenMissing() throws IOException {
        // No archive directory at all
        Files.createDirectory(workspace.resolve("docs"));
        Path result = ArchiveCommand.findArchiveDir(workspace);
        assertNull(result);
    }

    // -------------------------------------------------------------------------
    // listAllFiles
    // -------------------------------------------------------------------------

    @Test
    void listAllFiles_returnsAllRegularFiles() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("root"));
        Files.writeString(root.resolve("a.txt"), "aaa");
        Files.writeString(root.resolve("b.txt"), "bbb");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("c.txt"), "ccc");

        List<Path> files = ArchiveCommand.listAllFiles(root);
        assertEquals(3, files.size());
    }

    @Test
    void listAllFiles_skipsHiddenFiles() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("root"));
        Files.writeString(root.resolve("visible.txt"), "data");
        Files.writeString(root.resolve(".hidden"), "secret");

        List<Path> files = ArchiveCommand.listAllFiles(root);
        assertEquals(1, files.size());
        assertEquals("visible.txt", files.get(0).getFileName().toString());
    }

    // -------------------------------------------------------------------------
    // hashFile
    // -------------------------------------------------------------------------

    @Test
    void hashFile_sameContent_sameHash() throws IOException {
        Path f1 = workspace.resolve("file1.txt");
        Path f2 = workspace.resolve("file2.txt");
        Files.writeString(f1, "identical content");
        Files.writeString(f2, "identical content");

        String hash1 = ArchiveCommand.hashFile(f1);
        String hash2 = ArchiveCommand.hashFile(f2);
        assertNotNull(hash1);
        assertEquals(hash1, hash2);
    }

    @Test
    void hashFile_differentContent_differentHash() throws IOException {
        Path f1 = workspace.resolve("file1.txt");
        Path f2 = workspace.resolve("file2.txt");
        Files.writeString(f1, "content A");
        Files.writeString(f2, "content B");

        String hash1 = ArchiveCommand.hashFile(f1);
        String hash2 = ArchiveCommand.hashFile(f2);
        assertNotNull(hash1);
        assertNotNull(hash2);
        assertNotEquals(hash1, hash2);
    }

    // -------------------------------------------------------------------------
    // findDuplicates
    // -------------------------------------------------------------------------

    @Test
    void findDuplicates_detectsDuplicates() throws IOException {
        Path f1 = workspace.resolve("a.txt");
        Path f2 = workspace.resolve("b.txt");
        Files.writeString(f1, "duplicate data");
        Files.writeString(f2, "duplicate data");

        List<Path> files = List.of(f1, f2);
        Map<String, List<Path>> byHash = ArchiveCommand.findDuplicates(files, 1024 * 1024);

        // Should have exactly 1 hash group with 2 paths
        long groupsWithDupes = byHash.values().stream().filter(v -> v.size() > 1).count();
        assertEquals(1, groupsWithDupes);

        List<Path> dupePaths = byHash.values().stream()
                .filter(v -> v.size() > 1)
                .findFirst().orElseThrow();
        assertEquals(2, dupePaths.size());
    }

    @Test
    void findDuplicates_noDuplicates_returnsEmpty() throws IOException {
        Path f1 = workspace.resolve("a.txt");
        Path f2 = workspace.resolve("b.txt");
        Files.writeString(f1, "unique content one");
        Files.writeString(f2, "unique content two");

        List<Path> files = List.of(f1, f2);
        Map<String, List<Path>> byHash = ArchiveCommand.findDuplicates(files, 1024 * 1024);

        // No groups should have size > 1
        long groupsWithDupes = byHash.values().stream().filter(v -> v.size() > 1).count();
        assertEquals(0, groupsWithDupes);
    }

    @Test
    void findDuplicates_skipsFilesAboveMaxBytes() throws IOException {
        Path small = workspace.resolve("small.txt");
        Path large = workspace.resolve("large.txt");
        Files.writeString(small, "tiny");
        // Write content that exceeds 10 bytes
        Files.writeString(large, "this is definitely more than ten bytes of content here");

        List<Path> files = List.of(small, large);
        // maxBytes = 10 — the large file should be skipped entirely
        Map<String, List<Path>> byHash = ArchiveCommand.findDuplicates(files, 10);

        // Only the small file should be hashed
        long totalPaths = byHash.values().stream().mapToInt(List::size).sum();
        assertEquals(1, totalPaths, "Only files under maxBytes should be hashed");
    }

    // -------------------------------------------------------------------------
    // findBuildArtifacts
    // -------------------------------------------------------------------------

    @Test
    void findBuildArtifacts_detectsNodeModules() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("project"));
        Files.createDirectories(root.resolve("node_modules/package"));

        List<Path> artifacts = ArchiveCommand.findBuildArtifacts(root);
        assertTrue(artifacts.stream()
                        .anyMatch(p -> p.getFileName().toString().equals("node_modules")),
                "node_modules should be detected as a build artifact");
    }

    @Test
    void findBuildArtifacts_detectsStrayClassFiles() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("project"));
        Path srcDir = Files.createDirectories(root.resolve("src/main"));
        Files.writeString(srcDir.resolve("Foo.class"), "bytecode");

        List<Path> artifacts = ArchiveCommand.findBuildArtifacts(root);
        assertTrue(artifacts.stream()
                        .anyMatch(p -> p.toString().contains("src/main")
                                || p.toString().contains("src" + java.io.File.separator + "main")),
                ".class files outside target/ should be detected");
    }

    @Test
    void findBuildArtifacts_ignoresClassFilesInTarget() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("project"));
        Path targetDir = Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(targetDir.resolve("Foo.class"), "bytecode");

        List<Path> artifacts = ArchiveCommand.findBuildArtifacts(root);
        // .class files inside target/ should NOT appear as stray artifacts
        assertTrue(artifacts.stream()
                        .noneMatch(p -> p.toString().contains("target")),
                ".class files inside target/ should be ignored");
    }

    // -------------------------------------------------------------------------
    // findLargeDirs
    // -------------------------------------------------------------------------

    @Test
    void findLargeDirs_returnsSortedBySize() throws IOException {
        Path archiveRoot = Files.createDirectory(workspace.resolve("archive"));

        // Create a small directory
        Path smallDir = Files.createDirectory(archiveRoot.resolve("small"));
        Files.writeString(smallDir.resolve("a.txt"), "tiny");

        // Create a larger directory
        Path largeDir = Files.createDirectory(archiveRoot.resolve("large"));
        Files.writeString(largeDir.resolve("b.txt"), "x".repeat(10_000));

        List<ArchiveCommand.DirSummary> dirs = ArchiveCommand.findLargeDirs(archiveRoot, workspace);

        assertEquals(2, dirs.size());
        // Largest first
        assertEquals("large", dirs.get(0).path().getFileName().toString());
        assertEquals("small", dirs.get(1).path().getFileName().toString());
        assertTrue(dirs.get(0).sizeBytes() > dirs.get(1).sizeBytes());
    }

    // -------------------------------------------------------------------------
    // DuplicateGroup record
    // -------------------------------------------------------------------------

    @Test
    void DuplicateGroup_savingsBytes() {
        // 3 copies of a 500-byte file → savings = 500 * (3 - 1) = 1000
        ArchiveCommand.DuplicateGroup group = new ArchiveCommand.DuplicateGroup(
                "abc123",
                List.of(Path.of("a.txt"), Path.of("b.txt"), Path.of("c.txt")),
                500L
        );
        assertEquals(1000L, group.savingsBytes());
    }
}
