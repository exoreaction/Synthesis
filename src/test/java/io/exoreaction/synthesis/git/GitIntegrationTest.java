package io.exoreaction.synthesis.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitIntegration.
 */
class GitIntegrationTest {

    @TempDir
    Path tempDir;

    private Git git;
    private GitIntegration integration;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize a real Git repo in temp directory
        git = Git.init().setDirectory(tempDir.toFile()).call();

        // Create initial commit
        Path initialFile = tempDir.resolve("initial.txt");
        Files.writeString(initialFile, "initial content");
        git.add().addFilepattern("initial.txt").call();
        // setSign(false): the host's git config may set commit.gpgsign=true with
        // gpg.format=ssh, which JGit cannot honour (UnsupportedSigningFormatException)
        git.commit().setMessage("Initial commit")
                .setAuthor("Test", "test@test.com")
                .setSign(false)
                .call();

        integration = new GitIntegration(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (integration != null) {
            integration.close();
        }
        if (git != null) {
            git.close();
        }
    }

    @Test
    void getCurrentBranch() throws Exception {
        String branch = integration.getCurrentBranch();
        assertNotNull(branch, "Should return current branch");
        // JGit initializes with "master" by default
        assertTrue(branch.equals("master") || branch.equals("main"),
                "Should be on master or main branch");
    }

    @Test
    void getWorkingDir() {
        Path workDir = integration.getWorkingDir();
        assertEquals(tempDir.toAbsolutePath(), workDir.toAbsolutePath(),
                "Working dir should match temp dir");
    }

    @Test
    void getUncommittedChangesEmpty() throws Exception {
        List<GitIntegration.ChangedFile> changes = integration.getUncommittedChanges();
        assertTrue(changes.isEmpty(), "Should have no uncommitted changes");
    }

    @Test
    void getUncommittedChangesDetectsNewFile() throws Exception {
        Path newFile = tempDir.resolve("new.txt");
        Files.writeString(newFile, "new content");

        List<GitIntegration.ChangedFile> changes = integration.getUncommittedChanges();

        assertFalse(changes.isEmpty(), "Should detect new file");
        assertTrue(changes.stream().anyMatch(c -> c.path().equals("new.txt")),
                "Should list new.txt");
        assertTrue(changes.stream().anyMatch(c -> c.type() == GitIntegration.ChangeType.ADDED),
                "New file should have ADDED type");
    }

    @Test
    void getUncommittedChangesDetectsModifiedFile() throws Exception {
        // Modify tracked file
        Files.writeString(tempDir.resolve("initial.txt"), "modified content");

        List<GitIntegration.ChangedFile> changes = integration.getUncommittedChanges();

        assertFalse(changes.isEmpty(), "Should detect modified file");
        assertTrue(changes.stream().anyMatch(c -> c.path().equals("initial.txt")),
                "Should list initial.txt");
    }

    @Test
    void getUncommittedChangesDetectsDeletedFile() throws Exception {
        Files.delete(tempDir.resolve("initial.txt"));

        List<GitIntegration.ChangedFile> changes = integration.getUncommittedChanges();

        assertFalse(changes.isEmpty(), "Should detect deleted file");
        assertTrue(changes.stream().anyMatch(c ->
                        c.path().equals("initial.txt") && c.type() == GitIntegration.ChangeType.DELETED),
                "Should list deleted file with DELETED type");
    }

    @Test
    void diffRefsDetectsChanges() throws Exception {
        // Create a second commit
        Path newFile = tempDir.resolve("second.txt");
        Files.writeString(newFile, "second file");
        git.add().addFilepattern("second.txt").call();
        var firstCommit = git.log().setMaxCount(1).call().iterator().next();
        git.commit().setMessage("Second commit")
                .setAuthor("Test", "test@test.com")
                .setSign(false)
                .call();

        // Diff between first and second commits
        String firstHash = firstCommit.getName();
        String refSpec = firstHash + "..HEAD";

        List<GitIntegration.ChangedFile> changes = integration.diffRefs(refSpec);

        assertFalse(changes.isEmpty(), "Should detect changes between commits");
        assertTrue(changes.stream().anyMatch(c -> c.path().equals("second.txt")),
                "Should list second.txt as added");
    }

    @Test
    void diffRefsThrowsForInvalidFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> integration.diffRefs("invalid"),
                "Should throw for invalid ref spec format");
    }

    @Test
    void diffRefsThrowsForInvalidRef() {
        assertThrows(Exception.class,
                () -> integration.diffRefs("nonexistent..HEAD"),
                "Should throw for invalid ref");
    }

    @Test
    void getChangesSinceReturnsChangedFiles() throws Exception {
        // Create another commit
        Path newFile = tempDir.resolve("recent.txt");
        Files.writeString(newFile, "recent change");
        git.add().addFilepattern("recent.txt").call();
        git.commit().setMessage("Recent commit")
                .setAuthor("Test", "test@test.com")
                .setSign(false)
                .call();

        // Get changes since yesterday
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        List<GitIntegration.ChangedFile> changes = integration.getChangesSince(yesterday);

        assertFalse(changes.isEmpty(), "Should find files changed in the last day");
    }

    @Test
    void getChangesSinceFarFutureReturnsEmpty() throws Exception {
        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);
        List<GitIntegration.ChangedFile> changes = integration.getChangesSince(future);

        assertTrue(changes.isEmpty(), "Should find no changes in the future");
    }

    @Test
    void getRecentCommits() throws Exception {
        List<GitIntegration.CommitInfo> commits = integration.getRecentCommits(5);

        assertFalse(commits.isEmpty(), "Should have at least one commit");
        assertEquals("Initial commit", commits.get(0).message());
        assertEquals("Test", commits.get(0).author());
        assertNotNull(commits.get(0).hash());
        assertNotNull(commits.get(0).timestamp());
    }

    @Test
    void getRecentCommitsRespectsLimit() throws Exception {
        // Add more commits
        for (int i = 0; i < 5; i++) {
            Path f = tempDir.resolve("file" + i + ".txt");
            Files.writeString(f, "content " + i);
            git.add().addFilepattern("file" + i + ".txt").call();
            git.commit().setMessage("Commit " + i)
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        List<GitIntegration.CommitInfo> commits = integration.getRecentCommits(3);

        assertEquals(3, commits.size(), "Should respect max count limit");
    }

    @Test
    void changedFileRecordWorks() {
        GitIntegration.ChangedFile file = new GitIntegration.ChangedFile("test.txt", GitIntegration.ChangeType.ADDED);
        assertEquals("test.txt", file.path());
        assertEquals(GitIntegration.ChangeType.ADDED, file.type());
    }

    @Test
    void commitInfoRecordWorks() {
        Instant now = Instant.now();
        GitIntegration.CommitInfo info = new GitIntegration.CommitInfo("abc123", "fix bug", "Author", now);
        assertEquals("abc123", info.hash());
        assertEquals("fix bug", info.message());
        assertEquals("Author", info.author());
        assertEquals(now, info.timestamp());
    }
}
