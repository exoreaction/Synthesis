package io.exoreaction.synthesis.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

/**
 * Analyses git commit history to detect files that frequently change together.
 *
 * <p>Structural coupling (imports) is visible in the dependency graph.
 * Behavioural coupling (always committed together) is invisible to import analysis.
 * This class surfaces the latter.
 */
public class CoChangeAnalyzer {

    public record CoChangePair(
        String fileA,
        String fileB,
        int coCommitCount,
        int totalCommitsA,
        int totalCommitsB,
        double ratio,
        boolean hasImportLink
    ) {}

    public record CoChangeReport(
        List<CoChangePair> highCoupling,
        List<CoChangePair> mediumCoupling,
        List<CoChangePair> unexpected
    ) {}

    /**
     * Analyse git history for the given workspace.
     *
     * @param workspaceRoot   root of the git repository
     * @param lookbackCommits how many recent commits to analyse (default: 100)
     * @param minSupport      minimum co-commit count to include (default: 3)
     * @param importLinks     set of "fileA|fileB" pairs with known import relationships
     */
    public CoChangeReport analyze(Path workspaceRoot, int lookbackCommits,
                                  int minSupport, Set<String> importLinks) throws IOException {
        List<List<String>> commits = parseGitLog(workspaceRoot, lookbackCommits);
        return analyzeCommits(commits, minSupport, importLinks);
    }

    /**
     * Analyse pre-parsed commit data (package-private for testability).
     *
     * @param commits     list of commits; each inner list = files changed in that commit
     * @param minSupport  minimum co-commit count to include
     * @param importLinks set of "fileA|fileB" pairs with known import relationships
     */
    CoChangeReport analyzeCommits(List<List<String>> commits, int minSupport, Set<String> importLinks) {
        // 1. Count per-file total commits
        Map<String, Integer> fileTotals = new HashMap<>();
        for (List<String> commit : commits) {
            for (String f : commit) {
                fileTotals.merge(f, 1, Integer::sum);
            }
        }

        // 2. Count co-occurrences
        Map<String, Integer> coOccurrences = new HashMap<>();
        for (List<String> commit : commits) {
            List<String> sorted = new ArrayList<>(commit);
            Collections.sort(sorted);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    String key = sorted.get(i) + "|" + sorted.get(j);
                    coOccurrences.merge(key, 1, Integer::sum);
                }
            }
        }

        // 3. Build pairs above minSupport
        List<CoChangePair> all = new ArrayList<>();
        Set<String> knownLinks = importLinks != null ? importLinks : Set.of();

        for (Map.Entry<String, Integer> e : coOccurrences.entrySet()) {
            if (e.getValue() < minSupport) continue;
            String[] parts = e.getKey().split("\\|", 2);
            if (parts.length != 2) continue;
            String a = parts[0], b = parts[1];
            int totalA = fileTotals.getOrDefault(a, 1);
            int totalB = fileTotals.getOrDefault(b, 1);
            double ratio = (double) e.getValue() / Math.min(totalA, totalB);
            boolean hasLink = knownLinks.contains(a + "|" + b) || knownLinks.contains(b + "|" + a);
            all.add(new CoChangePair(a, b, e.getValue(), totalA, totalB, ratio, hasLink));
        }

        // 4. Sort by ratio descending
        all.sort(Comparator.comparingDouble(CoChangePair::ratio).reversed());

        List<CoChangePair> high = all.stream()
                .filter(p -> p.ratio() > 0.8)
                .collect(Collectors.toList());
        List<CoChangePair> medium = all.stream()
                .filter(p -> p.ratio() >= 0.5 && p.ratio() <= 0.8)
                .collect(Collectors.toList());
        List<CoChangePair> unexpected = all.stream()
                .filter(p -> p.ratio() >= 0.5 && !p.hasImportLink())
                .collect(Collectors.toList());

        return new CoChangeReport(high, medium, unexpected);
    }

    /**
     * Parse git log --name-only to get changed files per commit.
     * Returns a list where each inner list = files changed in one commit.
     */
    List<List<String>> parseGitLog(Path workspaceRoot, int lookbackCommits) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            "git", "log", "--name-only", "--pretty=format:COMMIT", "-n", String.valueOf(lookbackCommits)
        );
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());

        List<List<String>> commits = new ArrayList<>();
        List<String> current = null;
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.equals("COMMIT")) {
                if (current != null && !current.isEmpty()) commits.add(current);
                current = new ArrayList<>();
            } else if (!line.isEmpty() && current != null) {
                if (line.endsWith(".java") || line.endsWith(".sql")
                        || line.endsWith(".md") || line.endsWith(".yaml")) {
                    current.add(line);
                }
            }
        }
        if (current != null && !current.isEmpty()) commits.add(current);
        return commits;
    }

    /**
     * Format the report as a printable string.
     */
    public String format(CoChangeReport report, int minSupport) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nCO-CHANGE CLUSTERS (min ").append(minSupport).append(" co-commits):\n");

        if (!report.highCoupling().isEmpty()) {
            sb.append("\nHIGH coupling (>80% co-change):\n");
            for (CoChangePair p : report.highCoupling()) {
                sb.append(String.format("  %-45s <-> %-45s  (%d/%d commits)%s%n",
                    shortName(p.fileA()), shortName(p.fileB()),
                    p.coCommitCount(), Math.min(p.totalCommitsA(), p.totalCommitsB()),
                    p.hasImportLink() ? "" : " [no import link]"));
            }
        }

        if (!report.mediumCoupling().isEmpty()) {
            sb.append("\nMEDIUM coupling (50-80% co-change):\n");
            for (CoChangePair p : report.mediumCoupling()) {
                sb.append(String.format("  %-45s <-> %-45s  (%d/%d commits)%n",
                    shortName(p.fileA()), shortName(p.fileB()),
                    p.coCommitCount(), Math.min(p.totalCommitsA(), p.totalCommitsB())));
            }
        }

        List<CoChangePair> unexpectedHighlight = report.unexpected().stream()
            .filter(p -> !p.hasImportLink() && p.ratio() > 0.5)
            .collect(Collectors.toList());
        if (!unexpectedHighlight.isEmpty()) {
            sb.append("\nUNEXPECTED coupling (no import relationship, but frequently co-committed):\n");
            for (CoChangePair p : unexpectedHighlight) {
                sb.append(String.format("  [!] %-40s <-> %-40s  (%d/%d commits)%n",
                    shortName(p.fileA()), shortName(p.fileB()),
                    p.coCommitCount(), Math.min(p.totalCommitsA(), p.totalCommitsB())));
            }
        }

        if (report.highCoupling().isEmpty() && report.mediumCoupling().isEmpty()) {
            sb.append("\n  No co-change pairs found above threshold.\n");
        }

        return sb.toString();
    }

    private String shortName(String path) {
        int slash = path.lastIndexOf("/");
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
