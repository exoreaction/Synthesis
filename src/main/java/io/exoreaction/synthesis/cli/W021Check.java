package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryCentroid;
import io.exoreaction.synthesis.org.DirectoryClassification;
import io.exoreaction.synthesis.org.DirectoryClassifier;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.DirectoryProfile;
import io.exoreaction.synthesis.org.DirectoryWants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * W021 health check: detects directories with want drift.
 *
 * <p>A directory is "drifting" when it has a confident centroid
 * (confidence > 0.5) but low want satisfaction (< 0.4). This means
 * the directory's actual content has diverged from its stated purpose.
 *
 * <p>Unlike W020 (starvation), drift requires both a centroid AND wants
 * to be present -- it detects content-purpose misalignment in mature
 * directories rather than empty directories.
 *
 * <p>Does NOT fire for:
 * <ul>
 *   <li>Directories without wants (no purpose to drift from)</li>
 *   <li>Directories without centroids (cold-start, not drift)</li>
 *   <li>Low-confidence centroids (could be cold-start noise)</li>
 * </ul>
 *
 * @since v1.15.0 (P3-07)
 */
public class W021Check {

    /** Satisfaction threshold below which drift is reported. */
    static final double DRIFT_SATISFACTION_THRESHOLD = 0.4;

    /** Minimum centroid confidence required to diagnose drift. */
    static final double MIN_CENTROID_CONFIDENCE = 0.5;

    /**
     * A W021 finding.
     *
     * @param directory           the directory that is drifting
     * @param wants               the wants that are unsatisfied
     * @param satisfaction        the current satisfaction score
     * @param centroidConfidence  the centroid's confidence level
     * @param message             human-readable description
     */
    public record W021Finding(
            Path directory,
            DirectoryWants wants,
            double satisfaction,
            double centroidConfidence,
            String message
    ) {}

    /**
     * Checks for want drift across the workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of W021 findings
     */
    public List<W021Finding> check(Path workspaceRoot) {
        List<W021Finding> findings = new ArrayList<>();
        DirectoryIdentityParser parser = new DirectoryIdentityParser();

        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        // Skip CODE and MEDIA directories -- drift is not meaningful
                        DirectoryClassification classification =
                                DirectoryClassifier.classify(dir, workspaceRoot);
                        if (classification == DirectoryClassification.CODE
                                || classification == DirectoryClassification.MEDIA) {
                            return;
                        }

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        DirectoryWants wants = profile.wants();
                        DirectoryCentroid centroid = profile.centroid();

                        // Skip directories without wants (no purpose to drift from)
                        if (wants == null || wants.isEmpty()) return;

                        // Skip directories without centroid (cold-start, not drift)
                        if (centroid == null || centroid.isEmpty()) return;

                        // Skip low-confidence centroids (could be noise)
                        if (centroid.confidence() <= MIN_CENTROID_CONFIDENCE) return;

                        // Check satisfaction threshold (must be strictly below)
                        if (wants.satisfaction() >= DRIFT_SATISFACTION_THRESHOLD) return;

                        String relPath = workspaceRoot.relativize(dir).toString();
                        String wantsTopics = String.join(", ", wants.topics());
                        String wantsEntities = String.join(", ", wants.entities());
                        String wantsSummary = wantsTopics;
                        if (!wantsEntities.isEmpty()) {
                            wantsSummary += (wantsSummary.isEmpty() ? "" : ", ") + wantsEntities;
                        }

                        String message = String.format(
                                "[W021] Want drift: %s — Wants: %s, Satisfaction: %.1f, Centroid confidence: %.2f",
                                relPath, wantsSummary, wants.satisfaction(), centroid.confidence());

                        findings.add(new W021Finding(
                                dir, wants, wants.satisfaction(),
                                centroid.confidence(), message));
                    });
        } catch (IOException e) {
            // Return whatever was collected
        }

        return findings;
    }
}
