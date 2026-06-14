package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.analyzer.PresentationExtractor;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.RoutingRule;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;
import io.exoreaction.synthesis.enrichment.EnrichmentLevel;
import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import io.exoreaction.synthesis.org.DirectoryScorer;
import io.exoreaction.synthesis.org.DownloadsClassifier;
import io.exoreaction.synthesis.org.OrganizationRegistry;
import io.exoreaction.synthesis.org.RoutingHints;
import io.exoreaction.synthesis.search.WorkspaceDiscoveryConfig;
import io.exoreaction.synthesis.staging.StagingManager;
import io.exoreaction.synthesis.staging.StagingManager.StagedFile;
import io.exoreaction.synthesis.staging.StagingManager.StagingSummary;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages the staging sub-workspace lifecycle: list, promote, expire, and ingest files.
 *
 * <p>Staging sub-workspaces are temporary holding areas for incoming files
 * that need to be classified, reviewed, and promoted to permanent locations.
 *
 * <p>Usage:
 * <pre>
 *   synthesis staging list                         # List staged files
 *   synthesis staging list --status pending        # Filter by status
 *   synthesis staging promote &lt;file&gt; --to &lt;sub-workspace&gt;  # Promote a file
 *   synthesis staging ingest                       # Ingest new files in staging areas
 *   synthesis staging expire                       # Process expired files
 *   synthesis staging stats                        # Show staging statistics
 * </pre>
 *
 * @since v1.4.0
 */
