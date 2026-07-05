package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.core.FileMetadata;

import java.nio.file.Files;
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
        if (!Files.isDirectory(workspaceRoot.resolve(".git"))) {
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
            if (relativePath.equals("knowledge.yaml") || relativePath.endsWith("/knowledge.yaml")) {
                if (isIgnored.test(relativePath)) {
                    result.add(relativePath);
                }
            }
        }
        return result;
    }

    private static boolean isGitIgnored(Path workspaceRoot, String relativePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "check-ignore", "-q", relativePath);
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
}
