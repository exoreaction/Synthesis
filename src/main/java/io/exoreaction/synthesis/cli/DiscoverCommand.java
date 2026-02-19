package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.search.MultiWorkspaceSearch;
import io.exoreaction.synthesis.search.WorkspaceDiscoveryConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Scans configured search paths for unindexed git repositories and suggests
 * workspaces to initialize.
 *
 * <p>A git repository is "unindexed" when it has a {@code .git/} directory but
 * no {@code .synthesis/} directory and is not already known to Synthesis.
 *
 * <p>Usage:
 * <pre>
 *   synthesis discover          # Scan and list unindexed git repos
 *   synthesis discover --json   # JSON output
 * </pre>
 */
@Command(
        name = "discover",
        description = "Scan for unindexed git repositories and suggest workspaces to initialize",
        mixinStandardHelpOptions = true
)
public class DiscoverCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean json;

    @Override
    public Integer call() {
        List<Path> knownWorkspaces = MultiWorkspaceSearch.discoverAllWorkspaces();
        Set<Path> knownSet = new HashSet<>(knownWorkspaces);

        List<Path> found = findUnindexedGitRepos(knownSet);

        if (found.isEmpty()) {
            System.out.println();
            System.out.println("  No unindexed git repositories found in search paths.");
            if (!knownWorkspaces.isEmpty()) {
                System.out.println("  All discovered repositories are already configured as Synthesis workspaces.");
            }
            System.out.println();
            return 0;
        }

        if (json) {
            printJson(found);
        } else {
            printTable(found, knownWorkspaces.size());
        }

        return 0;
    }

    /**
     * Finds git repositories in discovery search paths that are not yet
     * initialized as Synthesis workspaces.
     *
     * <p>Scans configured search paths ({@link WorkspaceDiscoveryConfig}) plus parent
     * directories of all known workspaces, one level deep.
     *
     * @param knownWorkspaces set of already-known (initialized) workspace paths
     * @return sorted list of unindexed git repo paths
     */
    public static List<Path> findUnindexedGitRepos(Set<Path> knownWorkspaces) {
        WorkspaceDiscoveryConfig config = WorkspaceDiscoveryConfig.load();
        Set<Path> scanRoots = new LinkedHashSet<>();

        // Add configured search paths
        for (Path searchPath : config.getSearchPaths()) {
            if (Files.isDirectory(searchPath)) {
                scanRoots.add(searchPath.toAbsolutePath().normalize());
            }
        }

        // Add parent directories of known workspaces (catches sibling repos)
        for (Path ws : knownWorkspaces) {
            Path parentDir = ws.getParent();
            if (parentDir != null && Files.isDirectory(parentDir)) {
                scanRoots.add(parentDir.toAbsolutePath().normalize());
            }
        }

        return findUnindexedGitRepos(scanRoots, knownWorkspaces);
    }

    /**
     * Package-private overload — accepts explicit scan roots (used by tests).
     */
    static List<Path> findUnindexedGitRepos(Set<Path> scanRoots, Set<Path> knownWorkspaces) {
        List<Path> unindexed = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (Path root : scanRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(Files::isDirectory)
                       .forEach(dir -> {
                           Path abs = dir.toAbsolutePath().normalize();
                           if (!seen.add(abs)) return;             // deduplicate
                           if (knownWorkspaces.contains(abs)) return; // already indexed
                           if (!Files.isDirectory(abs.resolve(".git"))) return; // not a git repo
                           if (Files.isDirectory(abs.resolve(".synthesis"))) return; // already init'd
                           unindexed.add(abs);
                       });
            } catch (IOException e) {
                // Skip inaccessible directories silently
            }
        }

        unindexed.sort(Comparator.naturalOrder());
        return unindexed;
    }

    /**
     * Estimates the number of regular files in a directory, excluding {@code .git},
     * {@code node_modules}, {@code target}, and {@code .synthesis} subdirectories.
     * Walks up to depth 5 and caps at 100,000 for performance.
     */
    static long estimateFileCount(Path dir) {
        try (Stream<Path> walk = Files.walk(dir, 5)) {
            return walk.filter(Files::isRegularFile)
                       .filter(p -> {
                           String s = p.toString();
                           return !s.contains("/.git/")
                                  && !s.contains("/node_modules/")
                                  && !s.contains("/target/")
                                  && !s.contains("/.synthesis/");
                       })
                       .limit(100_000)
                       .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void printTable(List<Path> repos, int knownCount) {
        System.out.println();
        System.out.println("  Scanning for unindexed repositories...");
        System.out.println();
        System.out.printf("  Found %d git repo(s) not tracked by Synthesis:%n", repos.size());
        System.out.println();

        for (Path repo : repos) {
            long fileCount = estimateFileCount(repo);
            String countStr = fileCount > 0 ? String.format("%,d files", fileCount) : "?";
            System.out.printf("  %-60s  (%s)%n", repo.toString(), countStr);
        }

        System.out.println();
        System.out.println("  To add:");
        for (Path repo : repos) {
            System.out.println("    synthesis init " + repo);
        }
        System.out.println();
        System.out.println("  Tip: After initializing, run 'synthesis scan' to index the workspace.");
        System.out.println();
    }

    private void printJson(List<Path> repos) {
        System.out.println("{");
        System.out.println("  \"unindexedRepos\": [");
        for (int i = 0; i < repos.size(); i++) {
            Path repo = repos.get(i);
            long fileCount = estimateFileCount(repo);
            System.out.println("    {");
            System.out.println("      \"path\": \"" + repo + "\",");
            System.out.println("      \"name\": \"" + repo.getFileName() + "\",");
            System.out.printf("      \"estimatedFiles\": %d%n", fileCount);
            System.out.print("    }");
            System.out.println(i < repos.size() - 1 ? "," : "");
        }
        System.out.println("  ]");
        System.out.println("}");
    }
}
