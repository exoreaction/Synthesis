package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.git.GitIntegration;
import io.exoreaction.synthesis.git.GitIntegration.ChangedFile;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Shows files changed since a given date using Git history.
 *
 * <p>Usage:
 * <pre>
 *   synthesis changed --since 2026-02-01      # Files changed since date
 *   synthesis changed --since 7d              # Files changed in last 7 days
 *   synthesis changed --since 24h             # Files changed in last 24 hours
 *   synthesis changed --since 2w --type CODE  # Only code files changed in 2 weeks
 * </pre>
 */
@Command(
        name = "changed",
        description = "Show files changed since a date (Git history)",
        mixinStandardHelpOptions = true
)
public class ChangedCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--since"},
            description = "Date (YYYY-MM-DD) or duration (7d, 24h, 2w)",
            required = true
    )
    private String since;

    @Option(
            names = {"--type"},
            description = "Filter by file type: CODE, MARKDOWN, YAML, etc."
    )
    private String typeFilter;

    @Option(
            names = {"-s", "--search"},
            description = "Search query to run against changed files"
    )
    private String searchQuery;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed information",
            defaultValue = "false"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Parse the since parameter
            Instant sinceInstant = parseSince(since);
            if (sinceInstant == null) {
                AnsiOutput.printError("Invalid --since format: " + since);
                AnsiOutput.printInfo("Use YYYY-MM-DD (e.g., 2026-02-01) or duration (e.g., 7d, 24h, 2w)");
                return 1;
            }

            // Open Git repository
            GitIntegration gitIntegration;
            try {
                gitIntegration = new GitIntegration(workspaceRoot);
            } catch (Exception e) {
                AnsiOutput.printError("Not a Git repository: " + e.getMessage());
                AnsiOutput.printInfo("This command requires a Git repository.");
                return 1;
            }

            try (gitIntegration) {
                System.out.println();
                AnsiOutput.printInfo("Repository: " + gitIntegration.getWorkingDir());
                AnsiOutput.printInfo("Branch: " + gitIntegration.getCurrentBranch());
                AnsiOutput.printInfo("Since: " + since + " (" + sinceInstant + ")");
                System.out.println();

                // Get changes
                List<ChangedFile> changes = gitIntegration.getChangesSince(sinceInstant);

                if (changes.isEmpty()) {
                    AnsiOutput.printSuccess("No files changed since " + since + ".");
                    System.out.println();
                    return 0;
                }

                // Apply type filter if specified
                List<ChangedFile> filtered = changes;
                if (typeFilter != null) {
                    filtered = changes.stream()
                            .filter(c -> {
                                String ext = "";
                                int dot = c.path().lastIndexOf('.');
                                if (dot >= 0) ext = c.path().substring(dot);
                                FileUtils.FileType ft = FileUtils.classifyFile(Path.of(c.path()));
                                return ft.name().equalsIgnoreCase(typeFilter);
                            })
                            .toList();
                }

                System.out.println("  " + AnsiOutput.bold("Files changed since " + since + ":") +
                        " " + filtered.size() + " files" +
                        (typeFilter != null ? " (filtered: " + typeFilter + ")" : ""));
                System.out.println();

                for (ChangedFile file : filtered) {
                    System.out.println("    " + file.path());
                }
                System.out.println();

                // Search within changed files if query provided
                if (searchQuery != null && !searchQuery.isBlank()) {
                    System.out.println("  " + AnsiOutput.bold("Search results in changed files:"));
                    System.out.println();

                    try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                        List<SearchResult> allResults = index.search(searchQuery, 100);

                        List<String> changedPaths = filtered.stream()
                                .map(ChangedFile::path)
                                .toList();

                        List<SearchResult> filteredResults = allResults.stream()
                                .filter(r -> changedPaths.stream().anyMatch(cp ->
                                        r.relativePath().endsWith(cp) || r.relativePath().contains(cp)))
                                .toList();

                        if (filteredResults.isEmpty()) {
                            System.out.println("    No matching results in changed files.");
                        } else {
                            for (int i = 0; i < filteredResults.size(); i++) {
                                SearchResult r = filteredResults.get(i);
                                System.out.printf("    %d. %s%n", i + 1, AnsiOutput.bold(r.relativePath()));
                                if (!r.summary().isEmpty()) {
                                    System.out.printf("       %s%n", AnsiOutput.dim(r.summary()));
                                }
                            }
                        }
                        System.out.println();
                    }
                }

                // Show recent commits if verbose
                if (verbose) {
                    var commits = gitIntegration.getRecentCommits(10);
                    var relevantCommits = commits.stream()
                            .filter(c -> c.timestamp().isAfter(sinceInstant))
                            .toList();

                    if (!relevantCommits.isEmpty()) {
                        System.out.println("  " + AnsiOutput.bold("Commits since " + since + ":"));
                        for (var commit : relevantCommits) {
                            System.out.printf("    %s %s %s%n",
                                    AnsiOutput.yellow(commit.hash()),
                                    commit.message(),
                                    AnsiOutput.dim("(" + commit.author() + ")"));
                        }
                        System.out.println();
                    }
                }

                return 0;
            }
        } catch (Exception e) {
            AnsiOutput.printError("Changed command failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Parses a since string into an Instant.
     * Supports:
     * - ISO date: 2026-02-01
     * - Duration: 7d (days), 24h (hours), 2w (weeks), 3m (months)
     *
     * @return parsed Instant, or null if unparseable
     */
    public static Instant parseSince(String since) {
        if (since == null || since.isBlank()) return null;

        // Try as ISO date
        try {
            LocalDate date = LocalDate.parse(since);
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            // Not a date, try as duration
        }

        // Try as duration (e.g., 7d, 24h, 2w, 3m)
        try {
            String numStr = since.substring(0, since.length() - 1);
            char unit = since.charAt(since.length() - 1);
            long num = Long.parseLong(numStr);

            Instant now = Instant.now();
            return switch (unit) {
                case 'h' -> now.minus(num, ChronoUnit.HOURS);
                case 'd' -> now.minus(num, ChronoUnit.DAYS);
                case 'w' -> now.minus(num * 7, ChronoUnit.DAYS);
                case 'm' -> now.minus(num * 30, ChronoUnit.DAYS);
                default -> null;
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }
}