@Command(
        name = "staging",
        description = "Manage staging sub-workspace files (ingest, promote, route, rename, expire)",
        mixinStandardHelpOptions = true,
        subcommands = {
                StagingCommand.ListSub.class,
                StagingCommand.PromoteSub.class,
                StagingCommand.RouteSub.class,
                StagingCommand.ResolveSub.class,
                StagingCommand.HintsSub.class,
                StagingCommand.RenameSub.class,
                StagingCommand.IngestSub.class,
                StagingCommand.ExpireSub.class,
                StagingCommand.StatsSub.class
        }
)
public class StagingCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        // No subcommand given -- show help
        System.out.println("  Use 'synthesis staging <subcommand>' for staging operations.");
        System.out.println();
        System.out.println("  Subcommands:");
        System.out.println("    list      List staged files");
        System.out.println("    promote   Promote a file to a permanent sub-workspace");
        System.out.println("    route     Route files to permanent destinations using config rules");
        System.out.println("    resolve   Route a staged file to a specific directory");
        System.out.println("    hints     List and manage routing hints");
        System.out.println("    rename    Rename files to descriptive names using companion AI descriptions");
        System.out.println("    ingest    Scan staging areas and register new files");
        System.out.println("    expire    Process expired files");
        System.out.println("    stats     Show staging statistics");
        System.out.println();
        return 0;
    }

    // -----------------------------------------------------------------------
    // Subcommand: list
    // -----------------------------------------------------------------------

    /**
     * Lists files in staging sub-workspaces.
     */
    @Command(name = "list", description = "List staged files",
            mixinStandardHelpOptions = true)
    static class ListSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--status"}, description = "Filter by status: pending, promoted, expired")
        private String statusFilter;

        @Option(names = {"-v", "--verbose"}, description = "Show detailed information",
                defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled. Add 'staging: { enabled: true }'"
                            + " to your config.yaml.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                List<StagedFile> files = staging.list(statusFilter).stream()
                        .filter(f -> !f.relativePath().startsWith(".synthesis/"))
                        .toList();

                if (files.isEmpty()) {
                    System.out.println();
                    System.out.println("  No staged files"
                            + (statusFilter != null ? " with status '" + statusFilter + "'" : "")
                            + ".");
                    System.out.println();
                    return 0;
                }

                System.out.println();
                System.out.printf("  %s staged files%s:%n%n",
                        AnsiOutput.bold(String.valueOf(files.size())),
                        statusFilter != null ? " (status: " + statusFilter + ")" : "");

                for (int i = 0; i < files.size(); i++) {
                    StagedFile file = files.get(i);

                    String statusBadge = switch (file.status()) {
                        case "pending" -> AnsiOutput.yellow("[PENDING]");
                        case "promoted" -> AnsiOutput.green("[PROMOTED]");
                        case "expired" -> AnsiOutput.red("[EXPIRED]");
                        case "deleted" -> AnsiOutput.dim("[DELETED]");
                        default -> AnsiOutput.dim("[" + file.status() + "]");
                    };

                    System.out.printf("  %s %s %s%n",
                            AnsiOutput.dim(String.format("%2d.", i + 1)),
                            statusBadge,
                            AnsiOutput.bold(file.relativePath()));

                    StringBuilder meta = new StringBuilder();
                    meta.append(FileUtils.formatSize(file.fileSize()));
                    if (file.fileType() != null) {
                        meta.append(" | ").append(file.fileType());
                    }
                    meta.append(" | sub-ws: ").append(file.subWorkspace());

                    if (file.classifiedOrg() != null) {
                        meta.append(" | org: ").append(file.classifiedOrg());
                        meta.append(String.format(" (%.0f%%)", file.classificationConfidence() * 100));
                    }

                    if (file.isPending()) {
                        Duration timeLeft = Duration.between(Instant.now(), file.expiresAt());
                        if (timeLeft.isPositive()) {
                            meta.append(" | expires in ").append(formatDuration(timeLeft));
                        } else {
                            meta.append(" | ").append(AnsiOutput.red("EXPIRED"));
                        }
                    }

                    System.out.printf("     %s%n", AnsiOutput.dim(meta.toString()));

                    if (verbose) {
                        System.out.printf("     ingested: %s%n",
                                AnsiOutput.dim(formatInstant(file.ingestedAt())));
                        if (file.suggestedDestination() != null) {
                            System.out.printf("     suggested: %s%n",
                                    AnsiOutput.cyan(file.suggestedDestination()));
                        }
                        if (file.promotedTo() != null) {
                            System.out.printf("     promoted to: %s at %s%n",
                                    AnsiOutput.green(file.promotedTo()),
                                    formatInstant(file.promotedAt()));
                        }
                    }
                    System.out.println();
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Failed to list staged files: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: promote
    // -----------------------------------------------------------------------

    /**
     * Promotes a staged file to a permanent sub-workspace.
     */
    @Command(name = "promote", description = "Promote a staged file to a permanent sub-workspace",
            mixinStandardHelpOptions = true)
    static class PromoteSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Parameters(index = "0", description = "Relative path of the staged file to promote")
        private String filePath;

        @Option(names = {"--to"}, required = true,
                description = "Target sub-workspace name to promote to")
        private String targetSubWorkspace;

        @Option(names = {"--dest"},
                description = "Destination path within the target sub-workspace (default: auto)")
        private String destPath;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printError("Staging is not enabled.");
                    return 1;
                }

                // Find target sub-workspace
                SubWorkspaceConfig targetSw = null;
                for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
                    if (sw.getName().equals(targetSubWorkspace)) {
                        targetSw = sw;
                        break;
                    }
                }

                if (targetSw == null) {
                    AnsiOutput.printError("Target sub-workspace not found: " + targetSubWorkspace);
                    System.out.println("  Available sub-workspaces:");
                    for (SubWorkspaceConfig sw : config.getSubWorkspaces()) {
                        if (!sw.isStaging()) {
                            System.out.println("    - " + sw.getName() + " (" + sw.getPath() + ")");
                        }
                    }
                    return 1;
                }

                if (targetSw.isStaging()) {
                    AnsiOutput.printError("Cannot promote to another staging sub-workspace.");
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                // Find the staged file
                List<StagedFile> files = staging.list("pending");
                StagedFile targetFile = null;
                for (StagedFile f : files) {
                    if (f.relativePath().equals(filePath)) {
                        targetFile = f;
                        break;
                    }
                }

                if (targetFile == null) {
                    AnsiOutput.printError("Staged file not found (or not in 'pending' status): "
                            + filePath);
                    return 1;
                }

                // Compute destination path
                String destination = destPath;
                if (destination == null) {
                    // Auto-compute: targetSw.path + filename
                    String fileName = Path.of(targetFile.relativePath()).getFileName().toString();
                    destination = targetSw.getPath() + "/" + fileName;
                }

                boolean success = staging.promote(targetFile, targetSubWorkspace, destination);
                if (success) {
                    AnsiOutput.printSuccess("Promoted: " + filePath + " -> " + destination
                            + " [" + targetSubWorkspace + "]");
                } else {
                    AnsiOutput.printError("Promotion failed for: " + filePath);
                    return 1;
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Promotion failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: route
    // -----------------------------------------------------------------------

    /**
     * Routes staged files to permanent destinations using config-defined rules.
     *
     * <p>Rules are defined in the workspace config.yaml under {@code routing.rules}.
     * Each rule has glob {@code patterns} matched against the file's basename,
     * and a {@code destination} absolute directory path.
     *
     * <p>The first matching rule wins. Files with no match are left pending.
     * Companion {@code .synthesis.md} files are moved alongside the main file
     * when {@code routing.copyCompanions: true} (default).
     */
    @Command(name = "route", description = "Route staged files to permanent destinations using config rules",
            mixinStandardHelpOptions = true)
    static class RouteSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--dry-run"}, description = "Show what would be routed without moving files",
                defaultValue = "false")
        private boolean dryRun;

        @Option(names = {"-v", "--verbose"}, description = "Show per-file detail",
                defaultValue = "false")
        private boolean verbose;

        @Option(names = {"--enrich-first"},
                description = "Enrich unmatched images/PDFs before content-intelligence pass"
                        + " (generates companion .synthesis.md files on the fly)",
                defaultValue = "false")
        private boolean enrichFirst;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled. Add 'staging: { enabled: true }'"
                            + " to your config.yaml.");
                    return 0;
                }

                if (!config.getRouting().hasRules() && !config.getStaging().isAutoClassify()) {
                    AnsiOutput.printWarning("No routing rules defined. Add 'routing.rules' to your config.yaml.");
                    System.out.println();
                    System.out.println("  Example:");
                    System.out.println("    routing:");
                    System.out.println("      rules:");
                    System.out.println("        - name: \"Synthesis docs\"");
                    System.out.println("          patterns: [\"Synthesis_*.pdf\"]");
                    System.out.println("          destination: \"/path/to/destination\"");
                    System.out.println();
                    return 0;
                }

                // Build PathMatchers for each rule
                List<RoutingRule> rules = config.getRouting().getRules();
                List<List<PathMatcher>> ruleMatchers = new ArrayList<>();
                for (RoutingRule rule : rules) {
                    List<PathMatcher> matchers = new ArrayList<>();
                    for (String pattern : rule.getPatterns()) {
                        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                    }
                    ruleMatchers.add(matchers);
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);
                List<StagedFile> pending = staging.list("pending");
                boolean copyCompanions = config.getRouting().isCopyCompanions();

                if (pending.isEmpty()) {
                    System.out.println();
                    System.out.println("  No pending staged files to route.");
                    System.out.println();
                    return 0;
                }

                System.out.println();
                if (dryRun) {
                    AnsiOutput.printHeader("Route Preview (dry-run)");
                } else {
                    AnsiOutput.printHeader("Routing Staged Files");
                }
                System.out.println();

                int routed = 0;
                int keywordRouted = 0;
                int contentRouted = 0;
                int skipped = 0;
                int errors = 0;
                List<StagedFile> unmatchedFiles = new ArrayList<>();
                List<String> unmatched = new ArrayList<>();
                List<String> suggestions = new ArrayList<>();

                for (StagedFile file : pending) {
                    // Skip internal staging files
                    String relPath = file.relativePath();
                    if (relPath.startsWith(".synthesis/")) {
                        continue;
                    }

                    String basename = Path.of(relPath).getFileName().toString();
                    // Skip already-processed files
                    if (basename.contains("_processed")) {
                        continue;
                    }
                    // Skip companion metadata files — they travel with their parent via copyCompanions
                    if (StagingCommand.isCompanionFile(basename)) {
                        continue;
                    }
                    Path basenameAsPath = Path.of(basename);

                    // Find first matching rule: patterns first, then companion keywords
                    RoutingRule matchedRule = null;
                    boolean matchedByKeyword = false;
                    for (int i = 0; i < rules.size(); i++) {
                        RoutingRule rule = rules.get(i);
                        // Pass 1a: filename glob patterns
                        List<PathMatcher> matchers = ruleMatchers.get(i);
                        for (PathMatcher matcher : matchers) {
                            if (matcher.matches(basenameAsPath)) {
                                matchedRule = rule;
                                break;
                            }
                        }
                        if (matchedRule != null) break;
                        // Pass 1b: companion content keywords
                        if (rule.hasKeywords()) {
                            Path companionPath = workspaceRoot.resolve(relPath + ".synthesis.md");
                            if (companionMatchesKeywords(companionPath, rule.getKeywords())) {
                                matchedRule = rule;
                                matchedByKeyword = true;
                            }
                        }
                        if (matchedRule != null) break;
                    }

                    if (matchedRule == null) {
                        unmatchedFiles.add(file);
                        continue;
                    }

                    // Compute absolute destination path
                    Path destDir = Path.of(matchedRule.getDestination());
                    Path destFile = destDir.resolve(basename);

                    String matchLabel = matchedByKeyword
                            ? AnsiOutput.dim(" (keyword: " + matchedRule.getName() + ")")
                            : AnsiOutput.dim(" (rule: " + matchedRule.getName() + ")");
                    if (dryRun) {
                        System.out.printf("  %s %s%n",
                                AnsiOutput.green("→"),
                                AnsiOutput.bold(basename));
                        System.out.printf("     %s%n", matchLabel.stripLeading());
                        System.out.printf("     dest: %s%n", AnsiOutput.cyan(destFile.toString()));
                        // Check for companion
                        Path companionPath = workspaceRoot.resolve(relPath + ".synthesis.md");
                        if (copyCompanions && Files.exists(companionPath)) {
                            System.out.printf("     companion: %s%n",
                                    AnsiOutput.dim(basename + ".synthesis.md → will be moved"));
                        }
                        System.out.println();
                        if (matchedByKeyword) keywordRouted++; else routed++;
                    } else {
                        try {
                            boolean success = staging.routeTo(file, destFile, copyCompanions);
                            if (success) {
                                if (matchedByKeyword) keywordRouted++; else routed++;
                                if (verbose) {
                                    System.out.printf("  %s %s → %s%s%n",
                                            AnsiOutput.green("✓"),
                                            AnsiOutput.bold(basename),
                                            AnsiOutput.dim(destFile.toString()),
                                            matchLabel);
                                } else {
                                    System.out.printf("  %s %s%s%n",
                                            AnsiOutput.green("✓"),
                                            basename,
                                            matchedByKeyword ? matchLabel : "");
                                }
                            } else {
                                errors++;
                                AnsiOutput.printError("Failed to route: " + basename);
                            }
                        } catch (Exception e) {
                            errors++;
                            AnsiOutput.printError("Error routing " + basename + ": " + e.getMessage());
                        }
                    }
                }

                // Content-intelligence fallback for unmatched files
                if (config.getStaging().isAutoClassify() && !unmatchedFiles.isEmpty()) {
                    // --enrich-first: generate companions for IMAGE/PDF files before classifying
                    if (enrichFirst && !dryRun) {
                        enrichUnmatchedFiles(unmatchedFiles, workspaceRoot, config, verbose);
                    }

                    OrganizationRegistry orgRegistry = loadOrgRegistry(workspaceRoot);
                    if (orgRegistry != null && orgRegistry.hasOrganizations()) {
                        DownloadsClassifier classifier = new DownloadsClassifier(orgRegistry);
                        double threshold = config.getStaging().getClassificationThreshold();

                        // Directory identity routing (before DownloadsClassifier)
                        List<StagedFile> dirIdentityUnmatched = new ArrayList<>();
                        DirectoryIdentityRouter dirIdentityRouter =
                                new DirectoryIdentityRouter(workspaceRoot, orgRegistry);
                        for (StagedFile unmatchedFile : unmatchedFiles) {
                            String dirIdRelPath = unmatchedFile.relativePath();
                            String dirIdBasename = Path.of(dirIdRelPath).getFileName().toString();
                            Path dirIdFilePath = workspaceRoot.resolve(dirIdRelPath);
                            Optional<DirectoryIdentityRouter.RouteResult> routeResult =
                                    dirIdentityRouter.route(dirIdFilePath, threshold);
                            if (routeResult.isPresent()) {
                                DirectoryIdentityRouter.RouteResult result = routeResult.get();
                                if (result.ambiguous()) {
                                    // Leave in staging -- ambiguous match
                                    if (verbose) {
                                        List<DirectoryScorer.ScoredCandidate> allScored =
                                                dirIdentityRouter.scoreAll(dirIdFilePath);
                                        System.out.printf("  ? %s %s%n",
                                                AnsiOutput.bold(dirIdBasename),
                                                AnsiOutput.yellow("(ambiguous)"));
                                        for (int ai = 0; ai < Math.min(3, allScored.size()); ai++) {
                                            DirectoryScorer.ScoredCandidate sc = allScored.get(ai);
                                            if (!sc.blocked()) {
                                                System.out.printf("     %s (%.2f): %s%n",
                                                        sc.directory().getFileName(), sc.totalScore(),
                                                        String.join(", ", sc.reasons()));
                                            }
                                        }
                                    }
                                    dirIdentityUnmatched.add(unmatchedFile);
                                } else {
                                    // Route it — resolve full destination file path
                                    Path destFile = result.directory().resolve(dirIdBasename);
                                    if (dryRun) {
                                        System.out.printf("  %s %s%n",
                                                AnsiOutput.cyan("~"),
                                                AnsiOutput.bold(dirIdBasename));
                                        System.out.printf("     %s%n",
                                                AnsiOutput.dim("(" + result.scoreLabel() + ")"));
                                        System.out.printf("     dest: %s%n",
                                                AnsiOutput.cyan(destFile.toString()));
                                        System.out.println();
                                        contentRouted++;
                                    } else {
                                        try {
                                            boolean success = staging.routeTo(
                                                    unmatchedFile, destFile, copyCompanions);
                                            if (success) {
                                                contentRouted++;
                                                if (verbose) {
                                                    System.out.printf("  %s %s → %s %s%n",
                                                            AnsiOutput.cyan("~"),
                                                            AnsiOutput.bold(dirIdBasename),
                                                            AnsiOutput.dim(destFile.toString()),
                                                            AnsiOutput.dim("(" + result.scoreLabel() + ")"));
                                                } else {
                                                    System.out.printf("  %s %s %s%n",
                                                            AnsiOutput.cyan("~"), dirIdBasename,
                                                            AnsiOutput.dim("(" + result.scoreLabel() + ")"));
                                                }
                                            } else {
                                                errors++;
                                            }
                                        } catch (Exception e) {
                                            errors++;
                                        }
                                    }
                                }
                            } else {
                                dirIdentityUnmatched.add(unmatchedFile);
                            }
                        }
                        unmatchedFiles = dirIdentityUnmatched;

                        for (StagedFile unmatchedFile : unmatchedFiles) {
                            String relPath = unmatchedFile.relativePath();
                            String unmatchedBasename = Path.of(relPath).getFileName().toString();
                            Path filePath = workspaceRoot.resolve(relPath);
                            Path companionPath = workspaceRoot.resolve(relPath + ".synthesis.md");
                            DownloadsClassifier.ClassificationResult cr =
                                    classifier.classifyWithCompanion(filePath, companionPath);
                            if (cr.shouldSkip()) {
                                skipped++;
                                continue;
                            }
                            // Persist classification to DB (best-effort)
                            try {
                                staging.classify(unmatchedFile, classifier);
                            } catch (Exception ignored) {}
                            if (cr.isConfident(threshold)) {
                                Path destFile = cr.suggestedDestination();
                                if (destFile == null) {
                                    unmatched.add(unmatchedBasename);
                                    skipped++;
                                    continue;
                                }
                                if (dryRun) {
                                    System.out.printf("  %s %s%n",
                                            AnsiOutput.cyan("~"),
                                            AnsiOutput.bold(unmatchedBasename));
                                    System.out.printf("     content: %s (%.0f%%)%n",
                                            AnsiOutput.dim(cr.organization()),
                                            cr.confidence() * 100);
                                    System.out.printf("     dest: %s%n",
                                            AnsiOutput.cyan(destFile.toString()));
                                    System.out.println();
                                    contentRouted++;
                                } else {
                                    try {
                                        boolean success = staging.routeTo(unmatchedFile, destFile, copyCompanions);
                                        if (success) {
                                            contentRouted++;
                                            if (verbose) {
                                                System.out.printf("  %s %s → %s %s%n",
                                                        AnsiOutput.cyan("~"),
                                                        AnsiOutput.bold(unmatchedBasename),
                                                        AnsiOutput.dim(destFile.toString()),
                                                        AnsiOutput.dim("(content: " + cr.organization() + ")"));
                                            } else {
                                                System.out.printf("  %s %s %s%n",
                                                        AnsiOutput.cyan("~"),
                                                        unmatchedBasename,
                                                        AnsiOutput.dim("(content: " + cr.organization() + ")"));
                                            }
                                        } else {
                                            errors++;
                                            AnsiOutput.printError("Failed to route: " + unmatchedBasename);
                                        }
                                    } catch (Exception e) {
                                        errors++;
                                        AnsiOutput.printError("Error routing " + unmatchedBasename
                                                + ": " + e.getMessage());
                                    }
                                }
                            } else if (cr.organization() != null) {
                                suggestions.add(String.format("%s → %s (%.0f%% — below %.0f%% threshold)",
                                        unmatchedBasename, cr.organization(),
                                        cr.confidence() * 100,
                                        threshold * 100));
                                skipped++;
                            } else {
                                unmatched.add(unmatchedBasename);
                                skipped++;
                            }
                        }
                    } else {
                        // No org registry — all unmatched files stay unmatched
                        for (StagedFile unmatchedFile : unmatchedFiles) {
                            unmatched.add(Path.of(unmatchedFile.relativePath()).getFileName().toString());
                            skipped++;
                        }
                    }
                } else {
                    // autoClassify disabled — move all unmatched to the display list
                    for (StagedFile unmatchedFile : unmatchedFiles) {
                        unmatched.add(Path.of(unmatchedFile.relativePath()).getFileName().toString());
                        skipped++;
                    }
                }

                System.out.println();
                int totalRouted = routed + keywordRouted + contentRouted;
                List<String> breakdown = new ArrayList<>();
                if (routed > 0) breakdown.add(routed + " by rule");
                if (keywordRouted > 0) breakdown.add(keywordRouted + " by keyword");
                if (contentRouted > 0) breakdown.add(contentRouted + " by content");
                String routedStr = breakdown.size() > 1
                        ? totalRouted + " (" + String.join(", ", breakdown) + ")"
                        : String.valueOf(totalRouted);
                if (dryRun) {
                    System.out.printf("  Would route: %s  |  Suggestions: %s  |  No match: %s%n",
                            AnsiOutput.green(routedStr),
                            AnsiOutput.yellow(String.valueOf(suggestions.size())),
                            AnsiOutput.yellow(String.valueOf(unmatched.size())));
                } else {
                    System.out.printf("  Routed: %s  |  Suggestions: %s  |  No match: %s%s%n",
                            AnsiOutput.green(routedStr),
                            AnsiOutput.yellow(String.valueOf(suggestions.size())),
                            AnsiOutput.yellow(String.valueOf(unmatched.size())),
                            errors > 0 ? "  |  Errors: " + AnsiOutput.red(String.valueOf(errors)) : "");
                }

                if (!suggestions.isEmpty() && verbose) {
                    System.out.println();
                    System.out.println("  Content suggestions (below threshold — review manually):");
                    for (String s : suggestions) {
                        System.out.println("    " + AnsiOutput.cyan("?") + " " + s);
                    }
                }

                if (!unmatched.isEmpty() && verbose) {
                    System.out.println();
                    System.out.println("  Unmatched files (no routing rule or content match):");
                    for (String name : unmatched) {
                        System.out.println("    " + AnsiOutput.dim(name));
                    }
                    if (config.getStaging().isAutoClassify()) {
                        System.out.println();
                        System.out.println("  " + AnsiOutput.dim(
                                "Tip: Run 'synthesis enrich' to extract PDF/image content for better classification."));
                    }
                }

                System.out.println();
                if (!dryRun && totalRouted > 0) {
                    System.out.println("  " + AnsiOutput.dim("Run 'synthesis scan' to update the index."));
                    System.out.println();
                }

                return errors > 0 ? 1 : 0;
            } catch (Exception e) {
                AnsiOutput.printError("Routing failed: " + e.getMessage());
                return 1;
            }
        }

        /**
         * Generates companion {@code .synthesis.md} files for unmatched IMAGE and PDF
         * staging files that do not already have one.
         *
         * <p>Uses {@link CompanionFileGenerator} with the best available enrichment level
         * (AI if an API key is configured, otherwise BASIC). This gives the subsequent
         * content-intelligence pass ({@link DownloadsClassifier#classifyWithCompanion})
         * real content to work with for files whose names carry no semantic signal
         * (UUID exports, hash-named browser saves, etc.).
         *
         * @param unmatchedFiles files that did not match any routing rule
         * @param workspaceRoot  workspace root path
         * @param config         workspace configuration (for AI settings)
         * @param verbose        if true, print per-file enrichment status
         */
        private void enrichUnmatchedFiles(List<StagedFile> unmatchedFiles, Path workspaceRoot,
                                          SynthesisConfig config, boolean verbose) {
            List<StagedFile> enrichable = unmatchedFiles.stream()
                    .filter(f -> "IMAGE".equals(f.fileType()) || "PDF".equals(f.fileType()))
                    .filter(f -> !Files.exists(workspaceRoot.resolve(f.relativePath() + ".synthesis.md")))
                    .toList();

            if (enrichable.isEmpty()) return;

            EnrichmentLevel level = EnrichmentLevel.maxAvailable();
            AiClient aiClient = null;
            if (level.hasAI()) {
                Optional<AiClient> opt = AiClient.create(config.getAi());
                if (opt.isEmpty()) {
                    opt = AiClient.createIfApiKeyAvailable(config.getAi(), config.getAi().getModel());
                }
                aiClient = opt.orElse(null);
                if (aiClient == null) {
                    level = EnrichmentLevel.BASIC;
                }
            }

            CompanionFileGenerator generator = new CompanionFileGenerator(level, false, aiClient);
            AnalyzerRegistry analyzers = new AnalyzerRegistry();
            int enriched = 0;

            System.out.println();
            AnsiOutput.printInfo("Enriching " + enrichable.size() + " unmatched image/PDF file(s) before classification...");

            // Partition PDFs that are presentations from everything else
            List<StagedFile> presentations = new ArrayList<>();
            List<StagedFile> otherMedia = new ArrayList<>();

            for (StagedFile file : enrichable) {
                Path filePath = workspaceRoot.resolve(file.relativePath());
                if (!Files.exists(filePath)) {
                    otherMedia.add(file);
                    continue;
                }
                if ("PDF".equals(file.fileType())) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                        FileMetadata meta = FileMetadata.of(filePath, workspaceRoot,
                                attrs.size(), attrs.lastModifiedTime().toInstant(), null);
                        Object mediaType = analyzers.analyze(meta).metrics().get("mediaType");
                        if ("presentation".equals(mediaType)) {
                            presentations.add(file);
                            continue;
                        }
                    } catch (Exception ignored) {
                        // Analysis failed — fall through to normal enrichment
                    }
                }
                otherMedia.add(file);
            }

            // Handle presentation PDFs: extract slides, enrich per-slide, write index companion
            if (!presentations.isEmpty()) {
                AnsiOutput.printInfo("Extracting slides from " + presentations.size() + " presentation(s)...");
                for (StagedFile pres : presentations) {
                    Path filePath = workspaceRoot.resolve(pres.relativePath());
                    try {
                        extractAndEnrichPresentation(pres, workspaceRoot, filePath,
                                generator, analyzers, verbose);
                        enriched++;
                    } catch (Exception e) {
                        if (verbose) {
                            AnsiOutput.printWarning("Could not extract slides from " + pres.relativePath()
                                    + ": " + e.getMessage());
                        }
                        // Fall back to regular companion generation
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                            FileMetadata metadata = FileMetadata.of(filePath, workspaceRoot,
                                    attrs.size(), attrs.lastModifiedTime().toInstant(), null);
                            Optional<Path> companion = generator.generate(metadata,
                                    analyzers.analyze(metadata), List.of());
                            if (companion.isPresent()) {
                                enriched++;
                                if (verbose) {
                                    System.out.println("  + companion (fallback): " + companion.get().getFileName());
                                }
                            }
                        } catch (Exception fallbackEx) {
                            if (verbose) {
                                AnsiOutput.printWarning("Fallback enrichment also failed for "
                                        + pres.relativePath() + ": " + fallbackEx.getMessage());
                            }
                        }
                    }
                }
            }

            // Handle regular images and non-presentation PDFs
            for (StagedFile file : otherMedia) {
                Path filePath = workspaceRoot.resolve(file.relativePath());
                if (!Files.exists(filePath)) continue;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                    FileMetadata metadata = FileMetadata.of(
                            filePath, workspaceRoot, attrs.size(),
                            attrs.lastModifiedTime().toInstant(), null);
                    Optional<Path> companion = generator.generate(metadata, analyzers.analyze(metadata),
                            List.of());
                    if (companion.isPresent()) {
                        enriched++;
                        if (verbose) {
                            System.out.println("  + companion: " + companion.get().getFileName());
                        }
                    }
                } catch (Exception e) {
                    if (verbose) {
                        AnsiOutput.printWarning("Could not enrich " + file.relativePath()
                                + ": " + e.getMessage());
                    }
                }
            }

            if (enriched > 0) {
                System.out.printf("  Enriched %d file(s) — level: %s%n%n", enriched, level.name());
            }
        }

        /**
         * Extracts slides from a presentation PDF, generates a per-slide companion for each
         * PNG, and writes a slide-index companion for the PDF itself.
         *
         * <p>The slides directory is created as {@code <pdf-basename>-slides/} in the same
         * directory as the PDF. The PDF-level companion lists all slides with a one-line
         * summary drawn from each slide's companion file.
         */
        private void extractAndEnrichPresentation(StagedFile file, Path workspaceRoot,
                                                   Path filePath, CompanionFileGenerator generator,
                                                   AnalyzerRegistry analyzers, boolean verbose) throws Exception {
            String pdfName = filePath.getFileName().toString();
            String baseName = pdfName.endsWith(".pdf")
                    ? pdfName.substring(0, pdfName.length() - 4)
                    : pdfName;
            Path slidesDir = filePath.getParent().resolve(baseName + "-slides");

            // Extract slides as PNGs (no AI client — CompanionFileGenerator handles vision)
            PresentationExtractor extractor = new PresentationExtractor();
            PresentationExtractor.ExtractionResult result =
                    extractor.extractSlides(filePath, slidesDir, PresentationExtractor.DEFAULT_DPI, null);

            if (verbose) {
                System.out.printf("  %s → %d slides extracted to %s/%n",
                        pdfName, result.slidesExtracted(), slidesDir.getFileName());
            }

            // Enrich each slide PNG with its own companion
            for (PresentationExtractor.SlideInfo slide : result.slides()) {
                Path slidePath = slide.imagePath();
                if (!Files.exists(slidePath)) continue;
                try {
                    BasicFileAttributes slideAttrs = Files.readAttributes(slidePath, BasicFileAttributes.class);
                    FileMetadata slideMeta = FileMetadata.of(slidePath, workspaceRoot,
                            slideAttrs.size(), slideAttrs.lastModifiedTime().toInstant(), null);
                    Optional<Path> slideCompanion = generator.generate(slideMeta,
                            analyzers.analyze(slideMeta), List.of());
                    if (slideCompanion.isPresent() && verbose) {
                        System.out.println("  + " + slideCompanion.get().getFileName());
                    }
                } catch (Exception e) {
                    if (verbose) {
                        AnsiOutput.printWarning("  Could not enrich slide " + slidePath.getFileName()
                                + ": " + e.getMessage());
                    }
                }
            }

            // Write slide-index companion for the PDF
            Path pdfCompanion = filePath.getParent().resolve(pdfName + ".synthesis.md");
            String indexContent = buildSlideIndexCompanion(result, filePath, baseName);
            Files.writeString(pdfCompanion, indexContent);

            if (verbose) {
                System.out.println("  + " + pdfCompanion.getFileName() + " (slide index)");
            }
        }

        /**
         * Loads the OrganizationRegistry for content-based routing.
         * Searches the workspace root and common locations.
         */
        private OrganizationRegistry loadOrgRegistry(Path workspaceRoot) {
            // Try the workspace root
            Path orgsFile = workspaceRoot.resolve(".synthesis").resolve("organizations.json");
            if (Files.exists(orgsFile)) {
                try {
                    OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
                    registry.load();
                    if (registry.hasOrganizations()) return registry;
                } catch (Exception ignored) {}
            }

            // Try ~/Documents
            Path docsPath = Path.of(System.getProperty("user.home"), "Documents");
            orgsFile = docsPath.resolve(".synthesis").resolve("organizations.json");
            if (Files.exists(orgsFile)) {
                try {
                    OrganizationRegistry registry = new OrganizationRegistry(docsPath);
                    registry.load();
                    if (registry.hasOrganizations()) return registry;
                } catch (Exception ignored) {}
            }

            // Try all discovered workspaces
            try {
                WorkspaceDiscoveryConfig discoveryConfig = WorkspaceDiscoveryConfig.load();
                for (Path searchPath : discoveryConfig.getSearchPaths()) {
                    if (!Files.exists(searchPath)) continue;
                    orgsFile = searchPath.resolve(".synthesis").resolve("organizations.json");
                    if (Files.exists(orgsFile)) {
                        try {
                            OrganizationRegistry registry = new OrganizationRegistry(searchPath);
                            registry.load();
                            if (registry.hasOrganizations()) return registry;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: resolve
    // -----------------------------------------------------------------------

    /**
     * Routes a staged file to a specific directory, optionally learning a routing hint.
     *
     * <p>Unlike {@link RouteSub} which uses config rules, {@code resolve} is manual:
     * the user specifies exactly where a file should go. With {@code --learn}, the
     * resolution is saved as a {@link RoutingHints.RoutingHint} for future automatic routing.
     *
     * <p>Usage:
     * <pre>
     *   synthesis staging resolve myfile.pdf --to /path/to/dir
     *   synthesis staging resolve myfile.pdf --to /path/to/dir --also /other/dir
     *   synthesis staging resolve myfile.pdf --to /path/to/dir --learn
     * </pre>
     *
     * @since v1.9.9
     */
    @Command(name = "resolve",
            description = "Route a staged file to a specific directory, optionally learning a routing hint",
            mixinStandardHelpOptions = true)
    static class ResolveSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Parameters(index = "0", description = "Relative path of the staged file to resolve")
        private String filePath;

        @Option(names = {"--to"}, required = true, description = "Destination directory")
        private Path destination;

        @Option(names = {"--also"}, description = "Additional destination directory (cross-organization copy)")
        private Path also;

        @Option(names = {"--learn"},
                description = "Save this resolution as a routing hint for future files",
                defaultValue = "false")
        private boolean learn;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printError("Staging is not enabled.");
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                // Find the staged file
                List<StagedFile> pending = staging.list("pending");
                StagedFile targetFile = null;
                for (StagedFile f : pending) {
                    if (f.relativePath().equals(filePath)) {
                        targetFile = f;
                        break;
                    }
                }

                if (targetFile == null) {
                    AnsiOutput.printError("Staged file not found (or not in 'pending' status): "
                            + filePath);
                    return 1;
                }

                // Validate destination
                if (!Files.isDirectory(destination)) {
                    Files.createDirectories(destination);
                }

                // Route to --to
                String basename = Path.of(targetFile.relativePath()).getFileName().toString();
                Path destFile = destination.resolve(basename);
                boolean copyCompanions = config.getRouting().isCopyCompanions();

                boolean success = staging.routeTo(targetFile, destFile, copyCompanions);
                if (!success) {
                    AnsiOutput.printError("Failed to route: " + filePath);
                    return 1;
                }

                AnsiOutput.printSuccess("Resolved: " + basename + " -> " + destFile);

                // Handle --also: copy the file to a second destination
                if (also != null) {
                    if (!Files.isDirectory(also)) {
                        Files.createDirectories(also);
                    }
                    Path alsoFile = also.resolve(basename);
                    // Copy from destination (since source is now _processed)
                    Files.copy(destFile, alsoFile, StandardCopyOption.REPLACE_EXISTING);

                    // Also copy companion if it exists
                    if (copyCompanions) {
                        Path companionDest = Path.of(destFile + ".synthesis.md");
                        if (Files.exists(companionDest)) {
                            Path alsoCompanion = Path.of(alsoFile + ".synthesis.md");
                            Files.copy(companionDest, alsoCompanion, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }

                    AnsiOutput.printSuccess("Also copied to: " + alsoFile);
                }

                // Handle --learn: save routing hint
                if (learn) {
                    String pattern = RoutingHints.derivePattern(basename);
                    RoutingHints routingHints = new RoutingHints(workspaceRoot);
                    try {
                        routingHints.load();
                    } catch (IOException e) {
                        // Start fresh if hints file is corrupt
                    }
                    routingHints.addOrUpdate(new RoutingHints.RoutingHint(
                            pattern, destination.toAbsolutePath().toString(),
                            Instant.now(), 0));
                    AnsiOutput.printInfo("Learned hint: " + pattern + " -> " + destination);
                }

                System.out.println();
                System.out.println("  " + AnsiOutput.dim("Run 'synthesis scan' to update the index."));
                System.out.println();
                return 0;

            } catch (Exception e) {
                AnsiOutput.printError("Resolve failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: hints
    // -----------------------------------------------------------------------

    /**
     * Lists and manages routing hints learned from {@code resolve --learn}.
     *
     * <p>Without options, lists all saved hints. Use {@code --delete N} to remove
     * a hint by its 1-based index, or {@code --promote N} to convert it to a
     * permanent routing rule in config.yaml.
     *
     * @since v1.9.9
     */
    @Command(name = "hints",
            description = "List and manage routing hints learned from resolve --learn",
            mixinStandardHelpOptions = true)
    static class HintsSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--promote"},
                description = "Promote hint #N to permanent routing rule in config.yaml")
        private Integer promote;

        @Option(names = {"--delete"}, description = "Delete hint #N")
        private Integer delete;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();

                RoutingHints routingHints = new RoutingHints(workspaceRoot);
                routingHints.load();
                List<RoutingHints.RoutingHint> hints = routingHints.getHints();

                if (delete != null) {
                    if (hints.isEmpty()) {
                        AnsiOutput.printWarning("No hints to delete.");
                        return 0;
                    }
                    if (delete < 1 || delete > hints.size()) {
                        AnsiOutput.printError("Invalid hint index: " + delete
                                + " (have " + hints.size() + " hints)");
                        return 1;
                    }
                    RoutingHints.RoutingHint removed = hints.get(delete - 1);
                    routingHints.delete(delete);
                    AnsiOutput.printSuccess("Deleted hint [" + delete + "]: "
                            + removed.filenamePattern());
                    return 0;
                }

                if (promote != null) {
                    if (hints.isEmpty()) {
                        AnsiOutput.printWarning("No hints to promote.");
                        return 0;
                    }
                    if (promote < 1 || promote > hints.size()) {
                        AnsiOutput.printError("Invalid hint index: " + promote
                                + " (have " + hints.size() + " hints)");
                        return 1;
                    }
                    RoutingHints.RoutingHint hint = hints.get(promote - 1);

                    // Append the new rule to config.yaml
                    Path configFile = workspaceRoot.resolve("config.yaml");
                    if (!Files.exists(configFile)) {
                        AnsiOutput.printError("config.yaml not found at " + workspaceRoot);
                        return 1;
                    }

                    String configContent = Files.readString(configFile);

                    // Build a YAML rule entry
                    String ruleName = "hint-" + hint.filenamePattern()
                            .replace("*", "")
                            .replace(".", "-")
                            .replaceAll("-+", "-")
                            .replaceAll("^-|-$", "");
                    String ruleYaml = String.format(
                            "%n    - name: \"%s\"%n      patterns: [\"%s\"]%n      destination: \"%s\"%n",
                            ruleName, hint.filenamePattern(), hint.destinationPath());

                    // Try to insert after existing rules section, or create routing section
                    if (configContent.contains("routing:") && configContent.contains("rules:")) {
                        // Append after the last rule entry (find last "destination:" line in rules)
                        int rulesIdx = configContent.indexOf("rules:");
                        // Find the next top-level key or end of file
                        // Simple approach: append the rule text right after "rules:" line content
                        int rulesLineEnd = configContent.indexOf('\n', rulesIdx);
                        if (rulesLineEnd < 0) rulesLineEnd = configContent.length();

                        // Find the end of existing rules entries
                        String afterRules = configContent.substring(rulesLineEnd);
                        // Look for next non-rule-entry line (not starting with spaces/dash)
                        int insertPos = rulesLineEnd + afterRules.length(); // default: end
                        String[] lines = afterRules.split("\n");
                        int offset = rulesLineEnd;
                        boolean inRules = true;
                        for (String line : lines) {
                            offset += line.length() + 1;
                            if (line.isBlank()) continue;
                            // If we hit a non-indented line, we've left the rules block
                            if (inRules && !line.startsWith(" ") && !line.startsWith("\t")
                                    && !line.trim().startsWith("-") && !line.trim().startsWith("#")) {
                                insertPos = offset - line.length() - 1;
                                break;
                            }
                        }
                        configContent = configContent.substring(0, insertPos)
                                + ruleYaml
                                + configContent.substring(insertPos);
                    } else if (configContent.contains("routing:")) {
                        // routing section exists but no rules
                        int routingIdx = configContent.indexOf("routing:");
                        int routingLineEnd = configContent.indexOf('\n', routingIdx);
                        if (routingLineEnd < 0) routingLineEnd = configContent.length();
                        String insert = "\n  rules:" + ruleYaml;
                        configContent = configContent.substring(0, routingLineEnd)
                                + insert
                                + configContent.substring(routingLineEnd);
                    } else {
                        // No routing section at all — append at end
                        configContent += String.format(
                                "%nrouting:%n  rules:" + ruleYaml);
                    }

                    Files.writeString(configFile, configContent);

                    // Delete the hint from hints file
                    routingHints.delete(promote);

                    AnsiOutput.printSuccess("Promoted hint [" + promote + "] to routing rule in config.yaml:");
                    System.out.println("    pattern: " + hint.filenamePattern());
                    System.out.println("    dest:    " + hint.destinationPath());
                    return 0;
                }

                // Default: list hints
                if (hints.isEmpty()) {
                    System.out.println();
                    System.out.println("  No routing hints. Use 'synthesis staging resolve --learn' to learn hints.");
                    System.out.println();
                    return 0;
                }

                System.out.println();
                System.out.printf("  Routing hints (%d):%n", hints.size());
                System.out.println();
                for (int i = 0; i < hints.size(); i++) {
                    RoutingHints.RoutingHint hint = hints.get(i);
                    String learnedDate = hint.learnedAt() != null
                            ? hint.learnedAt().toString().substring(0, 10) : "unknown";
                    System.out.printf("  [%d] %s -> %s  (hit: %d, learned: %s)%n",
                            i + 1,
                            AnsiOutput.bold(hint.filenamePattern()),
                            AnsiOutput.cyan(hint.destinationPath()),
                            hint.hitCount(),
                            learnedDate);
                }
                System.out.println();

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Hints operation failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: rename
    // -----------------------------------------------------------------------

    /**
     * Renames files to descriptive names using AI descriptions from companion files.
     *
     * <p>Reads each file's {@code .synthesis.md} companion and either uses a heuristic
     * to extract a meaningful name from the AI description, or (with {@code --ai}) calls
     * Claude to generate one.
     *
     * <p>Both the binary file and its companion are renamed atomically. The
     * {@code companion_for:} header in the companion is updated to match.
     *
     * <p>Usage:
     * <pre>
     *   synthesis staging rename --dir /path/to/visuals --pattern "unnamed*.png" --dry-run
     *   synthesis staging rename --dir /path/to/visuals --pattern "unnamed*.png" --ai
     * </pre>
     */
    @Command(name = "rename",
            description = "Rename files to descriptive names using companion AI descriptions",
            mixinStandardHelpOptions = true)
    static class RenameSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--dir"}, required = true,
                description = "Directory containing files to rename")
        private Path targetDir;

        @Option(names = {"--pattern"}, description = "Glob pattern for files to rename",
                defaultValue = "unnamed*.png")
        private String pattern;

        @Option(names = {"--ai"}, description = "Use Claude to generate names (better quality, uses API)",
                defaultValue = "false")
        private boolean useAi;

        @Option(names = {"--dry-run"}, description = "Show proposed renames without acting",
                defaultValue = "false")
        private boolean dryRun;

        @Option(names = {"-v", "--verbose"}, defaultValue = "false")
        private boolean verbose;

        /** Generic keywords to skip when building a name from companion Keywords: line. */
        private static final Set<String> GENERIC_KEYWORDS = Set.of(
                "infographic", "diagram", "chart", "image", "png", "jpg", "jpeg",
                "pdf", "visualization", "figure", "screenshot", "slide", "business diagram",
                "workflow diagram", "notebooklm", "presentation"
        );

        @Override
        public Integer call() {
            try {
                if (!Files.isDirectory(targetDir)) {
                    AnsiOutput.printError("Not a directory: " + targetDir);
                    return 1;
                }

                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                List<Path> candidates = new ArrayList<>();
                try (Stream<Path> stream = Files.list(targetDir)) {
                    stream.filter(p -> matcher.matches(p.getFileName()))
                          .filter(Files::isRegularFile)
                          .sorted()
                          .forEach(candidates::add);
                }

                List<Path> withCompanions = candidates.stream()
                        .filter(p -> Files.exists(Path.of(p + ".synthesis.md")))
                        .toList();

                if (withCompanions.isEmpty()) {
                    if (candidates.isEmpty()) {
                        System.out.println("  No files matching '" + pattern + "' in " + targetDir);
                    } else {
                        AnsiOutput.printWarning(candidates.size() + " file(s) matched but none have"
                                + " companion .synthesis.md files.");
                        AnsiOutput.printInfo("Run 'synthesis enrich --type image' first.");
                    }
                    return 0;
                }

                // Optionally initialize AI client for AI-assisted naming (cheap/fast model)
                Optional<AiClient> claude = Optional.empty();
                if (useAi) {
                    claude = AiClient.createFast(ConfigLoader.load(parent.parent.getWorkspaceRoot()).getAi());
                    if (claude.isEmpty()) {
                        AnsiOutput.printWarning("No API key found — falling back to heuristic naming.");
                    }
                }

                System.out.println();
                if (dryRun) {
                    AnsiOutput.printHeader("Rename Preview (dry-run)");
                } else {
                    AnsiOutput.printHeader("Renaming Files");
                }
                System.out.println();

                int renamed = 0, skipped = 0, errors = 0;
                Set<String> usedNames = new HashSet<>();

                for (Path file : withCompanions) {
                    Path companionFile = Path.of(file + ".synthesis.md");
                    String ext = getExt(file.getFileName().toString());
                    String companionContent = Files.readString(companionFile);

                    String newBaseName;
                    if (useAi && claude.isPresent()) {
                        newBaseName = generateNameWithClaude(claude.get(), companionContent);
                    } else {
                        newBaseName = generateNameHeuristic(companionContent);
                    }

                    if (newBaseName == null || newBaseName.isBlank()) {
                        if (verbose) {
                            AnsiOutput.printWarning("Could not generate name for: "
                                    + file.getFileName());
                        }
                        skipped++;
                        continue;
                    }

                    // Ensure uniqueness
                    newBaseName = uniquify(newBaseName, usedNames);
                    String newFileName = newBaseName + ext;
                    Path newFilePath = targetDir.resolve(newFileName);
                    Path newCompanionPath = targetDir.resolve(newFileName + ".synthesis.md");

                    if (dryRun) {
                        System.out.printf("  %s %s%n", AnsiOutput.dim("→"),
                                AnsiOutput.bold(file.getFileName().toString()));
                        System.out.printf("    %s%n", AnsiOutput.cyan(newFileName));
                        System.out.println();
                        usedNames.add(newBaseName);
                        renamed++;
                    } else {
                        try {
                            // Update companion_for: and # header before renaming
                            String updated = updateCompanionHeader(companionContent, newFileName);
                            Files.writeString(companionFile, updated);

                            Files.move(file, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                            Files.move(companionFile, newCompanionPath,
                                    StandardCopyOption.REPLACE_EXISTING);

                            usedNames.add(newBaseName);
                            System.out.printf("  %s %s → %s%n",
                                    AnsiOutput.green("✓"),
                                    AnsiOutput.dim(file.getFileName().toString()),
                                    AnsiOutput.cyan(newFileName));
                            renamed++;
                        } catch (Exception e) {
                            AnsiOutput.printError("Failed to rename " + file.getFileName()
                                    + ": " + e.getMessage());
                            errors++;
                        }
                    }
                }

                System.out.println();
                if (dryRun) {
                    System.out.printf("  Would rename: %s  |  Would skip: %s%n",
                            AnsiOutput.green(String.valueOf(renamed)),
                            AnsiOutput.yellow(String.valueOf(skipped)));
                } else {
                    System.out.printf("  Renamed: %s  |  Skipped: %s%s%n",
                            AnsiOutput.green(String.valueOf(renamed)),
                            AnsiOutput.yellow(String.valueOf(skipped)),
                            errors > 0 ? "  |  Errors: " + AnsiOutput.red(String.valueOf(errors)) : "");
                    if (renamed > 0) {
                        System.out.println();
                        System.out.println("  " + AnsiOutput.dim("Run 'synthesis scan' to update the index."));
                    }
                }
                System.out.println();
                return errors > 0 ? 1 : 0;

            } catch (Exception e) {
                AnsiOutput.printError("Rename failed: " + e.getMessage());
                return 1;
            }
        }

        /** Asks Claude to generate a 3-5 word kebab-case filename from the companion content. */
        private String generateNameWithClaude(AiClient claude, String companionContent) {
            String prompt = """
                    Based on this companion file describing an image or document, generate a concise descriptive \
                    filename in kebab-case (3-5 words, no extension, no underscores).
                    The name must capture the PRIMARY subject of the content — what it is ABOUT, not that it's an \
                    "infographic" or "diagram".

                    Companion file:
                    ---
                    %s
                    ---

                    Respond with ONLY the kebab-case filename. No explanation, no quotes, no extension.
                    Examples of good names: quadim-knowledge-graph-ecosystem, synthesis-cross-company-platform, \
                    eXOReaction-lib-pcb-achievement, nordic-energy-skill-evolution, team-management-llm-approach
                    """.formatted(companionContent);
            try {
                String result = claude.generate(prompt, 32).trim()
                        .toLowerCase()
                        .replaceAll("[^a-z0-9-]", "-")
                        .replaceAll("-+", "-")
                        .replaceAll("^-|-$", "");
                return result.isBlank() ? null : result;
            } catch (Exception e) {
                return null;
            }
        }

        /** Heuristic: extracts meaningful keywords from the companion file's AI Description section. */
        private String generateNameHeuristic(String companionContent) {
            // Look for "Keywords: ..." line inside the ## AI Description section
            Pattern aiSection = Pattern.compile(
                    "## AI Description.*?Keywords:\\s*([^\n]+)", Pattern.DOTALL);
            Matcher km = aiSection.matcher(companionContent);
            if (km.find()) {
                String keywordLine = km.group(1).trim();
                List<String> meaningful = Arrays.stream(keywordLine.split(","))
                        .map(String::trim)
                        .filter(k -> !k.isBlank())
                        .filter(k -> !GENERIC_KEYWORDS.contains(k.toLowerCase()))
                        .toList();
                if (!meaningful.isEmpty()) {
                    String joined = String.join(" ", meaningful.subList(0, Math.min(3, meaningful.size())));
                    return toKebab(joined);
                }
            }

            // Fall back: extract subject after "illustrating", "depicting", etc. in AI Description
            Pattern descSection = Pattern.compile(
                    "## AI Description\\s*\r?\n(.+?)(?:\r?\n##|$)", Pattern.DOTALL);
            Matcher dm = descSection.matcher(companionContent);
            if (dm.find()) {
                String desc = dm.group(1);
                Pattern subjectPat = Pattern.compile(
                        "(?:illustrating|depicting|showing|presenting|describing)\\s+"
                                + "(?:the\\s+|an?\\s+|)?['\"]?([A-Z][^,\\.]{4,40}?)['\"]?"
                                + "(?:,|\\.|\\s+(?:with|featuring|that|which|using))");
                Matcher sm = subjectPat.matcher(desc);
                if (sm.find()) {
                    return toKebab(sm.group(1).trim());
                }
            }

            return null;
        }

        private String toKebab(String text) {
            return text.toLowerCase()
                    .replaceAll("[^a-z0-9\\s-]", " ")
                    .trim()
                    .replaceAll("\\s+", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
        }

        private String uniquify(String name, Set<String> used) {
            if (name.length() > 60) {
                name = name.substring(0, 60);
                int lastDash = name.lastIndexOf('-');
                if (lastDash > 20) name = name.substring(0, lastDash);
            }
            if (!used.contains(name)) return name;
            for (int i = 2; i < 100; i++) {
                String candidate = name + "-" + i;
                if (!used.contains(candidate)) return candidate;
            }
            return name + "-" + System.currentTimeMillis();
        }

        private String updateCompanionHeader(String content, String newFileName) {
            // Update # header (first line)
            content = content.replaceFirst("(?m)^# .+$", "# " + newFileName);
            // Update companion_for: in YAML block
            content = content.replaceFirst("(?m)^companion_for: .+$",
                    "companion_for: " + newFileName);
            return content;
        }

        private String getExt(String filename) {
            int dot = filename.lastIndexOf('.');
            return dot >= 0 ? filename.substring(dot) : "";
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: ingest
    // -----------------------------------------------------------------------

    /**
     * Scans staging areas and registers new files.
     */
    @Command(name = "ingest", description = "Scan staging areas and register new files",
            mixinStandardHelpOptions = true)
    static class IngestSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"-v", "--verbose"}, description = "Show detailed output",
                defaultValue = "false")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled.");
                    return 0;
                }

                // Find staging sub-workspaces
                List<SubWorkspaceConfig> stagingSubWorkspaces =
                        StagingManager.findStagingSubWorkspaces(config.getSubWorkspaces());

                if (stagingSubWorkspaces.isEmpty()) {
                    AnsiOutput.printWarning("No staging sub-workspaces configured. "
                            + "Add a sub-workspace with type: staging to your config.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                int totalIngested = 0;
                int totalClassified = 0;
                int totalErrors = 0;

                for (SubWorkspaceConfig swConfig : stagingSubWorkspaces) {
                    Path stagingDir = workspaceRoot.resolve(swConfig.getPath());
                    if (!Files.isDirectory(stagingDir)) {
                        if (verbose) {
                            System.out.println("  Staging directory does not exist: " + swConfig.getPath());
                        }
                        continue;
                    }

                    AnsiOutput.printInfo("Scanning staging area: " + swConfig.getName()
                            + " (" + swConfig.getPath() + ")");

                    // Get pending staged files for this sub-workspace.
                    // Only pending entries block re-ingestion — promoted/expired records
                    // must NOT prevent a new file with the same basename from being picked
                    // up (e.g. NotebookLM always exports as "unnamed (N).png"; fix #146).
                    List<StagedFile> existing = staging.list("pending");
                    java.util.Set<String> existingPaths = new java.util.HashSet<>();
                    for (StagedFile f : existing) {
                        if (f.subWorkspace().equals(swConfig.getName())) {
                            existingPaths.add(f.relativePath());
                        }
                    }

                    // Build matchers from workspace excludePatterns (same rules as scan)
                    List<PathMatcher> excludeMatchers = new ArrayList<>();
                    for (String pat : config.getScan().getExcludePatterns()) {
                        excludeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pat));
                    }

                    // Walk the staging directory and ingest new files
                    try (Stream<Path> files = Files.walk(stagingDir)) {
                        List<Path> newFiles = files
                                .filter(Files::isRegularFile)
                                .filter(p -> {
                                    String rel = workspaceRoot.relativize(p).toString();
                                    // Always skip internal Synthesis files
                                    if (rel.startsWith(".synthesis/")) return false;
                                    // Skip already-processed files (renamed by routing)
                                    String basename = p.getFileName().toString();
                                    if (basename.contains("_processed")) return false;
                                    // Skip companion metadata files — they travel with their parent
                                    if (isCompanionFile(basename)) return false;
                                    // Skip files matching workspace excludePatterns
                                    for (PathMatcher matcher : excludeMatchers) {
                                        if (matcher.matches(p) || matcher.matches(Path.of(rel))) {
                                            return false;
                                        }
                                    }
                                    return !existingPaths.contains(rel);
                                })
                                .toList();

                        for (Path file : newFiles) {
                            try {
                                String relativePath = workspaceRoot.relativize(file).toString();
                                long size = Files.size(file);
                                String ext = getExtension(file.getFileName().toString());
                                String fileType = guessFileType(ext);

                                StagedFile ingested = staging.ingest(
                                        relativePath, swConfig.getName(),
                                        size, fileType, null);

                                totalIngested++;
                                if (verbose) {
                                    System.out.println("    + " + relativePath
                                            + " (" + FileUtils.formatSize(size) + ")");
                                }
                            } catch (Exception e) {
                                totalErrors++;
                                if (verbose) {
                                    System.err.println("    Error: " + file + ": " + e.getMessage());
                                }
                            }
                        }
                    }
                }

                System.out.println();
                AnsiOutput.printSuccess("Ingestion complete: " + totalIngested + " new files"
                        + (totalErrors > 0 ? ", " + totalErrors + " errors" : ""));

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Ingestion failed: " + e.getMessage());
                return 1;
            }
        }

        private String getExtension(String filename) {
            int dot = filename.lastIndexOf('.');
            return dot >= 0 ? filename.substring(dot) : "";
        }

        private String guessFileType(String ext) {
            return switch (ext.toLowerCase()) {
                case ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".cs",
                     ".rb", ".php", ".swift", ".kt", ".scala", ".sh" -> "CODE";
                case ".md", ".markdown" -> "MARKDOWN";
                case ".yaml", ".yml" -> "YAML";
                case ".json" -> "JSON";
                case ".xml", ".properties", ".cfg", ".conf", ".ini", ".toml" -> "CONFIG";
                case ".pdf" -> "PDF";
                case ".png", ".jpg", ".jpeg", ".gif", ".svg", ".bmp", ".webp" -> "IMAGE";
                case ".mp4", ".mov", ".avi", ".mkv", ".webm" -> "VIDEO";
                case ".mp3", ".wav", ".ogg", ".flac", ".aac" -> "AUDIO";
                case ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx" -> "DOCUMENT";
                default -> "OTHER";
            };
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: expire
    // -----------------------------------------------------------------------

    /**
     * Processes expired files in staging areas.
     */
    @Command(name = "expire", description = "Process expired staging files",
            mixinStandardHelpOptions = true)
    static class ExpireSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Option(names = {"--dry-run"}, description = "Show what would be expired without acting",
                defaultValue = "false")
        private boolean dryRun;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                List<StagedFile> expired = staging.findExpired();

                if (expired.isEmpty()) {
                    System.out.println("  No expired files.");
                    return 0;
                }

                System.out.println();
                System.out.printf("  %s expired file(s):%n%n",
                        AnsiOutput.bold(String.valueOf(expired.size())));

                for (StagedFile file : expired) {
                    Duration age = Duration.between(file.ingestedAt(), Instant.now());
                    System.out.printf("  %s %s  %s  ingested %s ago%n",
                            AnsiOutput.red("[EXPIRED]"),
                            file.relativePath(),
                            AnsiOutput.dim(FileUtils.formatSize(file.fileSize())),
                            formatDuration(age));
                }

                if (dryRun) {
                    System.out.println();
                    AnsiOutput.printInfo("Dry run -- no changes made."
                            + (config.getStaging().isCleanupExpired()
                            ? " Would delete " + expired.size() + " file(s)." : ""));
                } else {
                    int processed = staging.processExpired();
                    System.out.println();
                    AnsiOutput.printSuccess("Processed " + processed + " expired file(s)"
                            + (config.getStaging().isCleanupExpired() ? " (deleted)" : " (marked)"));
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Expire processing failed: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: stats
    // -----------------------------------------------------------------------

    /**
     * Shows staging statistics.
     */
    @Command(name = "stats", description = "Show staging statistics",
            mixinStandardHelpOptions = true)
    static class StatsSub implements Callable<Integer> {

        @ParentCommand
        private StagingCommand parent;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (!config.getStaging().isEnabled()) {
                    AnsiOutput.printWarning("Staging is not enabled.");
                    return 0;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                StagingManager staging = new StagingManager(db, config.getStaging(), workspaceRoot);

                // Use filtered list counts for accuracy (excludes .synthesis/ internals)
                long realPending = staging.list("pending").stream()
                        .filter(f -> !f.relativePath().startsWith(".synthesis/")).count();
                long realPromoted = staging.list("promoted").stream()
                        .filter(f -> !f.relativePath().startsWith(".synthesis/")).count();
                StagingSummary stats = staging.getStats();

                System.out.println();
                AnsiOutput.printHeader("Staging Statistics");
                System.out.println();
                System.out.println("  Configuration:");
                System.out.println("    Retention:       " + config.getStaging().getRetentionDays() + " days");
                System.out.println("    Auto-classify:   " + (config.getStaging().isAutoClassify() ? "yes" : "no"));
                System.out.println("    Cleanup expired: " + (config.getStaging().isCleanupExpired() ? "yes" : "no"));
                System.out.println("    Threshold:       " + String.format("%.0f%%",
                        config.getStaging().getClassificationThreshold() * 100));
                System.out.println();
                System.out.println("  Status:");
                System.out.println("    Pending:   " + AnsiOutput.yellow(String.valueOf(realPending)));
                System.out.println("    Promoted:  " + AnsiOutput.green(String.valueOf(realPromoted)));
                System.out.println("    Expired:   " + AnsiOutput.red(String.valueOf(stats.expired())));
                System.out.println();

                // Show staging sub-workspaces
                List<SubWorkspaceConfig> stagingSws =
                        StagingManager.findStagingSubWorkspaces(config.getSubWorkspaces());
                if (!stagingSws.isEmpty()) {
                    System.out.println("  Staging areas:");
                    for (SubWorkspaceConfig sw : stagingSws) {
                        Path stagingDir = workspaceRoot.resolve(sw.getPath());
                        String dirStatus = Files.isDirectory(stagingDir)
                                ? AnsiOutput.green("exists") : AnsiOutput.red("missing");
                        System.out.printf("    %s (%s) - %s%n",
                                AnsiOutput.bold(sw.getName()), sw.getPath(), dirStatus);
                    }
                    System.out.println();
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Failed to get staging stats: " + e.getMessage());
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    /**
     * Returns true if the given filename is a Synthesis companion metadata file.
     *
     * <p>Companion files (*.synthesis.md) are created by {@code synthesis enrich} and
     * travel alongside their parent file when routed. They must never be treated as
     * independent routing candidates.
     *
     * @param basename the file's base name (not a full path)
     * @return true if this file is a companion metadata file
     */
    static boolean isCompanionFile(String basename) {
        return basename.endsWith(".synthesis.md");
    }

    /**
     * Returns true if the companion file's content contains any of the given keywords
     * (case-insensitive, OR logic — any single hit is sufficient).
     *
     * <p>Reads at most the first 5,000 characters to match the behaviour of
     * {@code DownloadsClassifier.analyzeContent()}. Returns false silently if
     * the companion does not exist or cannot be read.
     *
     * @param companionFile path to the {@code .synthesis.md} companion file
     * @param keywords      keywords to search for (must not be null or empty)
     * @return true if at least one keyword is found in the companion content
     */
    static boolean companionMatchesKeywords(Path companionFile, List<String> keywords) {
        if (!Files.exists(companionFile)) return false;
        try {
            byte[] bytes = Files.readAllBytes(companionFile);
            int limit = Math.min(bytes.length, 5_000);
            String content = new String(bytes, 0, limit).toLowerCase();
            for (String keyword : keywords) {
                if (content.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        } catch (IOException e) {
            // best-effort — missing or unreadable companion → no match
        }
        return false;
    }

    static String formatInstant(Instant instant) {
        if (instant == null) return "never";
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * Builds the content of the slide-index companion for a presentation PDF.
     *
     * <p>Reads each slide's companion file (if present) and extracts the first
     * non-header, non-empty line as the one-line summary for the table.
     */
    static String buildSlideIndexCompanion(PresentationExtractor.ExtractionResult result,
                                            Path pdfPath, String baseName) {
        String pdfFileName = pdfPath.getFileName().toString();
        String slidesDirName = baseName + "-slides";

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("companion_for: ").append(pdfFileName).append("\n");
        sb.append("type: PDF\n");
        sb.append("media_type: presentation\n");
        sb.append("---\n\n");
        sb.append("# ").append(result.presentationTitle()).append("\n\n");
        sb.append("**Source:** `").append(pdfFileName).append("`\n");
        sb.append("**Slides:** ").append(result.slidesExtracted()).append("\n");
        sb.append("**Slides directory:** `").append(slidesDirName).append("/`\n\n");

        sb.append("| Slide | File | Summary |\n");
        sb.append("|-------|------|---------|\n");

        for (PresentationExtractor.SlideInfo slide : result.slides()) {
            String slideFileName = slide.imagePath().getFileName().toString();
            String slideLink = "[" + slideFileName + "](" + slidesDirName + "/" + slideFileName + ")";
            String summary = extractSlideOneLiner(slide.imagePath());
            sb.append("| ").append(slide.slideNumber()).append(" | ")
              .append(slideLink).append(" | ")
              .append(summary).append(" |\n");
        }

        sb.append("\n*Generated by Synthesis — slide-level enrichment*\n");
        return sb.toString();
    }

    /**
     * Reads the companion file for a slide PNG and returns the first non-header,
     * non-empty content line as a one-line summary. Returns "—" if unavailable.
     */
    static String extractSlideOneLiner(Path slidePath) {
        Path companionPath = slidePath.getParent().resolve(slidePath.getFileName() + ".synthesis.md");
        if (!Files.exists(companionPath)) return "—";
        try {
            List<String> lines = Files.readAllLines(companionPath);
            // Skip YAML front-matter block (lines between opening and closing ---)
            int start = 0;
            if (!lines.isEmpty() && lines.get(0).equals("---")) {
                start = 1;
                while (start < lines.size() && !lines.get(start).equals("---")) {
                    start++;
                }
                start++; // skip the closing ---
            }
            for (int i = start; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.isBlank() || l.startsWith("#") || l.startsWith("**")) continue;
                return l.length() > 120 ? l.substring(0, 117) + "..." : l;
            }
            return "—";
        } catch (IOException e) {
            return "—";
        }
    }

    static String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        long minutes = duration.toMinutesPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
