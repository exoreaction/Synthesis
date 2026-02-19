package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.RepositoryManager;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds dependencies between repositories in a multi-repo workspace.
 *
 * <p>Analyzes file references, imports, and links that cross repository boundaries.
 *
 * <p>Usage: {@code synthesis cross-repo-deps}
 */
@Command(
        name = "cross-repo-deps",
        description = "Find dependencies between repositories",
        mixinStandardHelpOptions = true
)
public class CrossRepoDepsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    // Patterns for detecting cross-references
    private static final Pattern FILE_REF = Pattern.compile(
            "(?:['\"`])([\\w./-]+\\.(?:java|py|js|ts|md|yaml|yml|json|xml|go|rs|kt))['\"`]");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^(?:import|from|require|use)\\s+(.+)", Pattern.MULTILINE);

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Cross-Repository Dependencies");

            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            RepositoryManager repoManager = new RepositoryManager(workspaceRoot);
            repoManager.load();

            if (!repoManager.hasRepos() || repoManager.getRepositories().size() < 2) {
                AnsiOutput.printWarning("Cross-repo analysis requires at least 2 repositories.");
                AnsiOutput.printInfo("Use 'synthesis init --repos dir1,dir2' to set up multi-repo workspace.");
                return 0;
            }

            // Build per-repo file index
            Map<String, Map<String, String>> repoFileIndex = new LinkedHashMap<>();
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                for (RepositoryManager.RepoEntry repo : repoManager.getRepositories()) {
                    List<SearchResult> files = index.listAll(null, repo.name(), 50000);
                    Map<String, String> fileNameToPath = new HashMap<>();
                    for (SearchResult f : files) {
                        fileNameToPath.put(f.fileName(), f.relativePath());
                    }
                    repoFileIndex.put(repo.name(), fileNameToPath);
                }
            }

            // Analyze cross-repo references
            Map<String, Map<String, List<String>>> crossDeps = new LinkedHashMap<>();

            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                for (RepositoryManager.RepoEntry repo : repoManager.getRepositories()) {
                    List<SearchResult> files = index.listAll(null, repo.name(), 50000);
                    for (SearchResult file : files) {
                        if (!Files.exists(file.path()) || !Files.isReadable(file.path())) continue;
                        try {
                            String content = FileUtils.readPreview(file.path(), 30_000);
                            if (content.isEmpty()) continue;

                            // Check each file for references to files in other repos
                            for (Map.Entry<String, Map<String, String>> otherRepo : repoFileIndex.entrySet()) {
                                if (otherRepo.getKey().equals(repo.name())) continue;

                                for (String otherFileName : otherRepo.getValue().keySet()) {
                                    if (content.contains(otherFileName)) {
                                        String key = repo.name() + " -> " + otherRepo.getKey();
                                        crossDeps.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                                .computeIfAbsent(file.relativePath(), k -> new ArrayList<>())
                                                .add(otherFileName);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            // Skip unreadable files
                        }
                    }
                }
            }

            // Print results
            if (crossDeps.isEmpty()) {
                System.out.println("  No cross-repository dependencies detected.");
                System.out.println();
            } else {
                int totalDeps = crossDeps.values().stream()
                        .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
                        .sum();

                System.out.printf("  Found %s cross-repository dependencies:%n%n",
                        AnsiOutput.bold(String.valueOf(totalDeps)));

                for (Map.Entry<String, Map<String, List<String>>> entry : crossDeps.entrySet()) {
                    System.out.println("  " + AnsiOutput.bold(AnsiOutput.cyan(entry.getKey())));
                    for (Map.Entry<String, List<String>> fileEntry : entry.getValue().entrySet()) {
                        System.out.println("    " + fileEntry.getKey());
                        for (String ref : fileEntry.getValue()) {
                            System.out.println("      " + AnsiOutput.green("->") + " " + ref);
                        }
                    }
                    System.out.println();
                }
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Cross-repo analysis failed: " + e.getMessage());
            return 1;
        }
    }
}
