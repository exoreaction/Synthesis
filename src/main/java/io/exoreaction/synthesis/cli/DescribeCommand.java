package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.org.ArchetypeRegistry.ArchetypeMatch;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Read-only CLI command that explains what the system understands about a
 * directory (or the full workspace).
 *
 * <p>Displays centroid + wants + identity data in a human-readable format,
 * including semantic topics, key entities, timeframe, document types,
 * confidence levels, and want satisfaction.
 *
 * <p>Usage:
 * <pre>
 *   synthesis describe                            # workspace-level summary
 *   synthesis describe clients/opportunity-nova/  # directory-level detail
 * </pre>
 *
 * @since v1.14.0 (P2-08)
 */
@Command(
        name = "describe",
        description = "Show what the system understands about a directory or workspace",
        mixinStandardHelpOptions = true
)
public class DescribeCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "Directory to describe (relative to workspace root). Omit for workspace summary.",
            arity = "0..1"
    )
    private Path targetDir;

    /** Output stream for testability (defaults to System.out). */
    private PrintStream out = System.out;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            if (targetDir != null) {
                // Directory-level detail
                Path resolvedDir = targetDir.isAbsolute()
                        ? targetDir
                        : workspaceRoot.resolve(targetDir);
                return describeDirectory(workspaceRoot, resolvedDir);
            } else {
                // Workspace-level summary
                return describeWorkspace(workspaceRoot);
            }
        } catch (Exception e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Describes a single directory in detail: centroid, wants, scope, confidence.
     */
    int describeDirectory(Path workspaceRoot, Path directory) {
        if (!Files.isDirectory(directory)) {
            out.println("Not a directory: " + directory);
            return 1;
        }

        Path synthesisFile = directory.resolve(".synthesis.md");
        String relativePath = workspaceRoot.relativize(directory).toString();

        if (!Files.exists(synthesisFile)) {
            out.println(relativePath + "/");
            out.println("  No .synthesis.md file found.");
            out.println("  Run 'synthesis sync' to discover directory identity.");
            return 0;
        }

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryProfile profile = parser.parseProfile(synthesisFile);

        out.println(relativePath + "/");

        // Identity / scope
        DirectoryIdentity identity = profile.identity();
        if (identity.scopeLevel() != null) {
            StringBuilder scopeLine = new StringBuilder();
            scopeLine.append("  Scope: ").append(identity.scopeLevel().name());
            if (identity.scopeOrganization() != null) {
                scopeLine.append(" / ").append(identity.scopeOrganization());
            }
            if (identity.scopeEntity() != null) {
                scopeLine.append(" / ").append(identity.scopeEntity());
            }
            out.println(scopeLine);
        }

        // Accepts
        if (!identity.acceptsTypes().isEmpty()) {
            out.println("  Accepts: " + String.join(", ", identity.acceptsTypes()));
        }
        if (!identity.acceptsFormats().isEmpty()) {
            out.println("  Formats: " + String.join(", ", identity.acceptsFormats()));
        }

        // Rejects
        if (!identity.rejectsTypes().isEmpty()) {
            out.println("  Rejects: " + String.join(", ", identity.rejectsTypes()));
        }

        // Transient
        if (identity.transient_()) {
            out.println("  Transient: true (staging/landing zone)");
        }

        // Identity confidence
        out.println("  Identity confidence: " + formatConfidence(identity.confidence())
                + " (" + String.format("%.2f", identity.confidence()) + ")");
        if (identity.source() != null && !identity.source().isEmpty()) {
            out.println("  Source: " + identity.source());
        }

        // Centroid
        DirectoryCentroid centroid = profile.centroid();
        if (!centroid.isEmpty()) {
            out.println();
            out.println("  Centroid (what the directory IS):");
            if (!centroid.topics().isEmpty()) {
                out.println("    Topics: " + String.join(", ", centroid.topics()));
            }
            if (!centroid.entities().isEmpty()) {
                out.println("    Key entities: " + String.join(", ", centroid.entities()));
            }
            if (centroid.timeframe() != null) {
                out.println("    Timeframe: " + centroid.timeframe());
            }
            if (!centroid.documentTypes().isEmpty()) {
                out.println("    Document types: " + String.join(", ", centroid.documentTypes()));
            }
            out.println("    Confidence: " + formatConfidence(centroid.confidence())
                    + " (" + String.format("%.2f", centroid.confidence())
                    + ", " + centroid.contributingFiles() + " enriched files)");
        } else {
            out.println();
            out.println("  Centroid: none (no enriched files)");
            out.println("    Run 'synthesis sync --enrich-centroids' to compute");
        }

        // Archetype match and gaps (P4-02)
        if (!centroid.isEmpty()) {
            GapAnalyzer gapAnalyzer = new GapAnalyzer();
            java.util.Optional<GapAnalyzer.GapAnalysisResult> gapResult =
                    gapAnalyzer.analyze(centroid);
            if (gapResult.isPresent()) {
                GapAnalyzer.GapAnalysisResult gap = gapResult.get();
                out.println();
                out.println("  Archetype match: \"" + gap.archetypeName()
                        + "\" (" + String.format("%.2f", gap.matchScore()) + ")");
                if (!gap.missingDocTypes().isEmpty()) {
                    out.println("    Gaps: "
                            + gap.missingDocTypes().stream()
                                    .map(dt -> dt + " (missing)")
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("none"));
                } else {
                    out.println("    No document type gaps detected");
                }
            }
        }

        // Wants
        DirectoryWants wants = profile.wants();
        if (!wants.isEmpty()) {
            out.println();
            out.println("  Wants (what the directory is TRYING TO BECOME):");
            if (!wants.topics().isEmpty()) {
                out.println("    Topics: " + String.join(", ", wants.topics()));
            }
            if (!wants.entities().isEmpty()) {
                out.println("    Entities: " + String.join(", ", wants.entities()));
            }
            if (!wants.alsoLookingFor().isEmpty()) {
                out.println("    Also looking for: " + String.join(", ", wants.alsoLookingFor()));
            }
            out.println("    Satisfaction: " + String.format("%.0f%%", wants.satisfaction() * 100));
            if (wants.source() != null) {
                out.println("    Source: " + wants.source());
            }
        }

        return 0;
    }

    /**
     * Describes the workspace: top directories by centroid confidence,
     * starving directories, and drifting directories.
     */
    int describeWorkspace(Path workspaceRoot) throws IOException {
        out.println("Workspace: " + workspaceRoot);
        out.println();

        DirectoryIdentityParser parser = new DirectoryIdentityParser();

        // Collect all profiles
        List<DirectoryProfileEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> Files.exists(dir.resolve(".synthesis.md")))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        String relativePath = workspaceRoot.relativize(dir).toString();
                        entries.add(new DirectoryProfileEntry(relativePath, dir, profile));
                    });
        }

        if (entries.isEmpty()) {
            out.println("  No directories with .synthesis.md files found.");
            out.println("  Run 'synthesis sync' to discover directory identities.");
            return 0;
        }

        // Top directories by centroid confidence
        List<DirectoryProfileEntry> withCentroids = entries.stream()
                .filter(e -> !e.profile.centroid().isEmpty())
                .sorted(Comparator.comparingDouble(
                        (DirectoryProfileEntry e) -> e.profile.centroid().confidence())
                        .reversed())
                .toList();

        if (!withCentroids.isEmpty()) {
            out.println("  Top directories by centroid confidence:");
            int shown = 0;
            for (DirectoryProfileEntry entry : withCentroids) {
                if (shown >= 10) break;
                DirectoryCentroid c = entry.profile.centroid();
                out.printf("    %-40s %s (%.2f, %d files)%n",
                        entry.relativePath + "/",
                        formatConfidence(c.confidence()),
                        c.confidence(),
                        c.contributingFiles());
                if (!c.topics().isEmpty()) {
                    String topicsStr = c.topics().size() > 3
                            ? String.join(", ", c.topics().subList(0, 3)) + "..."
                            : String.join(", ", c.topics());
                    out.println("      Topics: " + topicsStr);
                }
                shown++;
            }
            out.println();
        }

        // Starving directories (wants with low satisfaction)
        List<DirectoryProfileEntry> starving = entries.stream()
                .filter(e -> !e.profile.wants().isEmpty())
                .filter(e -> e.profile.wants().satisfaction() < 0.1)
                .sorted(Comparator.comparing(e -> e.relativePath))
                .toList();

        if (!starving.isEmpty()) {
            out.println("  Starving directories (wants unsatisfied):");
            for (DirectoryProfileEntry entry : starving) {
                DirectoryWants w = entry.profile.wants();
                out.printf("    %-40s satisfaction: %.0f%%%n",
                        entry.relativePath + "/",
                        w.satisfaction() * 100);
                if (!w.topics().isEmpty()) {
                    out.println("      Wants: " + String.join(", ", w.topics()));
                }
            }
            out.println();
        }

        // Summary
        long totalDirs = entries.size();
        long withCentroidCount = withCentroids.size();
        long withWantsCount = entries.stream()
                .filter(e -> !e.profile.wants().isEmpty())
                .count();

        out.println("  Summary: " + totalDirs + " directories total, "
                + withCentroidCount + " with centroids, "
                + withWantsCount + " with wants");

        return 0;
    }

    /**
     * Formats a confidence value as a human-readable label.
     */
    static String formatConfidence(double confidence) {
        if (confidence >= 0.8) return "HIGH";
        if (confidence >= 0.5) return "MEDIUM";
        if (confidence >= 0.2) return "LOW";
        return "VERY LOW";
    }

    /** Package-private setters for testing. */
    void setParent(SynthesisApp parent) {
        this.parent = parent;
    }

    void setTargetDir(Path targetDir) {
        this.targetDir = targetDir;
    }

    void setOut(PrintStream out) {
        this.out = out;
    }

    /** Internal helper record for workspace summary. */
    private record DirectoryProfileEntry(
            String relativePath,
            Path directory,
            DirectoryProfile profile
    ) {}
}
