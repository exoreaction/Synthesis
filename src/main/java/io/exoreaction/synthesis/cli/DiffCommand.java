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
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Git diff integration command. Shows files changed between two Git refs
 * and optionally searches only within those files.
 *
 * <p>Usage:
 * <pre>
 *   synthesis diff main..HEAD                # List changed files
 *   synthesis diff main..HEAD --search "bug" # Search only in changed files
 *   synthesis diff --uncommitted             # Show uncommitted changes
 * </pre>
 */
@Command(
        name = "diff",
        description = "Show changed files between Git refs",
        mixinStandardHelpOptions = true
)
public class DiffCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            arity = "0..1",
            description = "Git ref range (e.g., main..HEAD, v1.0..v2.0)"
    )
    private String refSpec;

    @Option(
            names = {"-s", "--search"},
            description = "Search query to run against changed files only"
    )
    private String searchQuery;

    @Option(
            names = {"--uncommitted"},
            description = "Show uncommitted changes instead of ref diff",
            defaultValue = "false"
    )
    private boolean uncommitted;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed file information",
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
                System.out.println();

                List<ChangedFile> changes;
                String contextLabel;

                if (uncommitted) {
                    changes = gitIntegration.getUncommittedChanges();
                    contextLabel = "uncommitted changes";
                } else if (refSpec != null) {
                    changes = gitIntegration.diffRefs(refSpec);
                    contextLabel = refSpec;
                } else {
                    // Default: show uncommitted changes
                    changes = gitIntegration.getUncommittedChanges();
                    contextLabel = "uncommitted changes";
                }

                if (changes.isEmpty()) {
                    AnsiOutput.printSuccess("No changes found" +
                            (contextLabel != null ? " for " + contextLabel : "") + ".");
                    System.out.println();
                    return 0;
                }

                // Group by change type
                Map<GitIntegration.ChangeType, List<ChangedFile>> byType = changes.stream()
                        .collect(Collectors.groupingBy(ChangedFile::type));

                System.out.println("  " + AnsiOutput.bold("Changed files (" + contextLabel + "):") +
                        " " + changes.size() + " files");
                System.out.println();

                for (var entry : byType.entrySet()) {
                    for (ChangedFile file : entry.getValue()) {
                        String prefix = switch (file.type()) {
                            case ADDED -> AnsiOutput.green("A ");
                            case MODIFIED -> AnsiOutput.yellow("M ");
                            case DELETED -> AnsiOutput.red("D ");
                            case RENAMED -> AnsiOutput.cyan("R ");
                            case COPIED -> AnsiOutput.blue("C ");
                        };
                        System.out.println("    " + prefix + file.path());
                    }
                }
                System.out.println();

                // Search within changed files if query provided
                if (searchQuery != null && !searchQuery.isBlank()) {
                    System.out.println("  " + AnsiOutput.bold("Search results in changed files:"));
                    System.out.println();

                    try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                        List<SearchResult> allResults = index.search(searchQuery, 100);

                        // Filter to only changed files
                        List<String> changedPaths = changes.stream()
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
                    var commits = gitIntegration.getRecentCommits(5);
                    if (!commits.isEmpty()) {
                        System.out.println("  " + AnsiOutput.bold("Recent commits:"));
                        for (var commit : commits) {
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
            AnsiOutput.printError("Diff failed: " + e.getMessage());
            return 1;
        }
    }
}
