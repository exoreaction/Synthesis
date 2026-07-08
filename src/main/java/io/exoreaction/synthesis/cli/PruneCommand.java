package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis prune} — remove empty directories left behind by workspace reorganizations.
 *
 * <p>Safety rules (never pruned):
 * <ul>
 *   <li>Directories containing any file (including README.md)
 *   <li>Directories referenced as sub-workspace paths in config
 *   <li>Directories starting with {@code .} (hidden / dotdirs)
 *   <li>The workspace root itself
 * </ul>
 *
 * <p>Use {@code --dry-run} to preview removals without making changes.
 */
@Command(
        name = "prune",
        description = "Remove empty directories left behind by workspace reorganizations",
        mixinStandardHelpOptions = true
)
public class PruneCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--dry-run"},
            description = "Preview empty directories that would be removed without deleting anything"
    )
    private boolean dryRun;

    @Option(
            names = {"--keep-archived"},
            description = "Skip the archive/ directory tree"
    )
    private boolean keepArchived;

    @Option(
            names = {"--path"},
            description = "Scope pruning to this workspace-relative subdirectory"
    )
    private String scopePath;

    @Option(
            names = {"--yes", "-y"},
            description = "Remove without prompting for confirmation"
    )
    private boolean autoConfirm;

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();
        SynthesisConfig config = ConfigLoader.load(workspaceRoot);

        Path scanRoot = (scopePath != null && !scopePath.isBlank())
                ? workspaceRoot.resolve(scopePath)
                : workspaceRoot;

        if (!Files.isDirectory(scanRoot)) {
            System.err.println("Path not found: " + scanRoot);
            return 1;
        }

        Set<String> protectedPaths = buildProtectedPaths(workspaceRoot, config);
        List<Path> empty = findPruneable(scanRoot, workspaceRoot, protectedPaths);

        System.out.println();
        AnsiOutput.printHeader("Empty Directory Report");
        System.out.println();

        if (empty.isEmpty()) {
            System.out.println(AnsiOutput.green("  No empty directories found. Workspace is clean!"));
            System.out.println();
            return 0;
        }

        // Display candidates
        for (Path dir : empty) {
            String rel = workspaceRoot.relativize(dir).toString();
            System.out.printf("  %s%n", rel);
        }
        System.out.println();

        long preserved = countPreserved(scanRoot, workspaceRoot, protectedPaths);
        System.out.printf("Total: %d empty director%s would be removed.%n",
                empty.size(), empty.size() == 1 ? "y" : "ies");
        if (preserved > 0) {
            System.out.printf("Preserved: %d (contain files, dotfiles, or are config-referenced)%n",
                    preserved);
        }
        System.out.println();

        if (dryRun) {
            System.out.println(AnsiOutput.dim("  Dry run — no changes made. Remove --dry-run to apply."));
            System.out.println();
            return 0;
        }

        if (!autoConfirm) {
            System.out.printf("Remove %d director%s? [y/N]: ",
                    empty.size(), empty.size() == 1 ? "y" : "ies");
            System.out.flush();
            Scanner scanner = new Scanner(System.in);
            String ans = scanner.nextLine().trim().toLowerCase();
            if (!ans.equals("y") && !ans.equals("yes")) {
                System.out.println("  Aborted. No changes made.");
                return 0;
            }
        }

        int removed = pruneDirectories(empty);
        System.out.printf("%n  Removed %d director%s.%n%n",
                removed, removed == 1 ? "y" : "ies");
        return 0;
    }

    // -------------------------------------------------------------------------
    // Core logic (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Builds the set of workspace-relative paths that must never be pruned:
     * sub-workspace paths from config, plus the workspace root itself.
     */
    static Set<String> buildProtectedPaths(Path workspaceRoot, SynthesisConfig config) {
        Set<String> protected_ = new HashSet<>();
        for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
            protected_.add(sw.getPath());
        }
        return protected_;
    }

    /**
     * Returns empty directories eligible for pruning, sorted deepest-first so
     * children are removed before their parents.
     *
     * <p>A directory is eligible when it contains no regular files anywhere
     * in its subtree, is not hidden (dotdir), and is not in the protected set.
     *
     * <p><strong>Issue #329 fix:</strong> any path that has a dot-prefixed component
     * anywhere in its workspace-relative path is skipped — not just paths whose leaf
     * filename starts with {@code .}. This prevents {@code rmdir} failures on
     * directories whose subtree contains only dotdir children.
     */
    static List<Path> findPruneable(Path scanRoot, Path workspaceRoot,
                                    Set<String> protectedPaths) throws IOException {
        List<Path> result = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(scanRoot, 10)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.equals(scanRoot))
                  .filter(p -> !Files.isSymbolicLink(p))   // never prune symlinks
                  .filter(p -> !hasDotAncestor(workspaceRoot, p))
                  .filter(p -> !p.toString().contains("/.synthesis/"))
                  .filter(p -> !protectedPaths.contains(
                          workspaceRoot.relativize(p).toString()))
                  .filter(p -> isEmptyTree(p))
                  .forEach(result::add);
        }

        // Sort deepest (longest path string) first so children are removed before parents
        result.sort((a, b) -> Integer.compare(b.toString().length(), a.toString().length()));
        return result;
    }

    /**
     * Returns {@code true} if any component of {@code path}'s workspace-relative
     * representation starts with {@code .} (i.e. the path is inside a dotdir subtree).
     */
    static boolean hasDotAncestor(Path workspaceRoot, Path path) {
        Path rel = workspaceRoot.relativize(path);
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (rel.getName(i).toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if {@code dir} contains no regular files and no hidden (dot-prefixed)
     * subdirectories anywhere in its subtree.
     *
     * <p>Symlinks are never considered empty — they are user-managed and must not be pruned.
     *
     * <p><strong>Issue #329 fix:</strong> a directory that contains only dotdir children
     * (e.g. {@code .claude/}, {@code .git/}) is NOT considered empty, because POSIX
     * {@code rmdir} will fail on it — the dotdir children are still on disk. Previously
     * this method only checked for regular files, causing such directories to be
     * incorrectly marked as empty and then failing at removal time with
     * "Could not remove" warnings.
     */
    static boolean isEmptyTree(Path dir) {
        if (Files.isSymbolicLink(dir)) return false;
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(p -> !p.equals(dir))
                    .noneMatch(p ->
                            Files.isRegularFile(p)
                            || (Files.isDirectory(p) && p.getFileName().toString().startsWith(".")));
        } catch (IOException e) {
            return false;
        }
    }

    /** Attempts to delete each directory (deepest first); returns count of successfully removed dirs. */
    static int pruneDirectories(List<Path> dirs) {
        int count = 0;
        for (Path dir : dirs) {
            try {
                if (Files.deleteIfExists(dir)) {
                    count++;
                }
            } catch (IOException e) {
                System.err.printf("  Could not remove %s: %s%n", dir, e.getMessage());
            }
        }
        return count;
    }

    /** Counts directories that exist but are excluded from pruning (have files or are protected). */
    private long countPreserved(Path scanRoot, Path workspaceRoot,
                                Set<String> protectedPaths) throws IOException {
        try (Stream<Path> stream = Files.walk(scanRoot, 10)) {
            return stream.filter(Files::isDirectory)
                         .filter(p -> !p.equals(scanRoot))
                         .filter(p -> !Files.isSymbolicLink(p))
                         .filter(p -> !hasDotAncestor(workspaceRoot, p))
                         .filter(p -> !p.toString().contains("/.synthesis/"))
                         .filter(p -> {
                             // Only count dirs that would have been pruned but are protected
                             return protectedPaths.contains(
                                     workspaceRoot.relativize(p).toString())
                                     && isEmptyTree(p);
                         })
                         .count();
        }
    }
}
