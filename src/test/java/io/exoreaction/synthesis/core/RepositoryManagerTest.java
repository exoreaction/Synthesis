package io.exoreaction.synthesis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RepositoryManager (multi-repo workspace support).
 */
class RepositoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void addRepositoryCreatesEntry() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        boolean added = manager.addRepository(repo, "project-a");

        assertTrue(added);
        assertEquals(1, manager.getRepositories().size());
        assertEquals("project-a", manager.getRepositories().get(0).name());
    }

    @Test
    void addRepositoryRejectsDuplicate() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        assertTrue(manager.addRepository(repo, "project-a"));
        assertFalse(manager.addRepository(repo, "project-a"));
        assertEquals(1, manager.getRepositories().size());
    }

    @Test
    void addRepositoryRejectsNonExistentPath() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        RepositoryManager manager = new RepositoryManager(tempDir);

        assertThrows(IllegalArgumentException.class, () -> manager.addRepository(nonExistent, null));
    }

    @Test
    void addRepositoryDerivesNameFromDirectory() throws IOException {
        Path repo = tempDir.resolve("my-cool-project");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo, null);

        assertEquals("my-cool-project", manager.getRepositories().get(0).name());
    }

    @Test
    void addRepositoryEnsuresUniqueName() throws IOException {
        Path repo1 = tempDir.resolve("proj");
        Path repo2 = tempDir.resolve("subdir");
        Files.createDirectories(repo1);
        Files.createDirectories(repo2);

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo1, "project");
        manager.addRepository(repo2, "project");

        assertEquals(2, manager.getRepositories().size());
        assertEquals("project", manager.getRepositories().get(0).name());
        assertEquals("project-2", manager.getRepositories().get(1).name());
    }

    @Test
    void removeRepository() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo, "project-a");
        assertTrue(manager.removeRepository("project-a"));
        assertEquals(0, manager.getRepositories().size());
    }

    @Test
    void removeNonExistentRepository() {
        RepositoryManager manager = new RepositoryManager(tempDir);
        assertFalse(manager.removeRepository("nothing"));
    }

    @Test
    void findByName() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo, "Project-A");

        assertTrue(manager.findByName("project-a").isPresent());
        assertTrue(manager.findByName("PROJECT-A").isPresent());
        assertFalse(manager.findByName("nonexistent").isPresent());
    }

    @Test
    void isMultiRepo() throws IOException {
        Path repo1 = tempDir.resolve("project-a");
        Path repo2 = tempDir.resolve("project-b");
        Files.createDirectories(repo1);
        Files.createDirectories(repo2);

        RepositoryManager manager = new RepositoryManager(tempDir);
        assertFalse(manager.isMultiRepo());

        manager.addRepository(repo1, "a");
        assertFalse(manager.isMultiRepo());

        manager.addRepository(repo2, "b");
        assertTrue(manager.isMultiRepo());
    }

    @Test
    void hasRepos() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        assertFalse(manager.hasRepos());

        manager.addRepository(repo, "a");
        assertTrue(manager.hasRepos());
    }

    @Test
    void getRepoNames() throws IOException {
        Path repo1 = tempDir.resolve("project-a");
        Path repo2 = tempDir.resolve("project-b");
        Files.createDirectories(repo1);
        Files.createDirectories(repo2);

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo1, "alpha");
        manager.addRepository(repo2, "beta");

        assertEquals(2, manager.getRepoNames().size());
        assertTrue(manager.getRepoNames().contains("alpha"));
        assertTrue(manager.getRepoNames().contains("beta"));
    }

    @Test
    void updateScanTime() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo, "project-a");
        assertNull(manager.getRepositories().get(0).lastScanTime());

        Instant now = Instant.now();
        manager.updateScanTime("project-a", now);
        assertEquals(now, manager.getRepositories().get(0).lastScanTime());
    }

    @Test
    void saveAndLoad() throws IOException {
        Path repo1 = tempDir.resolve("project-a");
        Path repo2 = tempDir.resolve("project-b");
        Files.createDirectories(repo1);
        Files.createDirectories(repo2);
        // Create .synthesis dir
        Files.createDirectories(tempDir.resolve(".synthesis"));

        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.addRepository(repo1, "alpha");
        manager.addRepository(repo2, "beta");
        Instant scanTime = Instant.parse("2026-02-14T10:00:00Z");
        manager.updateScanTime("alpha", scanTime);
        manager.save();

        // Load into new manager
        RepositoryManager loaded = new RepositoryManager(tempDir);
        loaded.load();

        assertEquals(2, loaded.getRepositories().size());
        assertEquals("alpha", loaded.getRepositories().get(0).name());
        assertEquals("beta", loaded.getRepositories().get(1).name());
        assertEquals(scanTime, loaded.getRepositories().get(0).lastScanTime());
        assertNull(loaded.getRepositories().get(1).lastScanTime());
    }

    @Test
    void loadFromEmptyWorkspace() throws IOException {
        RepositoryManager manager = new RepositoryManager(tempDir);
        manager.load(); // Should not throw
        assertFalse(manager.hasRepos());
    }

    @Test
    void repoEntryResolvedPath() throws IOException {
        Path repo = tempDir.resolve("project-a");
        Files.createDirectories(repo);

        RepositoryManager.RepoEntry entry = new RepositoryManager.RepoEntry(
                "project-a", repo.toString(), null);

        assertEquals(repo.normalize(), entry.resolvedPath(tempDir));
    }
}
