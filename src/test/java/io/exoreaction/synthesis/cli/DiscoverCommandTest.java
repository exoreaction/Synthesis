package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DiscoverCommand}, specifically the static discovery logic
 * ({@code findUnindexedGitRepos} and {@code estimateFileCount}).
 */
class DiscoverCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void findUnindexedGitRepos_returnsEmptyWhenNoSubDirs() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                Set.of()
        );

        assertTrue(result.isEmpty(), "Empty scan root → no results");
    }

    @Test
    void findUnindexedGitRepos_returnsEmptyWhenNoGitDirs() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));
        Files.createDirectories(scanRoot.resolve("plain-dir")); // no .git

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                Set.of()
        );

        assertTrue(result.isEmpty(), "Dirs without .git should be skipped");
    }

    @Test
    void findUnindexedGitRepos_findsUnindexedGitRepo() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));
        Path repo = Files.createDirectories(scanRoot.resolve("my-repo"));
        Files.createDirectories(repo.resolve(".git"));

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                Set.of()
        );

        assertEquals(1, result.size());
        assertEquals(repo.toAbsolutePath().normalize(), result.get(0));
    }

    @Test
    void findUnindexedGitRepos_excludesKnownWorkspaces() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));
        Path knownRepo = Files.createDirectories(scanRoot.resolve("known-repo"));
        Files.createDirectories(knownRepo.resolve(".git"));

        Set<Path> knownWorkspaces = Set.of(knownRepo.toAbsolutePath().normalize());
        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                knownWorkspaces
        );

        assertTrue(result.isEmpty(), "Repos already in knownWorkspaces must be excluded");
    }

    @Test
    void findUnindexedGitRepos_excludesAlreadyInitializedRepos() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));
        Path repo = Files.createDirectories(scanRoot.resolve("has-synthesis"));
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(repo.resolve(".synthesis")); // already init'd but not discovered

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                Set.of()
        );

        assertTrue(result.isEmpty(), "Repos with .synthesis/ must be excluded");
    }

    @Test
    void findUnindexedGitRepos_returnsMultipleRepos() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));
        for (String name : List.of("repo-a", "repo-b", "repo-c")) {
            Path repo = Files.createDirectories(scanRoot.resolve(name));
            Files.createDirectories(repo.resolve(".git"));
        }

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                Set.of()
        );

        assertEquals(3, result.size());
    }

    @Test
    void findUnindexedGitRepos_resultIsSorted() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));
        for (String name : List.of("repo-c", "repo-a", "repo-b")) {
            Files.createDirectories(scanRoot.resolve(name).resolve(".git"));
        }

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                Set.of()
        );

        assertEquals(3, result.size());
        assertTrue(result.get(0).getFileName().toString().compareTo(
                result.get(1).getFileName().toString()) < 0, "Result must be sorted");
        assertTrue(result.get(1).getFileName().toString().compareTo(
                result.get(2).getFileName().toString()) < 0, "Result must be sorted");
    }

    @Test
    void findUnindexedGitRepos_deduplicatesAcrossMultipleScanRoots() throws IOException {
        Path root1 = Files.createDirectories(tempDir.resolve("root1"));
        Path root2 = Files.createDirectories(tempDir.resolve("root2"));
        Path repo = Files.createDirectories(root1.resolve("shared-repo"));
        Files.createDirectories(repo.resolve(".git"));

        // root1 appears twice in the scan roots set (via LinkedHashSet)
        Set<Path> scanRoots = new LinkedHashSet<>();
        scanRoots.add(root1.toAbsolutePath().normalize());
        scanRoots.add(root2.toAbsolutePath().normalize());
        scanRoots.add(root1.toAbsolutePath().normalize()); // duplicate

        List<Path> result = DiscoverCommand.findUnindexedGitRepos(scanRoots, Set.of());

        assertEquals(1, result.size(), "Same repo must not appear twice");
    }

    @Test
    void findUnindexedGitRepos_mixedRepos() throws IOException {
        Path scanRoot = Files.createDirectories(tempDir.resolve("scan"));

        // Unindexed git repo
        Files.createDirectories(scanRoot.resolve("new-repo").resolve(".git"));

        // Already init'd (has .synthesis) — not a workspace we know about
        Path initd = Files.createDirectories(scanRoot.resolve("init-repo"));
        Files.createDirectories(initd.resolve(".git"));
        Files.createDirectories(initd.resolve(".synthesis"));

        // Known workspace
        Path known = Files.createDirectories(scanRoot.resolve("known-repo"));
        Files.createDirectories(known.resolve(".git"));

        // No git
        Files.createDirectories(scanRoot.resolve("no-git-dir"));

        Set<Path> knownWorkspaces = Set.of(known.toAbsolutePath().normalize());
        List<Path> result = DiscoverCommand.findUnindexedGitRepos(
                Set.of(scanRoot.toAbsolutePath().normalize()),
                knownWorkspaces
        );

        assertEquals(1, result.size(), "Only the plain unindexed git repo should appear");
        assertEquals(scanRoot.resolve("new-repo").toAbsolutePath().normalize(), result.get(0));
    }

    @Test
    void estimateFileCount_countsRegularFiles() throws IOException {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(repo.resolve("README.md"), "hello");
        Path src = Files.createDirectories(repo.resolve("src"));
        Files.writeString(src.resolve("Main.java"), "class Main{}");

        long count = DiscoverCommand.estimateFileCount(repo);

        assertEquals(2, count);
    }

    @Test
    void estimateFileCount_excludesGitDir() throws IOException {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(repo.resolve("README.md"), "hello");
        Path gitObjects = Files.createDirectories(repo.resolve(".git").resolve("objects"));
        Files.writeString(gitObjects.resolve("pack"), "binary"); // inside .git

        long count = DiscoverCommand.estimateFileCount(repo);

        assertEquals(1, count, "Files inside .git/ must not be counted");
    }

    @Test
    void estimateFileCount_returnsZeroOnIoError() {
        Path nonExistent = tempDir.resolve("does-not-exist");

        long count = DiscoverCommand.estimateFileCount(nonExistent);

        assertEquals(0, count);
    }
}
