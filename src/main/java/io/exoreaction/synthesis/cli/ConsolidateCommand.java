package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * {@code synthesis consolidate} — merge fragmented entity directories into a single canonical location.
 *
 * <p>This is the action companion to {@code synthesis scatter} — once scatter has identified
 * fragmented directories for an entity, consolidate merges them into one canonical location.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>Plan mode (default):</b> shows the migration plan and prompts for execution</li>
 *   <li><b>{@code --execute}:</b> skip prompts and execute immediately</li>
 *   <li><b>{@code --dry-run}:</b> show plan only, never execute</li>
 * </ul>
 *
 * <p>After moving files, scans {@code .md} files in the workspace for references to old paths
 * and updates them (best-effort cross-reference updating).
 */
@Command(
        name = "consolidate",
        description = "Merge fragmented entity directories into a single canonical location",
        mixinStandardHelpOptions = true
)
public class ConsolidateCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(index = "0", description = "Entity name to consolidate")
    private String entityName;

    @Option(names = {"--target"}, description = "Target canonical path (workspace-relative)")
    private String target;

    @Option(names = {"--execute"}, description = "Execute without prompting")
    private boolean execute;

    @Option(names = {"--dry-run"}, description = "Show plan without executing")
    private boolean dryRun;

    @Option(names = {"--symlinks"}, description = "Create symlinks at old locations")
    private boolean symlinks;

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /**
     * A single step in a consolidation migration: move files from sourceDir to targetDir.
     */
    record MigrationStep(Path sourceDir, Path targetDir, int fileCount) {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();

        if (entityName == null || entityName.isBlank()) {
            System.err.println("Error: entity name is required");
            return 1;
        }

        // 1. Find all directories matching the entity name
        List<Path> sourceDirs = ScatterCommand.findEntityDirs(workspaceRoot, entityName);

        if (sourceDirs.isEmpty()) {
            System.out.println("No directories found matching \"" + entityName + "\".");
            return 0;
        }

        if (sourceDirs.size() < 2 && target == null) {
            System.out.println("Only one directory found for \"" + entityName + "\". Nothing to consolidate.");
            return 0;
        }

        // 2. Determine target path
        Path targetPath;
        if (target != null && !target.isBlank()) {
            targetPath = workspaceRoot.resolve(target);
        } else {
            targetPath = proposeTarget(workspaceRoot, entityName, sourceDirs);
        }

        // 3. Build migration steps
        List<MigrationStep> steps = new ArrayList<>();
        long totalFiles = 0;
        for (Path sourceDir : sourceDirs) {
            // Skip source dirs that are already under the target
            if (sourceDir.startsWith(targetPath) || sourceDir.equals(targetPath)) {
                continue;
            }
            String subdir = inferSubdir(sourceDir);
            Path stepTarget = targetPath.resolve(subdir);
            int fileCount = countFilesFlat(sourceDir);
            steps.add(new MigrationStep(sourceDir, stepTarget, fileCount));
            totalFiles += fileCount;
        }

        if (steps.isEmpty()) {
            System.out.println("All directories are already at the target location. Nothing to do.");
            return 0;
        }

        // 4. Show plan
        printPlan(workspaceRoot, sourceDirs, targetPath, steps, totalFiles);

        if (dryRun) {
            System.out.println(AnsiOutput.dim("  Dry run -- no changes made."));
            System.out.println();
            return 0;
        }

        // 5. Interactive prompts or auto-execute
        if (!execute) {
            Scanner scanner = new Scanner(System.in);

            System.out.print("Preview migration script? [y/N]: ");
            System.out.flush();
            String previewAns = scanner.nextLine().trim().toLowerCase();
            if (previewAns.equals("y") || previewAns.equals("yes")) {
                System.out.println();
                System.out.println(generateMigrationScript(workspaceRoot, steps));
            }

            System.out.print("Execute? [y/N]: ");
            System.out.flush();
            String executeAns = scanner.nextLine().trim().toLowerCase();
            if (!executeAns.equals("y") && !executeAns.equals("yes")) {
                System.out.println("  Aborted. No changes made.");
                return 0;
            }
        }

        // 6. Execute migration
        int movedFiles = 0;
        int removedDirs = 0;
        int updatedRefs = 0;

        for (MigrationStep step : steps) {
            int moved = moveFiles(step.sourceDir(), step.targetDir());
            movedFiles += moved;

            // Update cross-references
            String oldRelPath = workspaceRoot.relativize(step.sourceDir()).toString();
            String newRelPath = workspaceRoot.relativize(step.targetDir()).toString();
            updatedRefs += updateCrossReferences(workspaceRoot, oldRelPath, newRelPath);

            // Create symlink if requested
            if (symlinks && Files.exists(step.sourceDir())) {
                try {
                    // Remove the now-empty source dir first
                    if (PruneCommand.isEmptyTree(step.sourceDir())) {
                        deleteDirectoryRecursively(step.sourceDir());
                        removedDirs++;
                        Files.createSymbolicLink(step.sourceDir(), step.targetDir());
                    }
                } catch (IOException e) {
                    System.err.printf("  Could not create symlink at %s: %s%n",
                            step.sourceDir(), e.getMessage());
                }
            } else {
                // Remove empty source dirs
                if (PruneCommand.isEmptyTree(step.sourceDir())) {
                    try {
                        deleteDirectoryRecursively(step.sourceDir());
                        removedDirs++;
                    } catch (IOException e) {
                        System.err.printf("  Could not remove %s: %s%n",
                                step.sourceDir(), e.getMessage());
                    }
                }
            }
        }

        // Print summary
        System.out.println();
        System.out.printf("  Moved %d file%s.%n", movedFiles, movedFiles == 1 ? "" : "s");
        System.out.printf("  Updated %d cross-reference%s.%n", updatedRefs, updatedRefs == 1 ? "" : "s");
        System.out.printf("  Removed %d empty source director%s.%n",
                removedDirs, removedDirs == 1 ? "y" : "ies");
        System.out.println();

        return 0;
    }

    // -------------------------------------------------------------------------
    // Plan display
    // -------------------------------------------------------------------------

    private void printPlan(Path workspaceRoot, List<Path> sourceDirs,
                           Path targetPath, List<MigrationStep> steps, long totalFiles) throws IOException {
        AnsiOutput.printHeader("Consolidation Plan: \"" + entityName + "\"");

        // Count total files across all source dirs
        long allSourceFiles = 0;
        for (Path dir : sourceDirs) {
            allSourceFiles += ScatterCommand.countFiles(dir);
        }

        System.out.printf("Sources (%d location%s, %d file%s):%n",
                sourceDirs.size(), sourceDirs.size() == 1 ? "" : "s",
                allSourceFiles, allSourceFiles == 1 ? "" : "s");

        int index = 1;
        for (Path dir : sourceDirs) {
            String rel = workspaceRoot.relativize(dir).toString();
            if (!rel.endsWith("/")) rel += "/";
            long count = ScatterCommand.countFiles(dir);
            System.out.printf("  %d. %-55s %d file%s%n",
                    index++, rel, count, count == 1 ? "" : "s");
        }
        System.out.println();

        String targetRel = workspaceRoot.relativize(targetPath).toString();
        if (!targetRel.endsWith("/")) targetRel += "/";
        System.out.println("Proposed target: " + targetRel);

        for (MigrationStep step : steps) {
            String subdir = workspaceRoot.relativize(step.targetDir()).toString()
                    .substring(targetRel.length() - 1); // after target path
            if (subdir.startsWith("/")) subdir = subdir.substring(1);
            if (!subdir.endsWith("/")) subdir += "/";
            String sourceRel = workspaceRoot.relativize(step.sourceDir()).toString();
            if (!sourceRel.endsWith("/")) sourceRel += "/";
            System.out.printf("  %-15s <- from %s%n", subdir, sourceRel);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Static helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Infer target subdirectory name for files from a given source dir,
     * based on parent directory name heuristics.
     *
     * <ul>
     *   <li>Parent contains "marketing" -> "marketing"</li>
     *   <li>Parent contains "workshop" or "prospect" -> "workshop-prep"</li>
     *   <li>Parent contains "opportunit" or "proposal" -> "proposals"</li>
     *   <li>Parent contains "travel" or "personal" -> "personal"</li>
     *   <li>Otherwise -> source dir's own name (lowercased)</li>
     * </ul>
     */
    static String inferSubdir(Path sourceDir) {
        Path parentDir = sourceDir.getParent();
        if (parentDir == null) {
            return sourceDir.getFileName().toString().toLowerCase();
        }

        String parentName = parentDir.getFileName().toString().toLowerCase();

        if (parentName.contains("marketing")) {
            return "marketing";
        }
        if (parentName.contains("workshop") || parentName.contains("prospect")) {
            return "workshop-prep";
        }
        if (parentName.contains("opportunit") || parentName.contains("proposal")) {
            return "proposals";
        }
        if (parentName.contains("travel") || parentName.contains("personal")) {
            return "personal";
        }

        return sourceDir.getFileName().toString().toLowerCase();
    }

    /**
     * Propose the canonical target path for an entity.
     *
     * <p>Heuristic: look for a "clients/" dir under workspace; if it exists,
     * use {@code clients/EntityName}. Otherwise, use the parent of the largest
     * source dir + "/" + entityName (CamelCase, spaces removed).
     */
    static Path proposeTarget(Path workspaceRoot, String entityName,
                               List<Path> sourceDirs) throws IOException {
        // Check if a clients/ directory exists in the workspace tree
        Path clientsDir = findClientsDir(workspaceRoot);
        String camelName = toCamelCase(entityName);

        if (clientsDir != null) {
            return clientsDir.resolve(camelName);
        }

        // Fallback: use parent of the largest source dir
        Path largestDir = null;
        long largestCount = -1;
        for (Path dir : sourceDirs) {
            long count = ScatterCommand.countFiles(dir);
            if (count > largestCount) {
                largestCount = count;
                largestDir = dir;
            }
        }

        if (largestDir != null && largestDir.getParent() != null) {
            return largestDir.getParent().resolve(camelName);
        }

        // Ultimate fallback: workspace root
        return workspaceRoot.resolve(camelName);
    }

    /**
     * Move all files from sourceDir to targetDir, creating targetDir if needed.
     * Moves files recursively (preserving subdirectory structure within sourceDir).
     *
     * @return count of moved files
     */
    static int moveFiles(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        Files.createDirectories(targetDir);

        int[] count = {0};
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(dir);
                Path targetSubDir = targetDir.resolve(relative.toString());
                Files.createDirectories(targetSubDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(file);
                Path targetFile = targetDir.resolve(relative.toString());
                Files.createDirectories(targetFile.getParent());
                Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.printf("  Warning: could not move %s: %s%n", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        return count[0];
    }

    /**
     * Scan .md files in workspace for references to oldPath and replace with newPath.
     * Simple string replacement — best-effort. Returns count of files updated.
     */
    static int updateCrossReferences(Path workspaceRoot, String oldRelPath,
                                      String newRelPath) throws IOException {
        if (oldRelPath.equals(newRelPath)) {
            return 0;
        }

        int[] updatedCount = {0};

        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".md");
                  })
                  .filter(p -> {
                      // Skip hidden segments
                      Path rel = workspaceRoot.relativize(p);
                      for (int i = 0; i < rel.getNameCount(); i++) {
                          if (rel.getName(i).toString().startsWith(".")) return false;
                      }
                      return true;
                  })
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          if (content.contains(oldRelPath)) {
                              String updated = content.replace(oldRelPath, newRelPath);
                              Files.writeString(p, updated);
                              updatedCount[0]++;
                          }
                      } catch (IOException e) {
                          // Skip files that can't be read/written
                      }
                  });
        }

        return updatedCount[0];
    }

    /**
     * Generate a shell migration script as a String.
     */
    static String generateMigrationScript(Path workspaceRoot, List<MigrationStep> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("# Consolidation migration script\n");
        sb.append("# Generated by synthesis consolidate\n");
        sb.append("set -e\n\n");

        for (MigrationStep step : steps) {
            String sourceRel = workspaceRoot.relativize(step.sourceDir()).toString();
            String targetRel = workspaceRoot.relativize(step.targetDir()).toString();

            sb.append("# Move ").append(step.fileCount()).append(" file(s) from ")
              .append(sourceRel).append("\n");
            sb.append("mkdir -p \"").append(targetRel).append("\"\n");
            sb.append("mv \"").append(sourceRel).append("/\"* \"")
              .append(targetRel).append("/\" 2>/dev/null || true\n");
            sb.append("rmdir \"").append(sourceRel).append("\" 2>/dev/null || true\n");
            sb.append("\n");
        }

        sb.append("echo \"Migration complete.\"\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Count files directly in a directory (non-recursive, just immediate children).
     * Used for building migration step counts.
     */
    private static int countFilesFlat(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        int count = 0;
        // Count recursively to match the file count that moveFiles will process
        AtomicLong total = new AtomicLong();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    total.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return (int) total.get();
    }

    /**
     * Finds a "clients" directory under the workspace root (first level sub-workspaces).
     * Searches up to 3 levels deep.
     */
    private static Path findClientsDir(Path workspaceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(workspaceRoot, 3)) {
            return stream.filter(Files::isDirectory)
                         .filter(p -> !p.equals(workspaceRoot))
                         .filter(p -> p.getFileName().toString().equalsIgnoreCase("clients"))
                         .filter(p -> {
                             Path rel = workspaceRoot.relativize(p);
                             for (int i = 0; i < rel.getNameCount(); i++) {
                                 if (rel.getName(i).toString().startsWith(".")) return false;
                             }
                             return true;
                         })
                         .findFirst()
                         .orElse(null);
        }
    }

    /**
     * Converts an entity name like "Item Consulting" to CamelCase: "ItemConsulting".
     */
    static String toCamelCase(String name) {
        if (name == null || name.isBlank()) return name;
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == ' ' || c == '-' || c == '_') {
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    sb.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
