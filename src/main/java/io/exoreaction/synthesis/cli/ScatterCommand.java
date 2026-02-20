package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code synthesis scatter} — detect entity fragmentation in multi-company workspaces.
 *
 * <p>Finds cases where the same client, product, or entity is scattered across
 * multiple directory trees, making it hard to find all related files.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Single entity:</b> {@code synthesis scatter "Mynder"} — find all directories
 *       matching an entity name and scan for content mentions outside those directories.
 *   <li><b>All entities:</b> {@code synthesis scatter --all} — auto-detect fragmented
 *       entities by normalizing directory names and grouping.
 * </ul>
 */
@Command(
        name = "scatter",
        description = "Detect entity fragmentation — same client/product scattered across directory trees",
        mixinStandardHelpOptions = true
)
public class ScatterCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(index = "0", arity = "0..1", description = "Entity name to search for")
    private String entityName;

    @Option(names = {"--all"}, description = "Auto-detect all fragmented entities")
    private boolean all;

    @Option(names = {"--top"}, description = "Number of top fragmented entities to show (default: 10)",
            defaultValue = "10")
    private int top;

    @Option(names = {"--no-mentions"}, description = "Skip scanning for content mentions")
    private boolean noMentions;

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /**
     * A group of directories sharing the same normalized entity name.
     */
    static record EntityGroup(String canonicalName, List<Path> locations, long totalFiles) implements Comparable<EntityGroup> {
        @Override
        public int compareTo(EntityGroup other) {
            // Sort by total files descending
            return Long.compare(other.totalFiles, this.totalFiles);
        }
    }

    // -------------------------------------------------------------------------
    // Prefixes and suffixes to strip during normalization
    // -------------------------------------------------------------------------

    private static final List<String> STRIP_PREFIXES = List.of(
            "opportunity-", "client-", "@active-", "@past-", "@opportunities-"
    );

    private static final List<String> STRIP_SUFFIXES = List.of(
            "-past", "-active", "-old", "-archive"
    );

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();

        if (all) {
            return runAllMode(workspaceRoot);
        }

        if (entityName == null || entityName.isBlank()) {
            System.err.println("Error: provide an entity name or use --all");
            return 1;
        }

        return runSingleMode(workspaceRoot, entityName);
    }

    private int runSingleMode(Path workspaceRoot, String entity) throws IOException {
        List<Path> dirs = findEntityDirs(workspaceRoot, entity);

        // Count files per directory
        Map<Path, Long> fileCounts = new LinkedHashMap<>();
        long totalFiles = 0;
        for (Path dir : dirs) {
            long count = countFiles(dir);
            fileCounts.put(dir, count);
            totalFiles += count;
        }

        // Print header
        AnsiOutput.printHeader("Entity Scatter Report: \"" + entity + "\"");

        if (dirs.isEmpty()) {
            System.out.println("  No directories found matching \"" + entity + "\".");
            System.out.println();
            return 0;
        }

        System.out.printf("Found in %d location%s (%d file%s total):%n%n",
                dirs.size(), dirs.size() == 1 ? "" : "s",
                totalFiles, totalFiles == 1 ? "" : "s");

        int index = 1;
        for (Path dir : dirs) {
            String rel = workspaceRoot.relativize(dir).toString();
            if (!rel.endsWith("/")) rel += "/";
            long count = fileCounts.get(dir);
            System.out.printf("  %d. %-50s %d file%s%n",
                    index++, rel, count, count == 1 ? "" : "s");
        }
        System.out.println();

        // Content mentions
        if (!noMentions && dirs.size() >= 1) {
            Map<Path, Long> mentions = findContentMentions(workspaceRoot, entity, dirs);
            if (!mentions.isEmpty()) {
                System.out.println(AnsiOutput.bold("Content mentions") + " (outside above dirs):");
                for (var entry : mentions.entrySet()) {
                    String rel = workspaceRoot.relativize(entry.getKey()).toString();
                    System.out.printf("  - %-50s (%d mention%s)%n",
                            rel, entry.getValue(), entry.getValue() == 1 ? "" : "s");
                }
                System.out.println();
            }
        }

        // Suggestion
        if (dirs.size() >= 2) {
            System.out.println(AnsiOutput.green("Suggestion: ") +
                    "Consider consolidating the " + dirs.size() +
                    " directories under a single canonical location.");
            System.out.println();
        }

        return 0;
    }

    private int runAllMode(Path workspaceRoot) throws IOException {
        List<EntityGroup> groups = findFragmentedEntities(workspaceRoot, top);

        AnsiOutput.printHeader("Top Fragmented Entities");

        if (groups.isEmpty()) {
            System.out.println("  No fragmented entities detected.");
            System.out.println();
            return 0;
        }

        for (EntityGroup group : groups) {
            System.out.printf("  %-25s %d dir%s, %d file%s%n",
                    group.canonicalName() + ":",
                    group.locations().size(), group.locations().size() == 1 ? "" : "s",
                    group.totalFiles(), group.totalFiles() == 1 ? "" : "s");
        }
        System.out.println();

        return 0;
    }

    // -------------------------------------------------------------------------
    // Static helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Finds directories whose name contains the given entity name (case-insensitive).
     * Returns only "root" matches — if a matched directory is a descendant of
     * another matched directory, it is excluded.
     */
    static List<Path> findEntityDirs(Path workspaceRoot, String entityName) throws IOException {
        String entityLower = entityName.toLowerCase();
        List<Path> matches = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.equals(workspaceRoot))
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .filter(p -> {
                      // Skip hidden segments anywhere in the path
                      Path rel = workspaceRoot.relativize(p);
                      for (int i = 0; i < rel.getNameCount(); i++) {
                          if (rel.getName(i).toString().startsWith(".")) return false;
                      }
                      return true;
                  })
                  .filter(p -> p.getFileName().toString().toLowerCase().contains(entityLower))
                  .forEach(matches::add);
        }

        // Deduplicate: remove dirs that are subdirectories of other matched dirs
        List<Path> roots = new ArrayList<>();
        for (Path candidate : matches) {
            boolean isChild = false;
            for (Path other : matches) {
                if (!other.equals(candidate) && candidate.startsWith(other)) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild) {
                roots.add(candidate);
            }
        }

        // Sort by path for consistent output
        roots.sort(Comparator.comparing(Path::toString));
        return roots;
    }

    /**
     * Counts the total number of regular files recursively under a directory.
     */
    static long countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;

        AtomicLong count = new AtomicLong();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    count.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count.get();
    }

    /**
     * Scans .md and .txt files outside the entity directories for case-insensitive
     * mentions of the entity name. Limits scanning to 2000 files.
     *
     * @return map of file path to mention count, sorted by mention count descending
     */
    static Map<Path, Long> findContentMentions(Path workspaceRoot, String entityName,
                                                List<Path> excludeDirs) throws IOException {
        String entityLower = entityName.toLowerCase();
        Pattern mentionPattern = Pattern.compile(Pattern.quote(entityName), Pattern.CASE_INSENSITIVE);

        Map<Path, Long> mentions = new TreeMap<>(Comparator.comparing(Path::toString));
        int filesScanned = 0;
        int limit = 2000;

        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            Iterator<Path> iterator = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .filter(p -> {
                        // Skip hidden segments
                        Path rel = workspaceRoot.relativize(p);
                        for (int i = 0; i < rel.getNameCount(); i++) {
                            if (rel.getName(i).toString().startsWith(".")) return false;
                        }
                        return true;
                    })
                    .filter(p -> {
                        // Exclude files inside entity directories
                        for (Path excludeDir : excludeDirs) {
                            if (p.startsWith(excludeDir)) return false;
                        }
                        return true;
                    })
                    .iterator();

            while (iterator.hasNext() && filesScanned < limit) {
                Path file = iterator.next();
                filesScanned++;

                long count = countMentionsInFile(file, mentionPattern);
                if (count > 0) {
                    mentions.put(file, count);
                }
            }
        }

        // Sort by mention count descending
        LinkedHashMap<Path, Long> sorted = new LinkedHashMap<>();
        mentions.entrySet().stream()
                .sorted(Map.Entry.<Path, Long>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));

        return sorted;
    }

    /**
     * Counts occurrences of a pattern in a file.
     */
    private static long countMentionsInFile(Path file, Pattern pattern) {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var matcher = pattern.matcher(line);
                while (matcher.find()) {
                    count++;
                }
            }
        } catch (IOException e) {
            // Skip files that can't be read
        }
        return count;
    }

    /**
     * Normalizes an entity/directory name for grouping:
     * <ul>
     *   <li>Lowercase</li>
     *   <li>Strip known prefixes (opportunity-, client-, @active-, @past-, @opportunities-)</li>
     *   <li>Strip known suffixes (-past, -active, -old, -archive)</li>
     *   <li>Replace underscores with hyphens</li>
     * </ul>
     */
    static String normalizeEntityName(String name) {
        String normalized = name.toLowerCase().replace('_', '-');

        // Strip prefixes
        for (String prefix : STRIP_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
                break; // only strip one prefix
            }
        }

        // Strip suffixes
        for (String suffix : STRIP_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                normalized = normalized.substring(0, normalized.length() - suffix.length());
                break; // only strip one suffix
            }
        }

        return normalized;
    }

    /**
     * Auto-detects fragmented entities by collecting all directories, normalizing
     * their names, grouping, and filtering for entities that appear in 2+ distinct
     * top-level locations (not ancestor/descendant of each other).
     *
     * @param topN maximum number of results to return
     * @return sorted list (by total file count descending) of fragmented entity groups
     */
    static List<EntityGroup> findFragmentedEntities(Path workspaceRoot, int topN) throws IOException {
        // Collect all directories (skip hidden)
        Map<String, List<Path>> groups = new HashMap<>();

        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.equals(workspaceRoot))
                  .filter(p -> {
                      // Skip hidden segments anywhere in the path
                      Path rel = workspaceRoot.relativize(p);
                      for (int i = 0; i < rel.getNameCount(); i++) {
                          if (rel.getName(i).toString().startsWith(".")) return false;
                      }
                      return true;
                  })
                  .forEach(p -> {
                      String normalized = normalizeEntityName(p.getFileName().toString());
                      groups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(p);
                  });
        }

        // Filter: 2+ distinct top-level locations (not ancestor/descendant of each other)
        List<EntityGroup> result = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<Path> dirs = entry.getValue();
            List<Path> roots = deduplicateAncestors(dirs);

            if (roots.size() >= 2) {
                long totalFiles = 0;
                for (Path dir : roots) {
                    try {
                        totalFiles += countFiles(dir);
                    } catch (IOException e) {
                        // skip
                    }
                }
                // Capitalize for display
                String displayName = capitalize(entry.getKey());
                result.add(new EntityGroup(displayName, roots, totalFiles));
            }
        }

        // Sort by total files descending, take top N
        Collections.sort(result);
        if (result.size() > topN) {
            result = result.subList(0, topN);
        }
        return result;
    }

    /**
     * Removes directories that are descendants of other directories in the list.
     * Keeps only "root" directories.
     */
    private static List<Path> deduplicateAncestors(List<Path> dirs) {
        List<Path> roots = new ArrayList<>();
        for (Path candidate : dirs) {
            boolean isChild = false;
            for (Path other : dirs) {
                if (!other.equals(candidate) && candidate.startsWith(other)) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild) {
                roots.add(candidate);
            }
        }
        return roots;
    }

    /** Capitalize first letter of a string. */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
