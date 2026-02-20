package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.util.AnsiOutput;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis ttl} — manage time-to-live rules for ephemeral files.
 *
 * <p>Lets users tag files or glob patterns with an expiry period.
 * When files expire, {@code synthesis ttl check} reports them and
 * optionally archives them to {@code archive/expired-{date}/}.
 *
 * <p>Rules are stored in {@code .synthesis/ttl-rules.yaml}.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code set <pattern> --days N} — add or update a TTL rule
 *   <li>{@code list} — show all rules with expiry status
 *   <li>{@code check [--archive]} — find expired files matching rules
 * </ul>
 */
@Command(
        name = "ttl",
        description = "Manage time-to-live rules for ephemeral files",
        mixinStandardHelpOptions = true,
        subcommands = {TtlCommand.SetCommand.class, TtlCommand.ListCommand.class, TtlCommand.CheckCommand.class}
)
public class TtlCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        System.err.println("Usage: synthesis ttl <set|list|check> [options]");
        return 0;
    }

    // =========================================================================
    // TtlRule record
    // =========================================================================

    /**
     * A single TTL rule: a glob pattern or exact filename with an expiry period.
     */
    record TtlRule(String pattern, int days, LocalDate createdAt) {

        /** The date on which files matching this rule expire. */
        LocalDate expiresOn() {
            return createdAt.plusDays(days);
        }

        /** Returns {@code true} if the rule has expired (today is after the expiry date). */
        boolean isExpired() {
            return LocalDate.now().isAfter(expiresOn());
        }

        /**
         * Days until expiry (positive = future, negative = past).
         * Returns 0 on the expiry date itself.
         */
        long daysUntilExpiry() {
            return ChronoUnit.DAYS.between(LocalDate.now(), expiresOn());
        }
    }

    // =========================================================================
    // set subcommand
    // =========================================================================

    @Command(
            name = "set",
            description = "Set a TTL rule for a file or glob pattern",
            mixinStandardHelpOptions = true
    )
    static class SetCommand implements Callable<Integer> {

        @ParentCommand
        private TtlCommand parent;

        @Parameters(index = "0", description = "File name or glob pattern (e.g. \"TONIGHT-*.md\")")
        private String pattern;

        @Option(names = {"--days"}, required = true, description = "Number of days until expiry")
        private int days;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = parent.parent.getWorkspaceRoot();

            List<TtlRule> rules = loadRules(workspaceRoot);
            rules = upsertRule(rules, pattern, days);
            saveRules(workspaceRoot, rules);

            LocalDate expiresOn = LocalDate.now().plusDays(days);
            System.out.println();
            System.out.printf("  Set TTL: %d days for pattern %s%n", days, pattern);
            System.out.printf("  Expires: %s%n", expiresOn);
            System.out.println();

            return 0;
        }
    }

    // =========================================================================
    // list subcommand
    // =========================================================================

    @Command(
            name = "list",
            description = "List all TTL rules with expiry status",
            mixinStandardHelpOptions = true
    )
    static class ListCommand implements Callable<Integer> {

        @ParentCommand
        private TtlCommand parent;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = parent.parent.getWorkspaceRoot();

            List<TtlRule> rules = loadRules(workspaceRoot);

            if (rules.isEmpty()) {
                System.out.println();
                System.out.println("  No TTL rules defined. Use 'synthesis ttl set <pattern> --days N' to add one.");
                System.out.println();
                return 0;
            }

            System.out.println();
            System.out.printf("  %-25s %-8s %-12s %-14s %s%n",
                    "Pattern/File", "TTL", "Set On", "Expires", "Status");

            for (TtlRule rule : rules) {
                String status;
                if (rule.isExpired()) {
                    long daysAgo = -rule.daysUntilExpiry();
                    status = AnsiOutput.red("EXPIRED (" + daysAgo + " day" + (daysAgo == 1 ? "" : "s") + " ago)");
                } else {
                    long daysLeft = rule.daysUntilExpiry();
                    status = AnsiOutput.green("Active (" + daysLeft + " day" + (daysLeft == 1 ? "" : "s") + " left)");
                }

                System.out.printf("  %-25s %-8s %-12s %-14s %s%n",
                        rule.pattern(),
                        rule.days() + " days",
                        rule.createdAt(),
                        rule.expiresOn(),
                        status);
            }
            System.out.println();

            return 0;
        }
    }

    // =========================================================================
    // check subcommand
    // =========================================================================

    @Command(
            name = "check",
            description = "Report expired files matching TTL rules",
            mixinStandardHelpOptions = true
    )
    static class CheckCommand implements Callable<Integer> {

        @ParentCommand
        private TtlCommand parent;

        @Option(names = {"--archive"}, description = "Move expired files to archive/expired-{date}/")
        private boolean archive;

        @Option(names = {"--yes", "-y"}, description = "Archive without prompting")
        private boolean autoYes;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = parent.parent.getWorkspaceRoot();

            List<TtlRule> rules = loadRules(workspaceRoot);
            if (rules.isEmpty()) {
                System.out.println();
                System.out.println("  No TTL rules defined.");
                System.out.println();
                return 0;
            }

            List<Path> expiredFiles = findExpiredFiles(workspaceRoot, rules);

            if (expiredFiles.isEmpty()) {
                System.out.println();
                System.out.println(AnsiOutput.green("  No expired files found."));
                System.out.println();
                return 0;
            }

            // Build a map from expired file -> matching rule for display
            Map<Path, TtlRule> matchMap = new LinkedHashMap<>();
            List<TtlRule> expiredRules = rules.stream().filter(TtlRule::isExpired).toList();
            for (Path file : expiredFiles) {
                for (TtlRule rule : expiredRules) {
                    if (matchesPattern(file, rule.pattern())) {
                        matchMap.put(file, rule);
                        break;
                    }
                }
            }

            System.out.println();
            System.out.printf("  Expired files (%d):%n", expiredFiles.size());
            for (Map.Entry<Path, TtlRule> entry : matchMap.entrySet()) {
                String fileName = entry.getKey().getFileName().toString();
                TtlRule rule = entry.getValue();
                long daysAgo = -rule.daysUntilExpiry();
                System.out.printf("    %-35s (matched: %s, expired %d day%s ago)%n",
                        fileName, rule.pattern(), daysAgo, daysAgo == 1 ? "" : "s");
            }
            System.out.println();

            if (archive) {
                if (!autoYes) {
                    System.out.printf("  Move %d file%s to archive? [y/N]: ",
                            expiredFiles.size(), expiredFiles.size() == 1 ? "" : "s");
                    System.out.flush();
                    Scanner scanner = new Scanner(System.in);
                    String ans = scanner.nextLine().trim().toLowerCase();
                    if (!ans.equals("y") && !ans.equals("yes")) {
                        System.out.println("  Aborted. No changes made.");
                        return 0;
                    }
                }

                Path destination = workspaceRoot.resolve("archive")
                        .resolve("expired-" + LocalDate.now());
                Files.createDirectories(destination);

                int moved = 0;
                for (Path file : expiredFiles) {
                    Path target = destination.resolve(file.getFileName());
                    try {
                        Files.move(file, target);
                        moved++;
                    } catch (IOException e) {
                        System.err.printf("  Could not move %s: %s%n",
                                file.getFileName(), e.getMessage());
                    }
                }
                System.out.printf("  Moved %d file%s to %s%n%n",
                        moved, moved == 1 ? "" : "s", destination);
            } else {
                System.out.println("  Run with --archive to move them to archive/expired-{date}/");
                System.out.println();
            }

            return 0;
        }
    }

    // =========================================================================
    // Static helpers (package-visible for tests)
    // =========================================================================

    /** YAML file path relative to workspace root. */
    static final String TTL_RULES_FILE = ".synthesis/ttl-rules.yaml";

    /**
     * Loads TTL rules from {@code .synthesis/ttl-rules.yaml}.
     *
     * @return list of rules, or an empty list if the file does not exist
     */
    @SuppressWarnings("unchecked")
    static List<TtlRule> loadRules(Path workspaceRoot) throws IOException {
        Path rulesFile = workspaceRoot.resolve(TTL_RULES_FILE);
        if (!Files.exists(rulesFile)) {
            return new ArrayList<>();
        }

        Yaml yaml = new Yaml();
        Map<String, Object> raw;
        try (InputStream in = Files.newInputStream(rulesFile)) {
            raw = yaml.load(in);
        }
        if (raw == null || !raw.containsKey("rules")) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> rulesList = (List<Map<String, Object>>) raw.get("rules");
        if (rulesList == null) {
            return new ArrayList<>();
        }

        List<TtlRule> rules = new ArrayList<>();
        for (Map<String, Object> entry : rulesList) {
            String pattern = (String) entry.get("pattern");
            int days = ((Number) entry.get("days")).intValue();
            LocalDate createdAt = LocalDate.parse((String) entry.get("createdAt"),
                    DateTimeFormatter.ISO_LOCAL_DATE);
            rules.add(new TtlRule(pattern, days, createdAt));
        }
        return rules;
    }

    /**
     * Saves TTL rules to {@code .synthesis/ttl-rules.yaml}.
     * Creates the {@code .synthesis/} directory if it does not exist.
     */
    static void saveRules(Path workspaceRoot, List<TtlRule> rules) throws IOException {
        Path rulesFile = workspaceRoot.resolve(TTL_RULES_FILE);
        Files.createDirectories(rulesFile.getParent());

        List<Map<String, Object>> rulesList = new ArrayList<>();
        for (TtlRule rule : rules) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pattern", rule.pattern());
            entry.put("days", rule.days());
            entry.put("createdAt", rule.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE));
            rulesList.add(entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rules", rulesList);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);

        Files.writeString(rulesFile, new Yaml(opts).dump(root));
    }

    /**
     * Finds files at the workspace root that match any expired TTL rule.
     *
     * <p>Only checks direct children of the workspace root (not recursive).
     * Uses {@link PathMatcher} for glob patterns; exact filename match for non-glob patterns.
     *
     * @return list of matching file paths
     */
    static List<Path> findExpiredFiles(Path workspaceRoot, List<TtlRule> rules) throws IOException {
        List<TtlRule> expiredRules = rules.stream()
                .filter(TtlRule::isExpired)
                .toList();

        if (expiredRules.isEmpty()) {
            return List.of();
        }

        List<Path> matched = new ArrayList<>();

        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            stream.filter(Files::isRegularFile)
                  .forEach(file -> {
                      for (TtlRule rule : expiredRules) {
                          if (matchesPattern(file, rule.pattern())) {
                              matched.add(file);
                              break; // avoid duplicates if multiple rules match
                          }
                      }
                  });
        }

        // Sort for deterministic output
        matched.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return matched;
    }

    /**
     * Adds a new rule or updates an existing rule with the same pattern.
     *
     * @return the updated list (new list instance)
     */
    static List<TtlRule> upsertRule(List<TtlRule> rules, String pattern, int days) {
        List<TtlRule> result = new ArrayList<>();
        boolean found = false;
        for (TtlRule existing : rules) {
            if (existing.pattern().equals(pattern)) {
                result.add(new TtlRule(pattern, days, LocalDate.now()));
                found = true;
            } else {
                result.add(existing);
            }
        }
        if (!found) {
            result.add(new TtlRule(pattern, days, LocalDate.now()));
        }
        return result;
    }

    /**
     * Checks whether a file matches a pattern.
     * If the pattern contains glob characters ({@code *}, {@code ?}, {@code [}, {@code {}),
     * uses a {@link PathMatcher}; otherwise does an exact filename match.
     */
    static boolean matchesPattern(Path file, String pattern) {
        String fileName = file.getFileName().toString();
        if (isGlob(pattern)) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(file.getFileName());
        } else {
            return fileName.equals(pattern);
        }
    }

    /** Returns {@code true} if the pattern contains glob metacharacters. */
    static boolean isGlob(String pattern) {
        return pattern.contains("*") || pattern.contains("?")
                || pattern.contains("[") || pattern.contains("{");
    }
}
