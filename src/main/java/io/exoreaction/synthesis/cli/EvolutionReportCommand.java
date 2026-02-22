package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis evolution} -- periodic structural evolution reports.
 *
 * <p>Shows how the workspace has evolved: which directories are growing,
 * which are starving, which wants were satisfied, which gaps persist.
 * Based on current state of .synthesis.md profiles.
 *
 * <p>Usage:
 * <pre>
 *   synthesis evolution                # full evolution report
 *   synthesis evolution --format json  # machine-readable
 * </pre>
 *
 * @since v2.0 (P4-08)
 */
@Command(
        name = "evolution",
        aliases = {"evo"},
        description = "Show workspace structural evolution report",
        mixinStandardHelpOptions = true
)
public class EvolutionReportCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--format", "-f"},
            description = "Output format: ascii, json (default: ascii)",
            defaultValue = "ascii")
    private String format;

    @Option(names = {"--output", "-o"},
            description = "Write output to file instead of stdout")
    private Path outputFile;

    /** Output stream for testability. */
    private PrintStream out = System.out;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            DirectoryIdentityParser parser = new DirectoryIdentityParser();

            String output = "json".equalsIgnoreCase(format)
                    ? renderJson(workspaceRoot, parser)
                    : renderAscii(workspaceRoot, parser);

            if (outputFile != null) {
                Files.writeString(outputFile, output);
                out.println("Evolution report written to: " + outputFile);
            } else {
                out.println(output);
            }

            return 0;
        } catch (Exception e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // ---- Data collection ----

    /**
     * Collects snapshots of all directories in the workspace.
     */
    List<DirectorySnapshot> collectSnapshots(Path workspaceRoot,
                                               DirectoryIdentityParser parser) throws IOException {
        List<DirectorySnapshot> snapshots = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        String relPath = workspaceRoot.relativize(dir).toString();

                        DirectoryCentroid centroid = profile.centroid();
                        DirectoryWants wants = profile.wants();
                        DirectoryHealth health = profile.health();

                        String status = health.isEmpty() ? "unknown" : health.status();
                        double confidence = centroid.isEmpty() ? 0.0 : centroid.confidence();
                        int contributingFiles = centroid.contributingFiles();
                        int virtualMembers = centroid.virtualMembers();
                        double wantSatisfaction = wants.isEmpty() ? -1.0 : wants.satisfaction();

                        List<String> topics = new ArrayList<>();
                        if (!centroid.isEmpty()) {
                            topics.addAll(centroid.topics());
                        } else if (!wants.isEmpty()) {
                            topics.addAll(wants.topics());
                        }

                        snapshots.add(new DirectorySnapshot(
                                relPath, status, confidence, contributingFiles,
                                virtualMembers, wantSatisfaction, topics));
                    });
        }

        snapshots.sort(Comparator.comparing(DirectorySnapshot::path));
        return snapshots;
    }

    /**
     * Computes an evolution summary from current snapshots.
     */
    EvolutionSummary computeSummary(Path workspaceRoot,
                                     DirectoryIdentityParser parser) throws IOException {
        List<DirectorySnapshot> snapshots = collectSnapshots(workspaceRoot, parser);

        int totalDirectories = snapshots.size();
        int withCentroids = 0;
        int withWants = 0;
        int healthy = 0;
        int starving = 0;
        int bootstrapping = 0;
        int drifting = 0;

        for (DirectorySnapshot s : snapshots) {
            if (s.confidence > 0.0) withCentroids++;
            if (s.wantSatisfaction >= 0.0) withWants++;
            switch (s.status) {
                case "healthy" -> healthy++;
                case "starving" -> starving++;
                case "bootstrapping" -> bootstrapping++;
                case "drifting" -> drifting++;
            }
        }

        return new EvolutionSummary(totalDirectories, withCentroids, withWants,
                healthy, starving, bootstrapping, drifting);
    }

    // ---- Rendering ----

    String renderAscii(Path workspaceRoot,
                        DirectoryIdentityParser parser) throws IOException {
        List<DirectorySnapshot> snapshots = collectSnapshots(workspaceRoot, parser);
        EvolutionSummary summary = computeSummary(workspaceRoot, parser);
        StringBuilder sb = new StringBuilder();

        sb.append("Evolution Report: ").append(workspaceRoot).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        // Summary
        sb.append("Summary\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append(String.format("  Total directories: %d%n", summary.totalDirectories));
        sb.append(String.format("  With centroids: %d%n", summary.withCentroids));
        sb.append(String.format("  With wants: %d%n", summary.withWants));
        sb.append(String.format("  Healthy: %d  |  Starving: %d  |  Bootstrapping: %d  |  Drifting: %d%n%n",
                summary.healthy, summary.starving, summary.bootstrapping, summary.drifting));

        // Growing (high confidence, many files)
        List<DirectorySnapshot> growing = snapshots.stream()
                .filter(s -> s.confidence >= 0.7 && s.contributingFiles >= 3)
                .sorted(Comparator.comparingDouble((DirectorySnapshot s) -> s.confidence).reversed())
                .toList();

        if (!growing.isEmpty()) {
            sb.append("Growing Directories (high confidence, active)\n");
            sb.append("-".repeat(40)).append("\n");
            for (DirectorySnapshot s : growing) {
                sb.append(String.format("  [OK] %s/ (%.2f, %d files)%n",
                        s.path, s.confidence, s.contributingFiles));
                if (!s.topics.isEmpty()) {
                    sb.append("        Topics: ").append(formatTopics(s.topics)).append("\n");
                }
            }
            sb.append("\n");
        }

        // Starving (wants unsatisfied)
        List<DirectorySnapshot> starvingList = snapshots.stream()
                .filter(s -> "starving".equals(s.status))
                .sorted(Comparator.comparing(DirectorySnapshot::path))
                .toList();

        if (!starvingList.isEmpty()) {
            sb.append("Starving Directories (wants unsatisfied)\n");
            sb.append("-".repeat(40)).append("\n");
            for (DirectorySnapshot s : starvingList) {
                sb.append(String.format("  [!!] %s/ (satisfaction: %.0f%%)%n",
                        s.path, s.wantSatisfaction * 100));
                if (!s.topics.isEmpty()) {
                    sb.append("        Wants: ").append(formatTopics(s.topics)).append("\n");
                }
            }
            sb.append("\n");
        }

        // Bootstrapping (newly created, building identity)
        List<DirectorySnapshot> bootList = snapshots.stream()
                .filter(s -> "bootstrapping".equals(s.status))
                .sorted(Comparator.comparing(DirectorySnapshot::path))
                .toList();

        if (!bootList.isEmpty()) {
            sb.append("Bootstrapping Directories (building identity)\n");
            sb.append("-".repeat(40)).append("\n");
            for (DirectorySnapshot s : bootList) {
                sb.append(String.format("  [..] %s/ (%.2f, %d files)%n",
                        s.path, s.confidence, s.contributingFiles));
            }
            sb.append("\n");
        }

        // Drifting (centroid and wants diverging)
        List<DirectorySnapshot> driftList = snapshots.stream()
                .filter(s -> "drifting".equals(s.status))
                .sorted(Comparator.comparing(DirectorySnapshot::path))
                .toList();

        if (!driftList.isEmpty()) {
            sb.append("Drifting Directories (centroid/wants diverging)\n");
            sb.append("-".repeat(40)).append("\n");
            for (DirectorySnapshot s : driftList) {
                sb.append(String.format("  [~>] %s/ (confidence: %.2f)%n",
                        s.path, s.confidence));
            }
            sb.append("\n");
        }

        // Satisfied wants (positive signal)
        List<DirectorySnapshot> satisfied = snapshots.stream()
                .filter(s -> s.wantSatisfaction >= 0.7)
                .sorted(Comparator.comparingDouble((DirectorySnapshot s) -> s.wantSatisfaction).reversed())
                .toList();

        if (!satisfied.isEmpty()) {
            sb.append("Satisfied Wants (goals being met)\n");
            sb.append("-".repeat(40)).append("\n");
            for (DirectorySnapshot s : satisfied) {
                sb.append(String.format("  [OK] %s/ (satisfaction: %.0f%%)%n",
                        s.path, s.wantSatisfaction * 100));
            }
            sb.append("\n");
        }

        if (snapshots.isEmpty()) {
            sb.append("  No directories with .synthesis.md files found.\n");
            sb.append("  Run 'synthesis sync' first.\n");
        }

        return sb.toString();
    }

    String renderJson(Path workspaceRoot,
                       DirectoryIdentityParser parser) throws IOException {
        List<DirectorySnapshot> snapshots = collectSnapshots(workspaceRoot, parser);
        EvolutionSummary summary = computeSummary(workspaceRoot, parser);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"workspace\": \"").append(escapeJson(workspaceRoot.toString())).append("\",\n");

        // Summary
        sb.append("  \"summary\": {\n");
        sb.append("    \"totalDirectories\": ").append(summary.totalDirectories).append(",\n");
        sb.append("    \"withCentroids\": ").append(summary.withCentroids).append(",\n");
        sb.append("    \"withWants\": ").append(summary.withWants).append(",\n");
        sb.append("    \"healthy\": ").append(summary.healthy).append(",\n");
        sb.append("    \"starving\": ").append(summary.starving).append(",\n");
        sb.append("    \"bootstrapping\": ").append(summary.bootstrapping).append(",\n");
        sb.append("    \"drifting\": ").append(summary.drifting).append("\n");
        sb.append("  },\n");

        // Directories
        sb.append("  \"directories\": [\n");
        for (int i = 0; i < snapshots.size(); i++) {
            DirectorySnapshot s = snapshots.get(i);
            sb.append("    {\n");
            sb.append("      \"path\": \"").append(escapeJson(s.path)).append("\",\n");
            sb.append("      \"status\": \"").append(s.status).append("\",\n");
            sb.append("      \"confidence\": ").append(String.format("%.2f", s.confidence)).append(",\n");
            sb.append("      \"contributingFiles\": ").append(s.contributingFiles).append(",\n");
            sb.append("      \"virtualMembers\": ").append(s.virtualMembers).append(",\n");
            sb.append("      \"wantSatisfaction\": ").append(
                    s.wantSatisfaction >= 0 ? String.format("%.2f", s.wantSatisfaction) : "null").append(",\n");
            sb.append("      \"topics\": ").append(jsonArray(s.topics)).append("\n");
            sb.append("    }");
            if (i < snapshots.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    // ---- Utilities ----

    private static String formatTopics(List<String> topics) {
        if (topics.size() > 3) {
            return String.join(", ", topics.subList(0, 3)) + "...";
        }
        return String.join(", ", topics);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private static String jsonArray(List<String> items) {
        if (items.isEmpty()) return "[]";
        return "[" + items.stream()
                .map(s -> "\"" + escapeJson(s) + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("") + "]";
    }

    // ---- Test support ----

    void setParent(SynthesisApp parent) { this.parent = parent; }
    void setFormat(String format) { this.format = format; }
    void setOut(PrintStream out) { this.out = out; }

    // ---- Records ----

    record DirectorySnapshot(
            String path,
            String status,
            double confidence,
            int contributingFiles,
            int virtualMembers,
            double wantSatisfaction,
            List<String> topics
    ) {}

    record EvolutionSummary(
            int totalDirectories,
            int withCentroids,
            int withWants,
            int healthy,
            int starving,
            int bootstrapping,
            int drifting
    ) {}
}
