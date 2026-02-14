package io.exoreaction.synthesis.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Git integration layer for Synthesis.
 *
 * <p>Provides access to Git repository information including:
 * <ul>
 *   <li>Changed files between refs (branches, commits, tags)</li>
 *   <li>Files changed since a specific date</li>
 *   <li>Working directory status (uncommitted changes)</li>
 * </ul>
 *
 * <p>Uses JGit for pure Java Git access without requiring a Git binary.
 */
public class GitIntegration implements AutoCloseable {

    private final Repository repository;
    private final Git git;
    private final Path workingDir;

    /**
     * Opens a Git repository at or above the given path.
     *
     * @param path a directory within a Git repository
     * @throws IOException if no Git repository is found
     */
    public GitIntegration(Path path) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .readEnvironment()
                .findGitDir(path.toFile());

        if (builder.getGitDir() == null) {
            throw new IOException("Not a Git repository (or any parent up to root): " + path);
        }

        this.repository = builder.build();
        this.git = new Git(repository);
        this.workingDir = repository.getWorkTree().toPath();
    }

    /**
     * Returns the working directory root of the Git repository.
     */
    public Path getWorkingDir() {
        return workingDir;
    }

    /**
     * Returns the current branch name.
     */
    public String getCurrentBranch() throws IOException {
        return repository.getBranch();
    }

    /**
     * Gets files changed between two Git refs (branches, commits, tags).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "main..HEAD"} - changes between main and current branch</li>
     *   <li>{@code "abc123..def456"} - changes between two commits</li>
     *   <li>{@code "v1.0..v2.0"} - changes between two tags</li>
     * </ul>
     *
     * @param refSpec a ref range like "ref1..ref2"
     * @return list of changed file information
     */
    public List<ChangedFile> diffRefs(String refSpec) throws IOException, GitAPIException {
        String[] parts = refSpec.split("\\.\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid ref spec: " + refSpec +
                    ". Expected format: ref1..ref2 (e.g., main..HEAD)");
        }

        ObjectId oldId = resolveRef(parts[0].trim());
        ObjectId newId = resolveRef(parts[1].trim());

        if (oldId == null) {
            throw new IOException("Cannot resolve ref: " + parts[0]);
        }
        if (newId == null) {
            throw new IOException("Cannot resolve ref: " + parts[1]);
        }

        AbstractTreeIterator oldTree = prepareTreeParser(oldId);
        AbstractTreeIterator newTree = prepareTreeParser(newId);

        List<DiffEntry> diffs = git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call();

        return diffs.stream()
                .map(this::toChangedFile)
                .toList();
    }

    /**
     * Gets uncommitted changes in the working directory.
     *
     * @return list of changed files
     */
    public List<ChangedFile> getUncommittedChanges() throws GitAPIException {
        Status status = git.status().call();
        List<ChangedFile> changes = new ArrayList<>();

        for (String path : status.getAdded()) {
            changes.add(new ChangedFile(path, ChangeType.ADDED));
        }
        for (String path : status.getModified()) {
            changes.add(new ChangedFile(path, ChangeType.MODIFIED));
        }
        for (String path : status.getChanged()) {
            changes.add(new ChangedFile(path, ChangeType.MODIFIED));
        }
        for (String path : status.getRemoved()) {
            changes.add(new ChangedFile(path, ChangeType.DELETED));
        }
        for (String path : status.getMissing()) {
            changes.add(new ChangedFile(path, ChangeType.DELETED));
        }
        for (String path : status.getUntracked()) {
            changes.add(new ChangedFile(path, ChangeType.ADDED));
        }

        return changes;
    }

    /**
     * Gets files that were changed in commits since a given date.
     *
     * @param since only include commits after this instant
     * @return list of changed files (deduplicated)
     */
    public List<ChangedFile> getChangesSince(Instant since) throws IOException, GitAPIException {
        Set<String> changedPaths = new LinkedHashSet<>();

        Iterable<RevCommit> commits = git.log().call();
        for (RevCommit commit : commits) {
            Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());
            if (commitTime.isBefore(since)) {
                break;
            }

            // Get changed files for this commit
            if (commit.getParentCount() > 0) {
                RevCommit parent = commit.getParent(0);
                AbstractTreeIterator oldTree = prepareTreeParser(parent);
                AbstractTreeIterator newTree = prepareTreeParser(commit);

                List<DiffEntry> diffs = git.diff()
                        .setOldTree(oldTree)
                        .setNewTree(newTree)
                        .call();

                for (DiffEntry diff : diffs) {
                    String path = diff.getNewPath().equals("/dev/null") ? diff.getOldPath() : diff.getNewPath();
                    changedPaths.add(path);
                }
            } else {
                // Root commit -- all files are "added"
                try (var walk = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                    walk.addTree(commit.getTree());
                    walk.setRecursive(true);
                    while (walk.next()) {
                        changedPaths.add(walk.getPathString());
                    }
                }
            }
        }

        return changedPaths.stream()
                .map(path -> new ChangedFile(path, ChangeType.MODIFIED))
                .toList();
    }

    /**
     * Gets the list of recent commits.
     *
     * @param maxCount maximum number of commits to return
     * @return list of commit info
     */
    public List<CommitInfo> getRecentCommits(int maxCount) throws GitAPIException {
        Iterable<RevCommit> commits = git.log().setMaxCount(maxCount).call();

        return StreamSupport.stream(commits.spliterator(), false)
                .map(commit -> new CommitInfo(
                        commit.getName().substring(0, 8),
                        commit.getShortMessage(),
                        commit.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(commit.getCommitTime())
                ))
                .toList();
    }

    private ObjectId resolveRef(String ref) throws IOException {
        // Try as a ref name first
        Ref gitRef = repository.findRef(ref);
        if (gitRef != null) {
            return gitRef.getObjectId();
        }

        // Try as a commit hash
        try {
            return repository.resolve(ref);
        } catch (Exception e) {
            return null;
        }
    }

    private AbstractTreeIterator prepareTreeParser(ObjectId objectId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(objectId);
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (var reader = repository.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }

            walk.dispose();
            return parser;
        }
    }

    private AbstractTreeIterator prepareTreeParser(RevCommit commit) throws IOException {
        RevTree tree = commit.getTree();

        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repository.newObjectReader()) {
            parser.reset(reader, tree.getId());
        }

        return parser;
    }

    private ChangedFile toChangedFile(DiffEntry diff) {
        ChangeType type = switch (diff.getChangeType()) {
            case ADD -> ChangeType.ADDED;
            case MODIFY -> ChangeType.MODIFIED;
            case DELETE -> ChangeType.DELETED;
            case RENAME -> ChangeType.RENAMED;
            case COPY -> ChangeType.COPIED;
        };

        String path = diff.getNewPath().equals("/dev/null") ? diff.getOldPath() : diff.getNewPath();
        return new ChangedFile(path, type);
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }

    // --- Data types ---

    /**
     * Represents a file that changed in Git.
     */
    public record ChangedFile(String path, ChangeType type) {
    }

    /**
     * Type of change for a file.
     */
    public enum ChangeType {
        ADDED, MODIFIED, DELETED, RENAMED, COPIED
    }

    /**
     * Basic commit information.
     */
    public record CommitInfo(
            String hash,
            String message,
            String author,
            Instant timestamp
    ) {
    }
}
