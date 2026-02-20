package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.RoutingRule;
import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code synthesis sweep} — identify and archive stale root-level files.
 *
 * <p>Categorises root-level files by heuristic:
 * <ul>
 *   <li><b>EPHEMERAL</b>: session-specific name patterns older than {@code --days}
 *   <li><b>SCRIPTS</b>: shell scripts older than {@code --days}
 *   <li><b>ARTIFACTS</b>: archives (zip, tar.gz, 7z) at root level
 *   <li><b>COMPLETED REPORTS</b>: dated file names (year-prefixed or year-suffixed)
 * </ul>
 *
 * <p>Files are first routed using the workspace routing rules (same pipeline as
 * {@code staging route}). Unmatched files fall back to
 * {@code archive/swept-{date}/} inside the workspace.
 * Use {@code --dry-run} to preview without moving.
 * Use {@code --archive-only} to skip routing and send all files to archive.
 */
@Command(
        name = "sweep",
        description = "Identify and archive stale root-level files (scripts, ephemeral docs, artifacts)",
        mixinStandardHelpOptions = true
)
public class SweepCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--days"},
            description = "Age threshold in days for ephemeral and script files (default: 30)",
            defaultValue = "30"
    )
    private int days;

    @Option(
            names = {"--dry-run"},
            description = "Show candidates without moving anything"
    )
    private boolean dryRun;

    @Option(
            names = {"--yes", "-y"},
            description = "Move all candidates without prompting"
    )
    private boolean autoConfirm;

    @Option(
            names = {"--archive-only"},
            description = "Skip routing intelligence and send all files directly to archive (legacy behaviour)"
    )
    private boolean archiveOnly;

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    public enum Category {
        EPHEMERAL("EPHEMERAL (session-specific names)"),
        SCRIPTS("SCRIPTS (shell scripts)"),
        ARTIFACTS("ARTIFACTS (archives and exports)"),
        COMPLETED_REPORTS("COMPLETED REPORTS (dated names)");

        final String label;
        Category(String label) { this.label = label; }
    }

    public record SweepCandidate(Path path, Category category, String reason, long agedays) {}

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    // Session-planning name patterns
    private static final Set<String> EPHEMERAL_PREFIXES = Set.of(
            "TONIGHT-", "TONIGHT_", "READY-TO-", "PRE-FLIGHT",
            "PROCESSING-", "PROCESS-ALL-"
    );
    private static final Set<String> EPHEMERAL_SUFFIXES = Set.of(
            "-COMPLETE.md", "-COMPLETE.MD", "-PLAN.md", "-PLAN.MD",
            "-README.md", "-README.MD", "-STATUS.md", "-STATUS.MD",
            "-COMPLETE", "-PLAN", "-READY"
    );

    // Dated report patterns: 2024-SOMETHING or SOMETHING-2024 or SOMETHING-2025-COMPLETE
    private static final Pattern DATED_PATTERN = Pattern.compile(
            "(?i)(^\\d{4}[-_].+)|(.+[-_]\\d{4}[-_].+)|(.+[-_]\\d{4}$)");

    // Archive file extensions
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            ".zip", ".tar.gz", ".tar.bz2", ".tar.xz", ".7z", ".rar", ".gz", ".tgz"
    );

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();

        System.out.println();
        AnsiOutput.printHeader("Root-Level Sweep: " + workspaceRoot);
        System.out.println();

        List<SweepCandidate> candidates = findCandidates(workspaceRoot, days);

        if (candidates.isEmpty()) {
            System.out.println(AnsiOutput.green("  No stale root-level files found."));
            System.out.println();
            return 0;
        }

        // Archive fallback destination
        Path archiveDest = workspaceRoot.resolve("archive")
                .resolve("swept-" + LocalDate.now());

        // Load routing rules (unless --archive-only)
        List<RoutingRule> rules = List.of();
        List<List<PathMatcher>> ruleMatchers = List.of();
        if (!archiveOnly) {
            try {
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);
                List<RoutingRule> loaded = config.getRouting().getRules();
                List<List<PathMatcher>> matchers = new ArrayList<>();
                for (RoutingRule rule : loaded) {
                    List<PathMatcher> pm = new ArrayList<>();
                    for (String pattern : rule.getPatterns()) {
                        pm.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                    }
                    matchers.add(pm);
                }
                rules = loaded;
                ruleMatchers = matchers;
            } catch (Exception e) {
                // No config or routing section — fall back to archive-only behaviour
            }
        }

        // Create identity router for the second routing pass (skipped when --archive-only)
        DirectoryIdentityRouter identityRouter = archiveOnly ? null
                : new DirectoryIdentityRouter(workspaceRoot, null);

        // Compute per-file destinations
        Map<SweepCandidate, Path> destinations =
                resolveDestinations(candidates, workspaceRoot, rules, ruleMatchers, archiveDest, identityRouter);

        // Group and print
        printCandidates(candidates, workspaceRoot, destinations, archiveDest);

        if (dryRun) {
            System.out.println(AnsiOutput.dim("  Dry run — no changes made. Remove --dry-run to apply."));
            System.out.println();
            return 0;
        }

        if (!autoConfirm) {
            System.out.printf("Move %d file%s? [y/N/select]: ",
                    candidates.size(), candidates.size() == 1 ? "" : "s");
            System.out.flush();
            Scanner scanner = new Scanner(System.in);
            String ans = scanner.nextLine().trim().toLowerCase();
            switch (ans) {
                case "y", "yes" -> moveFiles(candidates, destinations);
                case "select" -> selectiveMove(candidates, destinations, scanner);
                default -> { System.out.println("  Aborted. No changes made."); return 0; }
            }
        } else {
            moveFiles(candidates, destinations);
        }

        return 0;
    }

    private void printCandidates(List<SweepCandidate> candidates, Path workspaceRoot,
                                  Map<SweepCandidate, Path> destinations, Path archiveDest) {
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        System.out.printf("  Candidates (%d file%s):%n%n",
                candidates.size(), candidates.size() == 1 ? "" : "s");

        // Split into ROUTABLE and ARCHIVE groups (preserving candidate order within each group)
        List<SweepCandidate> routable = new ArrayList<>();
        List<SweepCandidate> archive = new ArrayList<>();
        for (SweepCandidate c : candidates) {
            Path dest = destinations.get(c);
            if (dest != null && !dest.equals(archiveDest)) {
                routable.add(c);
            } else {
                archive.add(c);
            }
        }

        int index = 1;
        if (!routable.isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("ROUTABLE") + AnsiOutput.dim(" (destination found):"));
            for (SweepCandidate c : routable) {
                Path dest = destinations.get(c);
                String relDest = workspaceRoot.relativize(dest).toString();
                Instant modified = getModifiedTime(c.path());
                String dateStr = modified != null
                        ? dateFmt.format(modified.atZone(ZoneId.systemDefault()).toLocalDate())
                        : "unknown";
                System.out.printf("    [%2d] %-48s  %s → %s%n",
                        index++,
                        c.path().getFileName(),
                        dateStr,
                        AnsiOutput.green(relDest));
            }
            System.out.println();
        }

        if (!archive.isEmpty()) {
            String archiveRelPath = workspaceRoot.relativize(archiveDest).toString();
            System.out.println("  " + AnsiOutput.bold("ARCHIVE") + AnsiOutput.dim(" (no meaningful destination):"));
            for (SweepCandidate c : archive) {
                Instant modified = getModifiedTime(c.path());
                String dateStr = modified != null
                        ? dateFmt.format(modified.atZone(ZoneId.systemDefault()).toLocalDate())
                        : "unknown";
                String ageStr = c.agedays() > 0 ? "  (" + c.agedays() + " days ago)" : "";
                System.out.printf("    [%2d] %-48s  %s → %s%s%n",
                        index++,
                        c.path().getFileName(),
                        dateStr,
                        AnsiOutput.dim(archiveRelPath),
                        ageStr);
            }
            System.out.println();
        }
    }

    private void moveFiles(List<SweepCandidate> candidates,
                           Map<SweepCandidate, Path> destinations) throws IOException {
        int moved = 0;
        for (SweepCandidate c : candidates) {
            Path destination = destinations.get(c);
            if (destination == null) continue;
            Files.createDirectories(destination);
            Path target = destination.resolve(c.path().getFileName());
            try {
                Files.move(c.path(), target);
                moved++;
            } catch (IOException e) {
                System.err.printf("  Could not move %s: %s%n", c.path().getFileName(), e.getMessage());
            }
        }
        System.out.printf("%n  Moved %d file%s%n%n", moved, moved == 1 ? "" : "s");
    }

    private void selectiveMove(List<SweepCandidate> candidates,
                                Map<SweepCandidate, Path> destinations,
                                Scanner scanner) throws IOException {
        System.out.print("  Enter numbers to move (e.g. 1,3,5 or 1-5): ");
        System.out.flush();
        String input = scanner.nextLine().trim();
        Set<Integer> selected = parseSelection(input, candidates.size());

        List<SweepCandidate> toMove = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (selected.contains(i + 1)) toMove.add(candidates.get(i));
        }

        if (toMove.isEmpty()) {
            System.out.println("  No files selected.");
        } else {
            moveFiles(toMove, destinations);
        }
    }

    // -------------------------------------------------------------------------
    // Routing helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Resolves the destination directory for each candidate.
     * Files matching routing rules go to the rule's destination (relative to workspace root
     * if not absolute); unmatched files fall back to {@code archiveFallback}.
     *
     * <p>Backward-compatible overload (no identity router): used by existing tests.
     */
    static Map<SweepCandidate, Path> resolveDestinations(
            List<SweepCandidate> candidates,
            Path workspaceRoot,
            List<RoutingRule> rules,
            List<List<PathMatcher>> ruleMatchers,
            Path archiveFallback) {
        return resolveDestinations(candidates, workspaceRoot, rules, ruleMatchers, archiveFallback, null);
    }

    /**
     * Resolves the destination directory for each candidate, using directory identity routing
     * as a fallback between config rules and the archive.
     *
     * @param identityRouter optional router (null = skip identity routing, as in --archive-only)
     */
    static Map<SweepCandidate, Path> resolveDestinations(
            List<SweepCandidate> candidates,
            Path workspaceRoot,
            List<RoutingRule> rules,
            List<List<PathMatcher>> ruleMatchers,
            Path archiveFallback,
            DirectoryIdentityRouter identityRouter) {
        Map<SweepCandidate, Path> result = new LinkedHashMap<>();
        for (SweepCandidate c : candidates) {
            result.put(c, resolveDestination(c.path(), workspaceRoot, rules, ruleMatchers, archiveFallback, identityRouter));
        }
        return result;
    }

    /**
     * Determines the destination directory for a single file.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>Glob patterns from each routing rule (filename only)</li>
     *   <li>Companion keyword matching ({@code <file>.synthesis.md}) per rule</li>
     *   <li>Archive fallback if no rule matches</li>
     * </ol>
     *
     * <p>Backward-compatible overload (no identity router): used by existing tests.
     */
    static Path resolveDestination(Path file, Path workspaceRoot,
                                    List<RoutingRule> rules,
                                    List<List<PathMatcher>> ruleMatchers,
                                    Path archiveFallback) {
        return resolveDestination(file, workspaceRoot, rules, ruleMatchers, archiveFallback, null);
    }

    /**
     * Determines the destination directory for a single file.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>Glob patterns from each routing rule (filename only)</li>
     *   <li>Companion keyword matching ({@code <file>.synthesis.md}) per rule</li>
     *   <li>Directory identity routing (if {@code identityRouter} is non-null and a
     *       non-ambiguous candidate scores ≥ 0.5)</li>
     *   <li>Archive fallback if nothing matches</li>
     * </ol>
     *
     * @param identityRouter optional router (null = skip identity routing)
     */
    static Path resolveDestination(Path file, Path workspaceRoot,
                                    List<RoutingRule> rules,
                                    List<List<PathMatcher>> ruleMatchers,
                                    Path archiveFallback,
                                    DirectoryIdentityRouter identityRouter) {
        if (rules.isEmpty() && identityRouter == null) return archiveFallback;
        Path basename = file.getFileName();
        for (int i = 0; i < rules.size(); i++) {
            RoutingRule rule = rules.get(i);
            // Pass 1a: filename glob patterns
            for (PathMatcher matcher : ruleMatchers.get(i)) {
                if (matcher.matches(basename)) {
                    return toAbsoluteDest(rule.getDestination(), workspaceRoot);
                }
            }
            // Pass 1b: companion content keywords
            if (rule.hasKeywords()) {
                Path companionPath = Path.of(file.toString() + ".synthesis.md");
                if (StagingCommand.companionMatchesKeywords(companionPath, rule.getKeywords())) {
                    return toAbsoluteDest(rule.getDestination(), workspaceRoot);
                }
            }
        }
        // Pass 2: directory identity routing (config rules take precedence)
        if (identityRouter != null) {
            Optional<DirectoryIdentityRouter.RouteResult> routed = identityRouter.route(file, 0.5);
            if (routed.isPresent() && !routed.get().ambiguous()) {
                return routed.get().directory();
            }
        }
        return archiveFallback;
    }

    private static Path toAbsoluteDest(String destination, Path workspaceRoot) {
        Path dest = Path.of(destination);
        return dest.isAbsolute() ? dest : workspaceRoot.resolve(dest);
    }

    // -------------------------------------------------------------------------
    // Static helpers (package-visible for tests)
    // -------------------------------------------------------------------------

    /**
     * Scans the workspace root for sweep candidates.
     * Only looks at direct children (non-recursive) that are regular files.
     */
    static List<SweepCandidate> findCandidates(Path workspaceRoot, int ageDays) throws IOException {
        List<SweepCandidate> results = new ArrayList<>();
        Instant cutoff = Instant.now().minus(ageDays, ChronoUnit.DAYS);

        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .forEach(p -> {
                      SweepCandidate c = classify(p, cutoff);
                      if (c != null) results.add(c);
                  });
        }

        // Sort by category order, then by filename
        results.sort((a, b) -> {
            int catCmp = a.category().ordinal() - b.category().ordinal();
            return catCmp != 0 ? catCmp : a.path().getFileName().toString()
                    .compareTo(b.path().getFileName().toString());
        });
        return results;
    }

    /**
     * Classifies a single file as a sweep candidate, or returns {@code null} if it
     * should not be swept.
     */
    static SweepCandidate classify(Path file, Instant cutoff) {
        String name = file.getFileName().toString();
        String nameLower = name.toLowerCase();
        Instant modified = getModifiedTime(file);
        long agedays = modified != null
                ? ChronoUnit.DAYS.between(modified, Instant.now()) : 0;
        boolean old = modified == null || modified.isBefore(cutoff);

        // ARTIFACTS: archives at root — always sweep regardless of age
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (nameLower.endsWith(ext)) {
                return new SweepCandidate(file, Category.ARTIFACTS, "archive file", agedays);
            }
        }

        // SCRIPTS: shell scripts — sweep if old
        if (nameLower.endsWith(".sh") || nameLower.endsWith(".bash")) {
            if (old) {
                return new SweepCandidate(file, Category.SCRIPTS,
                        "shell script, " + agedays + "d old", agedays);
            }
            return null;
        }

        // EPHEMERAL: session-planning name patterns — sweep if old
        if (old && isEphemeralName(name)) {
            return new SweepCandidate(file, Category.EPHEMERAL,
                    "ephemeral name pattern", agedays);
        }

        // COMPLETED REPORTS: dated name patterns — sweep if old
        if (old && DATED_PATTERN.matcher(name).matches()) {
            return new SweepCandidate(file, Category.COMPLETED_REPORTS,
                    "dated file name", agedays);
        }

        return null;
    }

    static boolean isEphemeralName(String name) {
        String upper = name.toUpperCase();
        for (String prefix : EPHEMERAL_PREFIXES) {
            if (upper.startsWith(prefix)) return true;
        }
        for (String suffix : EPHEMERAL_SUFFIXES) {
            if (upper.endsWith(suffix.toUpperCase())) return true;
        }
        return false;
    }

    static Instant getModifiedTime(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            // Prefer creation time if available and earlier than modified time
            Instant created = attrs.creationTime().toInstant();
            Instant modified = attrs.lastModifiedTime().toInstant();
            return created.isBefore(modified) ? created : modified;
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses a selection string like "1,3,5" or "1-5" or "2,4-6" into a set of indices. */
    static Set<Integer> parseSelection(String input, int max) {
        Set<Integer> selected = new java.util.LinkedHashSet<>();
        for (String token : input.split(",")) {
            token = token.trim();
            if (token.contains("-")) {
                String[] bounds = token.split("-", 2);
                try {
                    int lo = Integer.parseInt(bounds[0].trim());
                    int hi = Integer.parseInt(bounds[1].trim());
                    for (int i = Math.max(1, lo); i <= Math.min(max, hi); i++) {
                        selected.add(i);
                    }
                } catch (NumberFormatException ignored) {}
            } else {
                try {
                    int n = Integer.parseInt(token);
                    if (n >= 1 && n <= max) selected.add(n);
                } catch (NumberFormatException ignored) {}
            }
        }
        return selected;
    }
}
