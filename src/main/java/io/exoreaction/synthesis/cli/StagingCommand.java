package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.RoutingRule;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
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

                if (!config.getRouting().hasRules()) {
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
                int skipped = 0;
                int errors = 0;
                List<String> unmatched = new ArrayList<>();

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
                    Path basenameAsPath = Path.of(basename);

                    // Find first matching rule
                    RoutingRule matchedRule = null;
                    for (int i = 0; i < rules.size(); i++) {
                        List<PathMatcher> matchers = ruleMatchers.get(i);
                        for (PathMatcher matcher : matchers) {
                            if (matcher.matches(basenameAsPath)) {
                                matchedRule = rules.get(i);
                                break;
                            }
                        }
                        if (matchedRule != null) break;
                    }

                    if (matchedRule == null) {
                        unmatched.add(basename);
                        skipped++;
                        continue;
                    }

                    // Compute absolute destination path
                    Path destDir = Path.of(matchedRule.getDestination());
                    Path destFile = destDir.resolve(basename);

                    if (dryRun) {
                        System.out.printf("  %s %s%n",
                                AnsiOutput.green("→"),
                                AnsiOutput.bold(basename));
                        System.out.printf("     rule: %s%n", AnsiOutput.dim(matchedRule.getName()));
                        System.out.printf("     dest: %s%n", AnsiOutput.cyan(destFile.toString()));
                        // Check for companion
                        Path companionPath = workspaceRoot.resolve(relPath + ".synthesis.md");
                        if (copyCompanions && Files.exists(companionPath)) {
                            System.out.printf("     companion: %s%n",
                                    AnsiOutput.dim(basename + ".synthesis.md → will be moved"));
                        }
                        System.out.println();
                        routed++;
                    } else {
                        try {
                            boolean success = staging.routeTo(file, destFile, copyCompanions);
                            if (success) {
                                routed++;
                                if (verbose) {
                                    System.out.printf("  %s %s → %s%n",
                                            AnsiOutput.green("✓"),
                                            AnsiOutput.bold(basename),
                                            AnsiOutput.dim(destFile.toString()));
                                } else {
                                    System.out.printf("  %s %s%n",
                                            AnsiOutput.green("✓"),
                                            basename);
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

                System.out.println();
                if (dryRun) {
                    System.out.printf("  Would route: %s  |  No match: %s%n",
                            AnsiOutput.green(String.valueOf(routed)),
                            AnsiOutput.yellow(String.valueOf(skipped)));
                } else {
                    System.out.printf("  Routed: %s  |  No match: %s%s%n",
                            AnsiOutput.green(String.valueOf(routed)),
                            AnsiOutput.yellow(String.valueOf(skipped)),
                            errors > 0 ? "  |  Errors: " + AnsiOutput.red(String.valueOf(errors)) : "");
                }

                if (!unmatched.isEmpty() && verbose) {
                    System.out.println();
                    System.out.println("  Unmatched files (no routing rule):");
                    for (String name : unmatched) {
                        System.out.println("    " + AnsiOutput.dim(name));
                    }
                }

                System.out.println();
                if (!dryRun && routed > 0) {
                    System.out.println("  " + AnsiOutput.dim("Run 'synthesis scan' to update the index."));
                    System.out.println();
                }

                return errors > 0 ? 1 : 0;
            } catch (Exception e) {
                AnsiOutput.printError("Routing failed: " + e.getMessage());
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

                // Optionally initialize Claude client for AI-assisted naming
                Optional<ClaudeClient> claude = Optional.empty();
                if (useAi) {
                    claude = ClaudeClient.createIfApiKeyAvailable("claude-haiku-4-5-20251001");
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
        private String generateNameWithClaude(ClaudeClient claude, String companionContent) {
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

                    // Get existing staged files for this sub-workspace
                    List<StagedFile> existing = staging.list(null);
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

    static String formatInstant(Instant instant) {
        if (instant == null) return "never";
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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
