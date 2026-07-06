package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.AnsiOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Detects KCP manifests (knowledge.yaml) that are indexed locally but excluded
 * from git via .gitignore -- meaning they never reach the remote/team even
 * though Synthesis can see and index them (issue #309).
 */
public final class KcpManifestChecks {

    private KcpManifestChecks() {
    }

    /**
     * Returns the relative paths of any {@code knowledge.yaml} files in
     * {@code scannedFiles} that are gitignored. Returns an empty list
     * immediately if {@code workspaceRoot} is not a git repository.
     */
    public static List<String> findGitignoredManifests(Path workspaceRoot, List<FileMetadata> scannedFiles) {
        if (!isRealGitRepo(workspaceRoot)) {
            return List.of();
        }
        return findGitignoredManifests(workspaceRoot, scannedFiles,
                relativePath -> isGitIgnored(workspaceRoot, relativePath));
    }

    /**
     * Same as {@link #findGitignoredManifests(Path, List)} but with an injectable
     * ignore-check predicate, so callers (and tests) can avoid spawning a real
     * git subprocess.
     */
    static List<String> findGitignoredManifests(Path workspaceRoot, List<FileMetadata> scannedFiles,
                                                 Predicate<String> isIgnored) {
        List<String> result = new ArrayList<>();
        for (FileMetadata fm : scannedFiles) {
            String relativePath = fm.relativePath();
            if (fm.fileName().equals("knowledge.yaml")) {
                if (isIgnored.test(relativePath)) {
                    result.add(relativePath);
                }
            }
        }
        return result;
    }

    /**
     * Returns true if {@code workspaceRoot} is genuinely inside a git working tree
     * (handles both plain repos and linked worktrees, where {@code .git} is a file,
     * not a directory). Any failure to run git (missing binary, off PATH, permission
     * issues, etc.) is treated as "not a git repo" rather than propagating -- this
     * must never crash a scan/maintain run.
     */
    private static boolean isRealGitRepo(Path workspaceRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.directory(workspaceRoot.toFile());
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes()).trim();
            return proc.waitFor() == 0 && "true".equals(stdout);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if {@code relativePath} is gitignored in {@code workspaceRoot}.
     * Returns false when the workspace is not a git repository. Public so K003
     * health checks (issue #355) can test a single manifest path.
     */
    public static boolean isManifestGitIgnored(Path workspaceRoot, String relativePath) {
        return isRealGitRepo(workspaceRoot) && isGitIgnored(workspaceRoot, relativePath);
    }

    private static boolean isGitIgnored(Path workspaceRoot, String relativePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "check-ignore", "-q", "--", relativePath);
            pb.directory(workspaceRoot.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Builds the warning message (with remedy) for a gitignored manifest at {@code relativePath}. */
    public static String warningFor(String relativePath) {
        return "WARNING: " + relativePath + " found but is listed in .gitignore -- run: git add -f "
                + relativePath + " to track it (or remove the .gitignore entry)";
    }

    /**
     * Prints the "Manifest coverage issues" section for {@code gitignoredManifests}
     * (header + one warning line per path), or nothing if the list is empty.
     * Shared by ScanCommand and MaintainCommand to avoid duplicating this formatting.
     */
    public static void printWarnings(List<String> gitignoredManifests) {
        if (gitignoredManifests.isEmpty()) {
            return;
        }
        System.out.println("  " + AnsiOutput.bold("Manifest coverage issues:"));
        for (String path : gitignoredManifests) {
            System.out.println("    " + AnsiOutput.error(warningFor(path)));
        }
    }
}
