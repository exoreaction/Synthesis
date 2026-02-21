package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.util.AnsiOutput;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
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
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis health} — workspace structural audit with health score.
 *
 * <p>Checks for:
 * <ul>
 *   <li>E001: Phantom sub-workspace paths (config entries with no matching directory)
 *   <li>E002: Build artifacts (node_modules, .class files outside target/)
 *   <li>W001: Empty directories
 *   <li>W002: Excessive loose files at the workspace root
 *   <li>I001: Archive size relative to total workspace
 * </ul>
 *
 * <p>With {@code --fix-config}: fuzzy-matches phantom paths against real
 * directories and offers to update config.yaml.
 */
@Command(
        name = "health",
        description = "Check workspace structural health and integrity",
        mixinStandardHelpOptions = true
)
public class HealthCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--fix-config"},
            description = "Reconcile phantom sub-workspace paths with the filesystem"
    )
    private boolean fixConfig;

    @Option(
            names = {"--yes", "-y"},
            description = "Apply all suggested config remappings without prompting (use with --fix-config)"
    )
    private boolean autoApply;

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** A single health finding with severity, code, description and optional detail lines. */
    public record HealthIssue(Severity severity, String code, String description,
                              String fix, List<String> details) {
        public enum Severity { ERROR, WARNING, INFO }

        public HealthIssue(Severity severity, String code, String description, String fix) {
            this(severity, code, description, fix, List.of());
        }

        public HealthIssue(Severity severity, String code, String description) {
            this(severity, code, description, null, List.of());
        }
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();
        SynthesisConfig config = ConfigLoader.load(workspaceRoot);

        if (fixConfig) {
            return runFixConfig(workspaceRoot, config);
        }
        return runAudit(workspaceRoot, config);
    }

    // -------------------------------------------------------------------------
    // Audit
    // -------------------------------------------------------------------------

    private Integer runAudit(Path workspaceRoot, SynthesisConfig config) throws IOException {
        System.out.println();
        AnsiOutput.printHeader("Workspace Health Report");
        System.out.println();

        List<HealthIssue> issues = new ArrayList<>();

        // E001: Phantom sub-workspace paths
        List<SubWorkspaceConfig> phantoms = findPhantomSubWorkspaces(workspaceRoot, config);
        if (!phantoms.isEmpty()) {
            List<String> details = phantoms.stream()
                    .map(sw -> sw.getPath() + "  -> does not exist")
                    .toList();
            issues.add(new HealthIssue(
                    HealthIssue.Severity.ERROR, "E001",
                    phantoms.size() + " phantom sub-workspace path(s) in config",
                    "synthesis health --fix-config",
                    details));
        }

        // E002: Build artifacts (node_modules, stray .class files)
        List<Path> artifacts = findBuildArtifacts(workspaceRoot);
        if (!artifacts.isEmpty()) {
            long totalSize = artifacts.stream().mapToLong(p -> {
                try { return dirSize(p); } catch (IOException e) { return 0L; }
            }).sum();
            List<String> details = artifacts.stream()
                    .map(p -> workspaceRoot.relativize(p).toString())
                    .toList();
            issues.add(new HealthIssue(
                    HealthIssue.Severity.ERROR, "E002",
                    "Build artifacts in workspace: " + formatSize(totalSize),
                    null,
                    details));
        }

        // W001: Empty directories
        List<Path> emptyDirs = findEmptyDirectories(workspaceRoot);
        if (!emptyDirs.isEmpty()) {
            issues.add(new HealthIssue(
                    HealthIssue.Severity.WARNING, "W001",
                    emptyDirs.size() + " empty director" + (emptyDirs.size() == 1 ? "y" : "ies")
                            + " (scaffolding never populated)",
                    null));
        }

        // W002: Excessive loose root-level files
        int looseFiles = countLooseRootFiles(workspaceRoot);
        if (looseFiles > 3) {
            issues.add(new HealthIssue(
                    HealthIssue.Severity.WARNING, "W002",
                    looseFiles + " files at workspace root (expected: 1-3)",
                    "synthesis sweep"));
        }

        // I001: Archive percentage
        Path archiveDir = findArchiveDir(workspaceRoot);
        if (archiveDir != null) {
            long totalSize = dirSize(workspaceRoot);
            if (totalSize > 0) {
                long archiveSize = dirSize(archiveDir);
                int archivePct = (int) (archiveSize * 100L / totalSize);
                if (archivePct > 50) {
                    issues.add(new HealthIssue(
                            HealthIssue.Severity.INFO, "I001",
                            "Archive is " + archivePct + "% of workspace ("
                                    + formatSize(archiveSize) + " / " + formatSize(totalSize) + ")",
                            null));
                }
            }
        }

        // E010: Media files in transient or hard-reject directories
        E010Check e010 = new E010Check();
        List<E010Check.E010Finding> e010Findings = e010.check(workspaceRoot);
        for (E010Check.E010Finding finding : e010Findings) {
            HealthIssue.Severity severity = switch (finding.level()) {
                case ERROR -> HealthIssue.Severity.ERROR;
                case WARNING -> HealthIssue.Severity.WARNING;
                case INFO -> HealthIssue.Severity.INFO;
            };
            String fix = finding.proposedDestination().isPresent()
                    ? "synthesis maintain --rebalance"
                    : null;
            issues.add(new HealthIssue(severity, "E010", finding.message(), fix));
        }

        printIssues(issues);

        int score = calculateScore(issues);
        String grade = scoreGrade(score);
        System.out.printf("Score: %d/100 (%s)%n%n", score, grade);

        return issues.stream().anyMatch(i -> i.severity() == HealthIssue.Severity.ERROR) ? 1 : 0;
    }

    private void printIssues(List<HealthIssue> issues) {
        Map<HealthIssue.Severity, List<HealthIssue>> bySeverity = new LinkedHashMap<>();
        bySeverity.put(HealthIssue.Severity.ERROR, new ArrayList<>());
        bySeverity.put(HealthIssue.Severity.WARNING, new ArrayList<>());
        bySeverity.put(HealthIssue.Severity.INFO, new ArrayList<>());
        for (HealthIssue issue : issues) {
            bySeverity.get(issue.severity()).add(issue);
        }

        for (var entry : bySeverity.entrySet()) {
            List<HealthIssue> group = entry.getValue();
            if (group.isEmpty()) continue;

            String label = switch (entry.getKey()) {
                case ERROR -> AnsiOutput.red("ERRORS") + " (" + group.size() + "):";
                case WARNING -> AnsiOutput.yellow("WARNINGS") + " (" + group.size() + "):";
                case INFO -> "INFO (" + group.size() + "):";
            };
            System.out.println(label);

            for (HealthIssue issue : group) {
                System.out.printf("  [%s] %s%n", issue.code(), issue.description());
                for (String detail : issue.details()) {
                    System.out.println("       " + detail);
                }
                if (issue.fix() != null) {
                    System.out.println("       Fix: " + AnsiOutput.dim(issue.fix()));
                }
                System.out.println();
            }
        }

        if (issues.isEmpty()) {
            System.out.println(AnsiOutput.green("No issues found. Workspace looks healthy!"));
            System.out.println();
        }
    }

    // -------------------------------------------------------------------------
    // Fix-config
    // -------------------------------------------------------------------------

    private Integer runFixConfig(Path workspaceRoot, SynthesisConfig config) throws IOException {
        List<SubWorkspaceConfig> phantoms = findPhantomSubWorkspaces(workspaceRoot, config);

        if (phantoms.isEmpty()) {
            System.out.println();
            System.out.println(AnsiOutput.green(
                    "  No phantom sub-workspace paths found. Config looks clean."));
            System.out.println();
            return 0;
        }

        System.out.println();
        AnsiOutput.printHeader("Config Reconciliation Report");
        System.out.println();
        System.out.printf("  MISMATCHES (%d):%n%n", phantoms.size());
        System.out.printf("  %-52s %-12s %s%n", "Config path", "Status", "Suggested fix");
        System.out.printf("  %-52s %-12s %s%n", "-".repeat(50), "-".repeat(10), "-".repeat(40));

        List<Path> actualDirs = listAllDirectories(workspaceRoot, 6);
        Map<SubWorkspaceConfig, String> suggestions = new LinkedHashMap<>();
        for (SubWorkspaceConfig phantom : phantoms) {
            String suggestion = findBestMatch(phantom.getPath(), actualDirs, workspaceRoot);
            suggestions.put(phantom, suggestion);
            System.out.printf("  %-52s %-12s %s%n",
                    phantom.getPath(),
                    AnsiOutput.red("NOT FOUND"),
                    suggestion != null ? "-> " + suggestion : AnsiOutput.dim("(no match found)"));
        }
        System.out.println();

        if (autoApply) {
            applyRemappings(workspaceRoot, config, suggestions);
            return 0;
        }

        System.out.print("Apply suggested remappings? [y/N/interactive]: ");
        System.out.flush();
        Scanner scanner = new Scanner(System.in);
        String answer = scanner.nextLine().trim().toLowerCase();
        switch (answer) {
            case "y", "yes" -> applyRemappings(workspaceRoot, config, suggestions);
            case "i", "interactive" -> applyInteractive(workspaceRoot, config, suggestions, scanner);
            default -> System.out.println("  Aborted. No changes made.");
        }
        return 0;
    }

    private void applyRemappings(Path workspaceRoot, SynthesisConfig config,
                                  Map<SubWorkspaceConfig, String> suggestions) throws IOException {
        int applied = 0;
        List<SubWorkspaceConfig> toRemove = new ArrayList<>();
        for (var entry : suggestions.entrySet()) {
            if (entry.getValue() != null) {
                entry.getKey().setPath(entry.getValue());
                applied++;
            } else {
                toRemove.add(entry.getKey());
            }
        }
        // Remove unmatched phantoms from the config
        config.getSubWorkspaces().removeAll(toRemove);

        if (applied > 0 || !toRemove.isEmpty()) {
            saveConfig(workspaceRoot, config);
        }
        if (applied > 0) {
            System.out.printf("%n  Applied %d remapping(s).%n", applied);
        }
        if (!toRemove.isEmpty()) {
            System.out.printf("%nRemoved %d phantom sub-workspace entr%s:%n",
                    toRemove.size(), toRemove.size() == 1 ? "y" : "ies");
            for (SubWorkspaceConfig sw : toRemove) {
                System.out.println("  - " + sw.getName());
            }
        }
        if (applied > 0 || !toRemove.isEmpty()) {
            Path configFile = workspaceRoot.resolve(ConfigLoader.ROOT_CONFIG);
            if (!java.nio.file.Files.exists(configFile)) {
                configFile = workspaceRoot.resolve(ConfigLoader.INTERNAL_CONFIG);
            }
            System.out.println("Config updated: " + configFile);
            System.out.println();
        } else {
            System.out.println("  No valid suggestions to apply.");
        }
    }

    private void applyInteractive(Path workspaceRoot, SynthesisConfig config,
                                   Map<SubWorkspaceConfig, String> suggestions,
                                   Scanner scanner) throws IOException {
        int applied = 0;
        for (var entry : suggestions.entrySet()) {
            SubWorkspaceConfig sw = entry.getKey();
            String suggestion = entry.getValue();
            if (suggestion == null) {
                System.out.printf("  [skip] %s — no suggestion available%n", sw.getPath());
                continue;
            }
            System.out.printf("  Remap '%s'%n       -> '%s' ? [y/N]: ", sw.getPath(), suggestion);
            System.out.flush();
            String ans = scanner.nextLine().trim().toLowerCase();
            if (ans.equals("y") || ans.equals("yes")) {
                sw.setPath(suggestion);
                applied++;
            }
        }
        if (applied > 0) {
            saveConfig(workspaceRoot, config);
            System.out.printf("%n  Applied %d remapping(s). Config updated.%n%n", applied);
        } else {
            System.out.println("  No changes applied.");
        }
    }

    // -------------------------------------------------------------------------
    // Static helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /** Returns sub-workspace config entries whose path does not exist as a directory. */
    static List<SubWorkspaceConfig> findPhantomSubWorkspaces(Path workspaceRoot,
                                                              SynthesisConfig config) {
        List<SubWorkspaceConfig> phantoms = new ArrayList<>();
        for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
            Path swPath = workspaceRoot.resolve(sw.getPath());
            if (!Files.isDirectory(swPath)) {
                phantoms.add(sw);
            }
        }
        return phantoms;
    }

    /** Finds build artifact directories (node_modules, bower_components) and stray .class dirs. */
    static List<Path> findBuildArtifacts(Path workspaceRoot) throws IOException {
        List<Path> artifacts = new ArrayList<>();
        Set<String> artifactDirNames = Set.of("node_modules", "bower_components");

        try (Stream<Path> stream = Files.walk(workspaceRoot, 8)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> artifactDirNames.contains(p.getFileName().toString()))
                  .filter(p -> !p.toString().contains("/.synthesis/"))
                  .forEach(artifacts::add);
        }

        // Stray .class files outside of target/ directories
        Set<String> seen = new java.util.HashSet<>();
        try (Stream<Path> stream = Files.walk(workspaceRoot, 8)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".class"))
                  .filter(p -> !p.toString().contains("/target/"))
                  .filter(p -> !p.toString().contains("/.synthesis/"))
                  .map(Path::getParent)
                  .filter(p -> seen.add(p.toString()))
                  .forEach(artifacts::add);
        }

        return artifacts;
    }

    /** Finds directories that contain no files (recursively) up to depth 6. */
    static List<Path> findEmptyDirectories(Path workspaceRoot) throws IOException {
        List<Path> empty = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workspaceRoot, 6)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.equals(workspaceRoot))
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .filter(p -> !p.toString().contains("/.synthesis/"))
                  .filter(p -> {
                      try (Stream<Path> children = Files.list(p)) {
                          return children.findFirst().isEmpty();
                      } catch (IOException e) {
                          return false;
                      }
                  })
                  .forEach(empty::add);
        }
        return empty;
    }

    /** Counts non-hidden, non-config files directly at the workspace root. */
    static int countLooseRootFiles(Path workspaceRoot) throws IOException {
        Set<String> knownConfigs = Set.of(
                "synthesis-config.yaml", ".synthesis", "README.md", "README",
                "ACTIVITY-LOG.md", "CLAUDE.md");
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !knownConfigs.contains(p.getFileName().toString()))
                    .count();
        }
    }

    /** Finds the workspace archive directory if it exists. */
    static Path findArchiveDir(Path workspaceRoot) throws IOException {
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            return stream.filter(Files::isDirectory)
                         .filter(p -> {
                             String name = p.getFileName().toString().toLowerCase();
                             return name.equals("archive") || name.equals("@archive")
                                     || name.startsWith("archive-");
                         })
                         .findFirst().orElse(null);
        }
    }

    /** Calculates health score: 100 minus 15 per ERROR category, 5 per WARNING category. */
    static int calculateScore(List<HealthIssue> issues) {
        long errors = issues.stream()
                .filter(i -> i.severity() == HealthIssue.Severity.ERROR).count();
        long warnings = issues.stream()
                .filter(i -> i.severity() == HealthIssue.Severity.WARNING).count();
        return Math.max(0, 100 - (int) (errors * 15) - (int) (warnings * 5));
    }

    static String scoreGrade(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 75) return "Good";
        if (score >= 60) return "Fair";
        return "Poor";
    }

    // -------------------------------------------------------------------------
    // E002 fix: .synthesisignore management
    // -------------------------------------------------------------------------

    /**
     * Appends a pattern to the {@code .synthesisignore} file, creating it if it
     * does not exist. Does nothing if the pattern is already present.
     *
     * @param ignoreFile path to the {@code .synthesisignore} file
     * @param pattern    the pattern to append (e.g., "node_modules/")
     */
    public static void appendToSynthesisIgnore(Path ignoreFile, String pattern) throws IOException {
        if (Files.exists(ignoreFile)) {
            String content = Files.readString(ignoreFile);
            // Check if the pattern is already present as a standalone line
            boolean alreadyPresent = content.lines()
                    .map(String::trim)
                    .anyMatch(line -> line.equals(pattern));
            if (alreadyPresent) {
                return;
            }
            // Append with a newline if the file does not end with one
            String toAppend = content.endsWith("\n") ? pattern + "\n" : "\n" + pattern + "\n";
            Files.writeString(ignoreFile, content + toAppend);
        } else {
            Files.writeString(ignoreFile, pattern + "\n");
        }
    }

    /**
     * Interactive fix for E002: finds build artifact directories, asks the user
     * to confirm each unique pattern, and appends confirmed patterns to
     * {@code .synthesisignore}.
     *
     * <p>Reads from {@link System#in} and writes to {@link System#out}.
     *
     * @param workspaceRoot the workspace root directory
     */
    public void runFixE002(Path workspaceRoot) throws IOException {
        List<Path> artifacts = findBuildArtifacts(workspaceRoot);
        if (artifacts.isEmpty()) {
            System.out.println("No build artifacts found.");
            return;
        }

        // Collect unique directory-name patterns (e.g., "node_modules/")
        java.util.LinkedHashSet<String> patterns = new java.util.LinkedHashSet<>();
        for (Path artifact : artifacts) {
            String dirName = artifact.getFileName().toString();
            patterns.add(dirName + "/");
        }

        Path ignoreFile = workspaceRoot.resolve(".synthesisignore");
        Scanner scanner = new Scanner(System.in);

        for (String pattern : patterns) {
            System.out.printf("Add '%s' to .synthesisignore? [y/N]: ", pattern);
            System.out.flush();
            String answer = scanner.nextLine().trim().toLowerCase();
            if (answer.equals("y") || answer.equals("yes")) {
                appendToSynthesisIgnore(ignoreFile, pattern);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fix-config helpers
    // -------------------------------------------------------------------------

    /** Lists all non-hidden directories under root up to maxDepth, excluding .synthesis and artifacts. */
    static List<Path> listAllDirectories(Path root, int maxDepth) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.equals(root))
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .filter(p -> !p.toString().contains("/node_modules/"))
                  .filter(p -> !p.toString().contains("/.synthesis/"))
                  .forEach(dirs::add);
        }
        return dirs;
    }

    /**
     * Fuzzy-matches a phantom config path against actual filesystem directories.
     *
     * <p>Uses the last non-{@code @} segment of the config path as the entity name,
     * strips common prefixes like {@code opportunity-} from candidate names, and
     * returns the best match if its score meets the minimum threshold.
     *
     * @return the workspace-relative path string of the best match, or {@code null}
     */
    static String findBestMatch(String phantomPath, List<Path> candidates, Path workspaceRoot) {
        // Extract entity name from path — last segment that isn't an @-category
        String[] parts = phantomPath.split("/");
        String entityName = null;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].startsWith("@") && !parts[i].isBlank()) {
                entityName = parts[i];
                break;
            }
        }
        if (entityName == null) return null;

        String entityLower = entityName.toLowerCase();
        String bestMatch = null;
        int bestScore = 0;

        for (Path candidate : candidates) {
            String candName = candidate.getFileName().toString().toLowerCase();
            // Normalize: strip common prefixes that categorize but don't identify
            String candNorm = candName.replaceAll(
                    "^(opportunity-|client-|@active-|@past-|@opportunities-)", "");

            int score = 0;
            if (candNorm.equals(entityLower))                         score = 100;
            else if (candName.contains(entityLower)
                    || entityLower.contains(candNorm))                score = 80;
            else if (levenshtein(candNorm, entityLower) <= 2)         score = 70;
            else if (candNorm.startsWith(entityLower)
                    || entityLower.startsWith(candNorm))              score = 60;

            if (score > bestScore) {
                bestScore = score;
                bestMatch = workspaceRoot.relativize(candidate).toString();
            }
        }

        return bestScore >= 60 ? bestMatch : null;
    }

    /** Standard Levenshtein distance (edit distance) between two strings. */
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

    /**
     * Persists the modified {@link SynthesisConfig} back to the workspace config file.
     *
     * <p>Loads the raw YAML, updates {@code path} values for each sub-workspace by name,
     * and writes the file back with block-style YAML formatting.
     */
    @SuppressWarnings("unchecked")
    static void saveConfig(Path workspaceRoot, SynthesisConfig config) throws IOException {
        Path configFile = workspaceRoot.resolve(ConfigLoader.ROOT_CONFIG);
        if (!Files.exists(configFile)) {
            configFile = workspaceRoot.resolve(ConfigLoader.INTERNAL_CONFIG);
        }

        Yaml yaml = new Yaml();
        Map<String, Object> raw;
        try (var in = Files.newInputStream(configFile)) {
            raw = yaml.load(in);
        }
        if (raw == null) raw = new LinkedHashMap<>();

        // Update the path of each sub-workspace entry in the raw map
        Object subWsObj = raw.get("subWorkspaces");
        if (subWsObj instanceof List<?> rawListRaw) {
            List<Object> rawList = (List<Object>) rawListRaw;
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> swMap) {
                    String name = (String) ((Map<?, ?>) swMap).get("name");
                    for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
                        if (sw.getName().equals(name)) {
                            ((Map<String, Object>) swMap).put("path", sw.getPath());
                        }
                    }
                }
            }
            // Remove entries whose name is no longer in the active sub-workspace set
            Set<String> activeNames = config.getSubWorkspaces().stream()
                    .map(SubWorkspaceConfig::getName)
                    .collect(java.util.stream.Collectors.toSet());
            rawList.removeIf(item -> {
                if (item instanceof Map<?, ?> swMap) {
                    Object name = swMap.get("name");
                    return name != null && !activeNames.contains(name.toString());
                }
                return false;
            });
        }

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        Files.writeString(configFile, new Yaml(opts).dump(raw));
    }

    // -------------------------------------------------------------------------
    // Filesystem utilities
    // -------------------------------------------------------------------------

    /** Returns total size in bytes of all regular files under {@code path}. */
    static long dirSize(Path path) throws IOException {
        if (!Files.exists(path)) return 0L;
        if (Files.isRegularFile(path)) return Files.size(path);
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                         .mapToLong(p -> {
                             try { return Files.size(p); } catch (IOException e) { return 0L; }
                         }).sum();
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1_024L) return bytes + " B";
        if (bytes < 1_024L * 1_024) return (bytes / 1_024) + " KB";
        if (bytes < 1_024L * 1_024 * 1_024) return (bytes / (1_024L * 1_024)) + " MB";
        return String.format("%.1f GB", bytes / (1_024.0 * 1_024 * 1_024));
    }
}
