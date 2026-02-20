package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis archive audit} — scans archive directories for duplicates,
 * build artifacts, large unexplored areas, and generates a manifest.
 *
 * <p>Subcommand: {@code synthesis archive audit [--path <dir>]}
 */
@Command(
        name = "archive",
        description = "Archive management commands",
        mixinStandardHelpOptions = true,
        subcommands = {ArchiveCommand.AuditCommand.class}
)
public class ArchiveCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Usage: synthesis archive audit [options]");
        return 1;
    }

    // =========================================================================
    // audit subcommand
    // =========================================================================

    @Command(
            name = "audit",
            description = "Audit archive for duplicates, build artifacts, and space recovery",
            mixinStandardHelpOptions = true
    )
    public static class AuditCommand implements Callable<Integer> {

        @ParentCommand
        private ArchiveCommand archiveCmd;

        // Access grandparent SynthesisApp via parent chain
        @picocli.CommandLine.Spec
        picocli.CommandLine.Model.CommandSpec spec;

        @Option(
                names = {"--path"},
                description = "Archive directory to audit (default: archive/ under workspace root)"
        )
        private String archivePath;

        @Option(
                names = {"--max-file-mb"},
                description = "Skip files larger than this for hashing (default: 200 MB)",
                defaultValue = "200"
        )
        private int maxFileMb;

        @Option(
                names = {"--generate-manifest"},
                description = "Write MANIFEST.md to the archive root after audit"
        )
        private boolean generateManifest;

        @Option(
                names = {"--yes", "-y"},
                description = "Generate manifest without prompting"
        )
        private boolean autoYes;

        @Override
        public Integer call() throws Exception {
            // Walk up command spec to find workspace root
            Path workspaceRoot = resolveWorkspaceRoot();

            Path auditRoot = (archivePath != null && !archivePath.isBlank())
                    ? workspaceRoot.resolve(archivePath)
                    : findArchiveDir(workspaceRoot);

            if (auditRoot == null || !Files.isDirectory(auditRoot)) {
                System.err.println("Archive directory not found. Use --path to specify.");
                return 1;
            }

            System.out.println();
            AnsiOutput.printHeader("Archive Audit: " + auditRoot);
            System.out.println();

            long maxBytes = (long) maxFileMb * 1024 * 1024;

            // Collect all files
            List<Path> allFiles = listAllFiles(auditRoot);
            long totalSize = allFiles.stream().mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();

            System.out.printf("  Total: %s across %,d files%n%n",
                    HealthCommand.formatSize(totalSize), allFiles.size());

            // Duplicate detection
            Map<String, List<Path>> byHash = findDuplicates(allFiles, maxBytes);
            List<DuplicateGroup> dupeGroups = new ArrayList<>();
            for (var entry : byHash.entrySet()) {
                if (entry.getValue().size() > 1) {
                    long size = Files.size(entry.getValue().get(0));
                    dupeGroups.add(new DuplicateGroup(entry.getKey(), entry.getValue(), size));
                }
            }
            dupeGroups.sort((a, b) -> Long.compare(b.savingsBytes(), a.savingsBytes()));

            // Build artifacts
            List<Path> artifacts = findBuildArtifacts(auditRoot);

            // Large unexplored dirs
            List<DirSummary> largeDirs = findLargeDirs(auditRoot, workspaceRoot);

            // Print results
            long dupeSavings = printDuplicates(dupeGroups, auditRoot);
            long artifactSize = printArtifacts(artifacts, auditRoot);
            printLargeDirs(largeDirs);
            printRecommendation(dupeSavings, artifactSize, totalSize);

            // Manifest
            if (generateManifest || promptManifest(autoYes)) {
                writeManifest(auditRoot, allFiles, dupeGroups, artifacts, largeDirs,
                        totalSize, dupeSavings, artifactSize);
            }

            return 0;
        }

        private Path resolveWorkspaceRoot() throws IOException {
            // Walk up spec hierarchy to find SynthesisApp's workspace root
            picocli.CommandLine.Model.CommandSpec current = spec;
            while (current.parent() != null) {
                current = current.parent();
            }
            Object userObject = current.userObject();
            if (userObject instanceof SynthesisApp app) {
                return app.getWorkspaceRoot();
            }
            return Path.of(System.getProperty("user.dir"));
        }

        private boolean promptManifest(boolean autoYes) {
            if (autoYes) return true;
            System.out.print("Generate manifest? [y/N]: ");
            System.out.flush();
            try {
                Scanner scanner = new Scanner(System.in);
                String ans = scanner.nextLine().trim().toLowerCase();
                return ans.equals("y") || ans.equals("yes");
            } catch (Exception e) {
                return false;
            }
        }
    }

    // =========================================================================
    // Static helpers (package-visible for tests)
    // =========================================================================

    /** Finds the archive directory under workspace root. */
    static Path findArchiveDir(Path workspaceRoot) throws IOException {
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            return stream.filter(Files::isDirectory)
                         .filter(p -> {
                             String name = p.getFileName().toString().toLowerCase();
                             return name.equals("archive") || name.equals("@archive");
                         })
                         .findFirst().orElse(null);
        }
    }

    /** Lists all regular files under a directory. */
    static List<Path> listAllFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .forEach(files::add);
        }
        return files;
    }

    /**
     * Groups files by content hash (SHA-256).
     * Files larger than {@code maxBytes} are skipped.
     */
    static Map<String, List<Path>> findDuplicates(List<Path> files,
                                                   long maxBytes) throws IOException {
        Map<String, List<Path>> byHash = new LinkedHashMap<>();
        for (Path file : files) {
            long size = Files.size(file);
            if (size == 0 || size > maxBytes) continue;
            String hash = hashFile(file);
            if (hash != null) {
                byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(file);
            }
        }
        return byHash;
    }

    /** Computes SHA-256 hash of a file, returning hex string or null on error. */
    static String hashFile(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65_536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Finds build artifact directories (node_modules, .class files). */
    static List<Path> findBuildArtifacts(Path root) throws IOException {
        List<Path> artifacts = new ArrayList<>();
        Set<String> artifactDirNames = Set.of("node_modules", "bower_components");

        try (Stream<Path> stream = Files.walk(root, 8)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> artifactDirNames.contains(p.getFileName().toString()))
                  .forEach(artifacts::add);
        }

        // .class files outside target/
        Set<String> seen = new java.util.HashSet<>();
        try (Stream<Path> stream = Files.walk(root, 8)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".class"))
                  .filter(p -> !p.toString().contains("/target/"))
                  .map(Path::getParent)
                  .filter(p -> seen.add(p.toString()))
                  .forEach(artifacts::add);
        }

        return artifacts;
    }

    /** Summarises immediate children directories by size. */
    static List<DirSummary> findLargeDirs(Path archiveRoot, Path workspaceRoot) throws IOException {
        List<DirSummary> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(archiveRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    long size = HealthCommand.dirSize(dir);
                    long fileCount = listAllFiles(dir).size();
                    boolean hasManifest = Files.exists(dir.resolve("MANIFEST.md"))
                            || Files.exists(dir.resolve("README.md"));
                    dirs.add(new DirSummary(dir, size, fileCount, hasManifest));
                } catch (IOException e) {
                    // skip
                }
            });
        }
        dirs.sort((a, b) -> Long.compare(b.sizeBytes(), a.sizeBytes()));
        return dirs;
    }

    // =========================================================================
    // Printing
    // =========================================================================

    private static long printDuplicates(List<DuplicateGroup> groups, Path archiveRoot) {
        if (groups.isEmpty()) {
            System.out.println(AnsiOutput.green("  No duplicates found."));
            System.out.println();
            return 0L;
        }

        long totalSavings = groups.stream().mapToLong(DuplicateGroup::savingsBytes).sum();
        System.out.printf("%s:%n", AnsiOutput.bold("DUPLICATES (by content hash)"));
        for (DuplicateGroup g : groups) {
            String names = g.paths().stream()
                    .map(p -> p.getFileName().toString())
                    .reduce((a, b) -> a + " ≈ " + b).orElse("");
            System.out.printf("  %s  (%s × %d)%n",
                    names, HealthCommand.formatSize(g.fileSize()), g.paths().size());
            // Show paths if different dirs
            boolean sameDir = g.paths().stream()
                    .map(Path::getParent).distinct().count() == 1;
            if (!sameDir) {
                g.paths().forEach(p -> System.out.printf("    %s%n",
                        archiveRoot.getParent() != null
                                ? archiveRoot.getParent().relativize(p) : p));
            }
        }
        System.out.printf("  Potential savings: ~%s%n%n",
                HealthCommand.formatSize(totalSavings));
        return totalSavings;
    }

    private static long printArtifacts(List<Path> artifacts, Path archiveRoot) throws IOException {
        if (artifacts.isEmpty()) return 0L;

        long totalSize = artifacts.stream().mapToLong(p -> {
            try { return HealthCommand.dirSize(p); } catch (IOException e) { return 0L; }
        }).sum();

        System.out.printf("%s (recommend deletion):%n", AnsiOutput.bold("BUILD ARTIFACTS"));
        for (Path a : artifacts) {
            System.out.printf("  %s  (%s)%n",
                    archiveRoot.getParent() != null
                            ? archiveRoot.getParent().relativize(a) : a,
                    HealthCommand.formatSize(HealthCommand.dirSize(a)));
        }
        System.out.println();
        return totalSize;
    }

    private static void printLargeDirs(List<DirSummary> dirs) {
        if (dirs.isEmpty()) return;

        System.out.printf("%s (no README/manifest):%n", AnsiOutput.bold("LARGE UNEXPLORED"));
        for (DirSummary d : dirs) {
            if (!d.hasManifest() && d.sizeBytes() > 10 * 1024 * 1024) { // > 10 MB
                System.out.printf("  %-20s  %8s  (%,d files)%n",
                        d.path().getFileName() + "/",
                        HealthCommand.formatSize(d.sizeBytes()),
                        d.fileCount());
            }
        }
        System.out.println();
    }

    private static void printRecommendation(long dupeSavings, long artifactSize, long totalSize) {
        System.out.printf("%s:%n", AnsiOutput.bold("RECOMMENDATION"));
        if (artifactSize > 0) {
            System.out.printf("  Safe to delete:    %s (build artifacts)%n",
                    HealthCommand.formatSize(artifactSize));
        }
        if (dupeSavings > 0) {
            System.out.printf("  Likely duplicates: %s%n",
                    HealthCommand.formatSize(dupeSavings));
        }
        long unknownSize = totalSize - dupeSavings - artifactSize;
        if (unknownSize > 0) {
            System.out.printf("  Needs review:      %s%n",
                    HealthCommand.formatSize(Math.max(0, unknownSize)));
        }
        System.out.println();
    }

    private static void writeManifest(Path archiveRoot, List<Path> files,
                                       List<DuplicateGroup> dupes, List<Path> artifacts,
                                       List<DirSummary> dirs, long totalSize,
                                       long dupeSavings, long artifactSize) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Archive Manifest\n\n");
        sb.append("Generated: ").append(LocalDate.now()).append("\n\n");
        sb.append("## Summary\n\n");
        sb.append("- **Total size:** ").append(HealthCommand.formatSize(totalSize)).append("\n");
        sb.append("- **Total files:** ").append(String.format("%,d", files.size())).append("\n");
        sb.append("- **Duplicate savings:** ").append(HealthCommand.formatSize(dupeSavings)).append("\n");
        sb.append("- **Build artifacts:** ").append(HealthCommand.formatSize(artifactSize)).append("\n\n");

        if (!dupes.isEmpty()) {
            sb.append("## Duplicates\n\n");
            for (DuplicateGroup g : dupes) {
                String names = g.paths().stream()
                        .map(p -> p.getFileName().toString())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                sb.append("- ").append(names).append("  (")
                  .append(HealthCommand.formatSize(g.fileSize())).append(" × ")
                  .append(g.paths().size()).append(")\n");
            }
            sb.append("\n");
        }

        if (!dirs.isEmpty()) {
            sb.append("## Directory Overview\n\n");
            sb.append("| Directory | Size | Files | Status |\n");
            sb.append("|-----------|------|-------|--------|\n");
            for (DirSummary d : dirs) {
                sb.append("| ").append(d.path().getFileName()).append("/ | ")
                  .append(HealthCommand.formatSize(d.sizeBytes())).append(" | ")
                  .append(String.format("%,d", d.fileCount())).append(" | ")
                  .append(d.hasManifest() ? "documented" : "needs review").append(" |\n");
            }
            sb.append("\n");
        }

        Path manifestFile = archiveRoot.resolve("MANIFEST.md");
        Files.writeString(manifestFile, sb.toString());
        System.out.println("  Manifest written: " + manifestFile);
        System.out.println();
    }

    // =========================================================================
    // Records
    // =========================================================================

    public record DuplicateGroup(String hash, List<Path> paths, long fileSize) {
        long savingsBytes() {
            return fileSize * (paths.size() - 1);
        }
    }

    public record DirSummary(Path path, long sizeBytes, long fileCount, boolean hasManifest) {}
}
