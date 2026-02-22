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
 * {@code synthesis analyze} -- structural analysis of workspace knowledge graph.
 *
 * <p>Surfaces emerging patterns, orphan files, topic/entity fragmentation,
 * and aspirational gaps across the entire workspace.
 *
 * <p>Usage:
 * <pre>
 *   synthesis analyze                  # full structural analysis
 *   synthesis analyze --orphans        # files with no semantic home
 *   synthesis analyze --fragmentation  # concepts split across dirs
 *   synthesis analyze --gaps           # aspirational gaps across workspace
 *   synthesis analyze --format json    # machine-readable output
 * </pre>
 *
 * @since v2.0 (P4-06)
 */
@Command(
        name = "structure",
        aliases = {"structural-analysis"},
        description = "Structural analysis of workspace knowledge graph (orphans, fragmentation, gaps)",
        mixinStandardHelpOptions = true
)
public class StructuralAnalysisCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--orphans"}, description = "Show only orphan files (no semantic home)")
    private boolean orphansOnly;

    @Option(names = {"--fragmentation"}, description = "Show only topic/entity fragmentation")
    private boolean fragmentationOnly;

    @Option(names = {"--gaps"}, description = "Show only aspirational gaps")
    private boolean gapsOnly;

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

            String output;
            if ("json".equalsIgnoreCase(format)) {
                output = renderJson(workspaceRoot, parser);
            } else {
                if (orphansOnly) {
                    output = renderOrphansOnly(workspaceRoot, parser);
                } else if (fragmentationOnly) {
                    output = renderFragmentationOnly(workspaceRoot, parser);
                } else if (gapsOnly) {
                    output = renderGapsOnly(workspaceRoot, parser);
                } else {
                    output = renderFullAnalysis(workspaceRoot, parser);
                }
            }

            if (outputFile != null) {
                Files.writeString(outputFile, output);
                out.println("Analysis written to: " + outputFile);
            } else {
                out.println(output);
            }

            return 0;
        } catch (Exception e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // ---- Analysis methods ----

    /**
     * Detects topic and entity fragmentation: concepts that appear in multiple
     * directories, suggesting the content is scattered rather than consolidated.
     *
     * @return map of concept name -> list of directory paths where it appears
     */
    Map<String, List<String>> detectFragmentation(Path workspaceRoot,
                                                    DirectoryIdentityParser parser) throws IOException {
        List<DirectoryProfileEntry> entries = collectProfiles(workspaceRoot, parser);

        // Collect topic occurrences
        Map<String, List<String>> conceptDirs = new LinkedHashMap<>();

        for (DirectoryProfileEntry entry : entries) {
            DirectoryCentroid centroid = entry.profile.centroid();
            if (centroid.isEmpty()) continue;

            for (String topic : centroid.topics()) {
                conceptDirs.computeIfAbsent(topic, k -> new ArrayList<>())
                        .add(entry.relativePath);
            }
            for (String entity : centroid.entities()) {
                conceptDirs.computeIfAbsent(entity, k -> new ArrayList<>())
                        .add(entry.relativePath);
            }
        }

        // Keep only concepts that appear in 2+ directories
        Map<String, List<String>> fragmented = new LinkedHashMap<>();
        conceptDirs.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) ->
                        e.getValue().size()).reversed())
                .forEach(e -> fragmented.put(e.getKey(), e.getValue()));

        return fragmented;
    }

    /**
     * Detects aspirational gaps across the workspace: directories matching known
     * archetypes that are missing expected document types.
     */
    List<WorkspaceGap> detectGaps(Path workspaceRoot,
                                   DirectoryIdentityParser parser) throws IOException {
        List<DirectoryProfileEntry> entries = collectProfiles(workspaceRoot, parser);
        GapAnalyzer gapAnalyzer = new GapAnalyzer();
        List<WorkspaceGap> gaps = new ArrayList<>();

        for (DirectoryProfileEntry entry : entries) {
            DirectoryCentroid centroid = entry.profile.centroid();
            if (centroid.isEmpty()) continue;

            Optional<GapAnalyzer.GapAnalysisResult> result = gapAnalyzer.analyze(centroid);
            if (result.isPresent()) {
                GapAnalyzer.GapAnalysisResult gap = result.get();
                if (!gap.missingDocTypes().isEmpty()) {
                    gaps.add(new WorkspaceGap(
                            entry.relativePath,
                            gap.archetypeName(),
                            gap.matchScore(),
                            gap.missingDocTypes()));
                }
            }
        }

        gaps.sort(Comparator.comparingDouble((WorkspaceGap g) -> g.matchScore).reversed());
        return gaps;
    }

    /**
     * Detects orphan files: files in enriched directories that lack enrichment
     * companion data, suggesting they may not semantically belong anywhere.
     */
    List<String> detectOrphans(Path workspaceRoot,
                                DirectoryIdentityParser parser) throws IOException {
        List<DirectoryProfileEntry> entries = collectProfiles(workspaceRoot, parser);
        List<String> orphans = new ArrayList<>();

        for (DirectoryProfileEntry entry : entries) {
            DirectoryCentroid centroid = entry.profile.centroid();
            if (centroid.isEmpty()) continue;

            Path dir = workspaceRoot.resolve(entry.relativePath);
            if (!Files.isDirectory(dir)) continue;

            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> !f.getFileName().toString().startsWith("."))
                        .filter(f -> !f.getFileName().toString().endsWith(".synthesis.md"))
                        .forEach(f -> {
                            // Check if this file has a companion enrichment file
                            Path companion = f.getParent().resolve(
                                    f.getFileName() + ".synthesis.md");
                            if (!Files.exists(companion)) {
                                String relPath = workspaceRoot.relativize(f).toString();
                                orphans.add(relPath);
                            }
                        });
            }
        }

        orphans.sort(Comparator.naturalOrder());
        return orphans;
    }

    // ---- Rendering ----

    String renderFullAnalysis(Path workspaceRoot,
                               DirectoryIdentityParser parser) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Structural Analysis: ").append(workspaceRoot).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        // Fragmentation
        Map<String, List<String>> fragmented = detectFragmentation(workspaceRoot, parser);
        sb.append("Fragmentation Analysis\n");
        sb.append("-".repeat(40)).append("\n");
        if (fragmented.isEmpty()) {
            sb.append("  No fragmentation detected.\n");
            sb.append("  All concepts are consolidated in single directories.\n");
        } else {
            sb.append(String.format("  %d concept(s) spread across multiple directories:%n%n",
                    fragmented.size()));
            for (Map.Entry<String, List<String>> entry : fragmented.entrySet()) {
                sb.append(String.format("  \"%s\" (%d directories):%n",
                        entry.getKey(), entry.getValue().size()));
                for (String dir : entry.getValue()) {
                    sb.append(String.format("    - %s/%n", dir));
                }
                sb.append("\n");
            }
        }

        // Gaps
        List<WorkspaceGap> gaps = detectGaps(workspaceRoot, parser);
        sb.append("\nAspirations & Gaps\n");
        sb.append("-".repeat(40)).append("\n");
        if (gaps.isEmpty()) {
            sb.append("  No archetype gaps detected.\n");
        } else {
            sb.append(String.format("  %d directory(s) with document type gaps:%n%n",
                    gaps.size()));
            for (WorkspaceGap gap : gaps) {
                sb.append(String.format("  %s/ (matches \"%s\" archetype, score: %.2f)%n",
                        gap.directoryPath, gap.archetypeName, gap.matchScore));
                sb.append("    Missing: ");
                sb.append(String.join(", ", gap.missingDocTypes));
                sb.append("\n\n");
            }
        }

        // Orphans
        List<String> orphans = detectOrphans(workspaceRoot, parser);
        sb.append("\nOrphan Files\n");
        sb.append("-".repeat(40)).append("\n");
        if (orphans.isEmpty()) {
            sb.append("  No orphan files detected.\n");
        } else {
            sb.append(String.format("  %d file(s) without enrichment companions:%n%n",
                    orphans.size()));
            int shown = 0;
            for (String orphan : orphans) {
                if (shown >= 50) {
                    sb.append(String.format("  ... and %d more%n",
                            orphans.size() - shown));
                    break;
                }
                sb.append(String.format("    %s%n", orphan));
                shown++;
            }
        }

        // Summary
        List<DirectoryProfileEntry> entries = collectProfiles(workspaceRoot, parser);
        long withCentroids = entries.stream()
                .filter(e -> !e.profile.centroid().isEmpty())
                .count();
        long withWants = entries.stream()
                .filter(e -> !e.profile.wants().isEmpty())
                .count();

        sb.append("\nSummary\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append(String.format("  Directories: %d (%d with centroids, %d with wants)%n",
                entries.size(), withCentroids, withWants));
        sb.append(String.format("  Fragmented concepts: %d%n", fragmented.size()));
        sb.append(String.format("  Archetype gaps: %d%n", gaps.size()));
        sb.append(String.format("  Orphan files: %d%n", orphans.size()));

        return sb.toString();
    }

    String renderOrphansOnly(Path workspaceRoot,
                              DirectoryIdentityParser parser) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Orphan Files: ").append(workspaceRoot).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        List<String> orphans = detectOrphans(workspaceRoot, parser);
        if (orphans.isEmpty()) {
            sb.append("  No orphan files detected.\n");
            sb.append("  All files in enriched directories have companion data.\n");
        } else {
            sb.append(String.format("  %d file(s) without enrichment companions:%n%n",
                    orphans.size()));
            for (String orphan : orphans) {
                sb.append(String.format("    %s%n", orphan));
            }
            sb.append("\n  To fix: run 'synthesis sync --enrich-centroids' to enrich these files.\n");
        }

        return sb.toString();
    }

    String renderFragmentationOnly(Path workspaceRoot,
                                    DirectoryIdentityParser parser) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Fragmentation Analysis: ").append(workspaceRoot).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        Map<String, List<String>> fragmented = detectFragmentation(workspaceRoot, parser);
        if (fragmented.isEmpty()) {
            sb.append("  No fragmentation detected.\n");
        } else {
            sb.append(String.format("  %d concept(s) spread across multiple directories:%n%n",
                    fragmented.size()));
            for (Map.Entry<String, List<String>> entry : fragmented.entrySet()) {
                sb.append(String.format("  \"%s\" (%d directories):%n",
                        entry.getKey(), entry.getValue().size()));
                for (String dir : entry.getValue()) {
                    sb.append(String.format("    - %s/%n", dir));
                }
                sb.append("\n");
            }
            sb.append("  Consider consolidating related content into single directories.\n");
        }

        return sb.toString();
    }

    String renderGapsOnly(Path workspaceRoot,
                           DirectoryIdentityParser parser) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Aspirational Gaps: ").append(workspaceRoot).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        List<WorkspaceGap> gaps = detectGaps(workspaceRoot, parser);
        if (gaps.isEmpty()) {
            sb.append("  No archetype gaps detected.\n");
        } else {
            sb.append(String.format("  %d directory(s) with document type gaps:%n%n",
                    gaps.size()));
            for (WorkspaceGap gap : gaps) {
                sb.append(String.format("  %s/ (matches \"%s\" archetype, score: %.2f)%n",
                        gap.directoryPath, gap.archetypeName, gap.matchScore));
                sb.append("    Missing: ");
                sb.append(String.join(", ", gap.missingDocTypes));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    String renderJson(Path workspaceRoot,
                       DirectoryIdentityParser parser) throws IOException {
        Map<String, List<String>> fragmented = detectFragmentation(workspaceRoot, parser);
        List<WorkspaceGap> gaps = detectGaps(workspaceRoot, parser);
        List<String> orphans = detectOrphans(workspaceRoot, parser);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"workspace\": \"").append(escapeJson(workspaceRoot.toString())).append("\",\n");

        // Fragmentation
        sb.append("  \"fragmentation\": [\n");
        int fIdx = 0;
        for (Map.Entry<String, List<String>> entry : fragmented.entrySet()) {
            sb.append("    {\n");
            sb.append("      \"concept\": \"").append(escapeJson(entry.getKey())).append("\",\n");
            sb.append("      \"directories\": ").append(jsonArray(entry.getValue())).append("\n");
            sb.append("    }");
            if (fIdx < fragmented.size() - 1) sb.append(",");
            sb.append("\n");
            fIdx++;
        }
        sb.append("  ],\n");

        // Gaps
        sb.append("  \"gaps\": [\n");
        for (int i = 0; i < gaps.size(); i++) {
            WorkspaceGap gap = gaps.get(i);
            sb.append("    {\n");
            sb.append("      \"directory\": \"").append(escapeJson(gap.directoryPath)).append("\",\n");
            sb.append("      \"archetype\": \"").append(escapeJson(gap.archetypeName)).append("\",\n");
            sb.append("      \"matchScore\": ").append(String.format("%.2f", gap.matchScore)).append(",\n");
            sb.append("      \"missingDocTypes\": ").append(jsonArray(gap.missingDocTypes)).append("\n");
            sb.append("    }");
            if (i < gaps.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Orphans
        sb.append("  \"orphans\": ").append(jsonArray(orphans)).append("\n");
        sb.append("}\n");

        return sb.toString();
    }

    // ---- Internal helpers ----

    private List<DirectoryProfileEntry> collectProfiles(Path workspaceRoot,
                                                         DirectoryIdentityParser parser) throws IOException {
        List<DirectoryProfileEntry> entries = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        String relPath = workspaceRoot.relativize(dir).toString();
                        entries.add(new DirectoryProfileEntry(relPath, profile));
                    });
        }

        entries.sort(Comparator.comparing(e -> e.relativePath));
        return entries;
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

    record WorkspaceGap(
            String directoryPath,
            String archetypeName,
            double matchScore,
            List<String> missingDocTypes
    ) {}

    private record DirectoryProfileEntry(
            String relativePath,
            DirectoryProfile profile
    ) {}
}
