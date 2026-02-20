package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code synthesis naming} -- detect naming inconsistencies across workspace.
 *
 * <p>Analysis-only command that reports:
 * <ul>
 *   <li>Singular/plural collisions (e.g. product/ vs products/ at the same parent)</li>
 *   <li>Semantic near-duplicates (Levenshtein distance &le; 3, excluding singular/plural)</li>
 *   <li>Client naming convention drift (multiple patterns in use)</li>
 * </ul>
 *
 * <p>Never modifies files -- only reports findings.
 */
@Command(
        name = "naming",
        description = "Detect naming inconsistencies across workspace (singular/plural, semantic duplicates, convention drift)",
        mixinStandardHelpOptions = true
)
public class NamingCommand implements Callable<Integer> {

    @ParentCommand
    SynthesisApp parent;

    @Option(names = {"--path"}, description = "Scope to workspace-relative subdirectory")
    private String scopePath;

    @Option(names = {"--depth"}, description = "Max directory depth to scan (default: 6)", defaultValue = "6")
    private int depth;

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** A pair of directories that share a naming issue. */
    record Pair(Path dir1, Path dir2) {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();
        Path root = (scopePath != null && !scopePath.isBlank())
                ? workspaceRoot.resolve(scopePath)
                : workspaceRoot;

        if (!Files.isDirectory(root)) {
            System.err.println("Error: directory does not exist: " + root);
            return 1;
        }

        System.out.println();
        AnsiOutput.printHeader("Naming Consistency Report");

        boolean anyIssues = false;

        // 1. Singular/plural collisions
        List<Pair> spCollisions = findSingularPluralCollisions(root, depth);
        if (!spCollisions.isEmpty()) {
            anyIssues = true;
            System.out.println("SINGULAR/PLURAL COLLISIONS:");
            for (Pair pair : spCollisions) {
                Path parent1 = root.relativize(pair.dir1());
                Path parent2 = root.relativize(pair.dir2());
                String parentDir = parent1.getParent() != null
                        ? parent1.getParent().toString() + "/" : "";
                System.out.printf("  %s   (parent: %s)%n", parent1 + "/", parentDir.isEmpty() ? "./" : parentDir);
                System.out.printf("  %s%n", parent2 + "/");
                // Suggest the plural form
                String name1 = pair.dir1().getFileName().toString();
                String name2 = pair.dir2().getFileName().toString();
                String plural = name1.length() > name2.length() ? name1 : name2;
                System.out.printf("  -> Suggestion: merge into %s/%n", plural);
                System.out.println();
            }
        }

        // 2. Semantic near-duplicates
        List<Pair> semanticDups = findSemanticDuplicates(root, depth);
        if (!semanticDups.isEmpty()) {
            anyIssues = true;
            System.out.println("SEMANTIC NEAR-DUPLICATES:");
            for (Pair pair : semanticDups) {
                Path rel1 = root.relativize(pair.dir1());
                Path rel2 = root.relativize(pair.dir2());
                String name1 = pair.dir1().getFileName().toString();
                String name2 = pair.dir2().getFileName().toString();
                int dist = levenshtein(name1.toLowerCase(), name2.toLowerCase());
                System.out.printf("  %s%n", rel1 + "/");
                System.out.printf("  %s%n", rel2 + "/");
                System.out.printf("  -> These look like the same concept (edit distance: %d). Consider consolidating.%n", dist);
                System.out.println();
            }
        }

        // 3. Client naming conventions
        Map<String, List<Path>> conventions = detectNamingConventions(root, depth);
        if (conventions.size() >= 2) {
            anyIssues = true;
            System.out.printf("CLIENT NAMING CONVENTIONS (%d patterns detected):%n", conventions.size());
            for (var entry : conventions.entrySet()) {
                String names = entry.getValue().stream()
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.joining(", "));
                System.out.printf("  Pattern \"%s\":  %s%n", entry.getKey(), names);
            }
            System.out.println("  -> Recommendation: pick one pattern and standardise");
            System.out.println();
        }

        if (!anyIssues) {
            System.out.println(AnsiOutput.green("No naming inconsistencies found."));
            System.out.println();
        }

        return 0;
    }

    // -------------------------------------------------------------------------
    // Static helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Returns pairs of directories at the same parent that are singular/plural of each other.
     */
    static List<Pair> findSingularPluralCollisions(Path root, int maxDepth) throws IOException {
        List<Pair> collisions = new ArrayList<>();
        Map<Path, List<Path>> byParent = groupDirectoriesByParent(root, maxDepth);

        for (var entry : byParent.entrySet()) {
            List<Path> dirs = entry.getValue();
            for (int i = 0; i < dirs.size(); i++) {
                for (int j = i + 1; j < dirs.size(); j++) {
                    String name1 = dirs.get(i).getFileName().toString();
                    String name2 = dirs.get(j).getFileName().toString();
                    if (isSingularPlural(name1, name2)) {
                        collisions.add(new Pair(dirs.get(i), dirs.get(j)));
                    }
                }
            }
        }
        return collisions;
    }

    /**
     * Returns pairs of directories at the same parent with Levenshtein distance &le; 3,
     * excluding singular/plural pairs.
     */
    static List<Pair> findSemanticDuplicates(Path root, int maxDepth) throws IOException {
        List<Pair> duplicates = new ArrayList<>();
        Map<Path, List<Path>> byParent = groupDirectoriesByParent(root, maxDepth);

        for (var entry : byParent.entrySet()) {
            List<Path> dirs = entry.getValue();
            for (int i = 0; i < dirs.size(); i++) {
                for (int j = i + 1; j < dirs.size(); j++) {
                    String name1 = dirs.get(i).getFileName().toString();
                    String name2 = dirs.get(j).getFileName().toString();
                    // Skip singular/plural pairs -- they are reported separately
                    if (isSingularPlural(name1, name2)) continue;
                    int dist = levenshtein(name1.toLowerCase(), name2.toLowerCase());
                    if (dist > 0 && dist <= 3) {
                        duplicates.add(new Pair(dirs.get(i), dirs.get(j)));
                    }
                }
            }
        }
        return duplicates;
    }

    /**
     * Detects client naming convention patterns in use across the workspace.
     *
     * <p>Returns a map where the key is the pattern name (e.g. "opportunity-{Name}")
     * and the value is the list of directories matching that pattern.
     */
    static Map<String, List<Path>> detectNamingConventions(Path root, int maxDepth) throws IOException {
        Map<String, List<Path>> patterns = new LinkedHashMap<>();

        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.toString().contains("/.synthesis/"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String parentName = p.getParent() != null
                                ? p.getParent().getFileName().toString() : "";

                        if (name.startsWith("opportunity-")) {
                            patterns.computeIfAbsent("opportunity-{Name}", k -> new ArrayList<>()).add(p);
                        } else if (name.endsWith("-past")) {
                            patterns.computeIfAbsent("{Name}-past", k -> new ArrayList<>()).add(p);
                        } else if (parentName.startsWith("@")) {
                            patterns.computeIfAbsent("@{status}/{Name}", k -> new ArrayList<>()).add(p);
                        } else if (!name.startsWith("@") && !name.contains("-")
                                && Character.isUpperCase(name.charAt(0))) {
                            // Plain capitalized name with no prefix/suffix pattern
                            patterns.computeIfAbsent("{Name}", k -> new ArrayList<>()).add(p);
                        }
                    });
        }

        return patterns;
    }

    /**
     * Returns true if one name is the plural of the other (simple +s / +es rule).
     *
     * <p>Comparison is case-insensitive.
     */
    static boolean isSingularPlural(String a, String b) {
        String lower1 = a.toLowerCase();
        String lower2 = b.toLowerCase();

        // Same name is not a singular/plural pair
        if (lower1.equals(lower2)) return false;

        return isPlural(lower1, lower2) || isPlural(lower2, lower1);
    }

    /**
     * Returns true if {@code longer} is the plural of {@code shorter} (shorter + "s" or shorter + "es").
     */
    private static boolean isPlural(String shorter, String longer) {
        if (longer.equals(shorter + "s")) return true;
        if (longer.equals(shorter + "es")) return true;
        return false;
    }

    /**
     * Standard Levenshtein distance (edit distance) between two strings.
     *
     * <p>Replicates the implementation from {@link HealthCommand#levenshtein}.
     */
    static int levenshtein(String a, String b) {
        int[] dp = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) dp[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int temp = dp[j];
                dp[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev : 1 + Math.min(prev, Math.min(dp[j], dp[j - 1]));
                prev = temp;
            }
        }
        return dp[b.length()];
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Groups non-hidden directories by their parent directory.
     * Excludes .synthesis internal directories.
     */
    private static Map<Path, List<Path>> groupDirectoriesByParent(Path root, int maxDepth) throws IOException {
        Map<Path, List<Path>> byParent = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.toString().contains("/.synthesis/"))
                    .forEach(p -> byParent.computeIfAbsent(p.getParent(), k -> new ArrayList<>()).add(p));
        }
        return byParent;
    }
}
