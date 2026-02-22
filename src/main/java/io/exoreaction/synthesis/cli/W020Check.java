package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryClassification;
import io.exoreaction.synthesis.org.DirectoryClassifier;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.DirectoryProfile;
import io.exoreaction.synthesis.org.DirectoryWants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * W020 health check: detects directories with starving wants.
 *
 * <p>A directory is "starving" when it has clear wants (topics/entities)
 * but very low satisfaction (< 0.1) and has existed for more than 3 days.
 * This signals that the directory was created for a purpose that is not
 * being served -- possibly an abandoned opportunity or misconfigured routing.
 *
 * <p>Does NOT fire for directories without wants (no false positives).
 *
 * @since v1.15.0 (P3-06)
 */
public class W020Check {

    /** Satisfaction threshold below which starvation is reported. */
    static final double STARVATION_THRESHOLD = 0.1;

    /** Minimum age in days before starvation is reported. */
    static final int MIN_AGE_DAYS = 3;

    /**
     * A W020 finding.
     *
     * @param directory    the directory that is starving
     * @param wants        the wants that are unsatisfied
     * @param satisfaction the current satisfaction score
     * @param ageDays      days since directory creation/last sync
     * @param message      human-readable description
     */
    public record W020Finding(
            Path directory,
            DirectoryWants wants,
            double satisfaction,
            long ageDays,
            String message
    ) {}

    /**
     * Checks for want starvation across the workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of W020 findings
     */
    public List<W020Finding> check(Path workspaceRoot) {
        List<W020Finding> findings = new ArrayList<>();
        DirectoryIdentityParser parser = new DirectoryIdentityParser();

        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        // Skip CODE and MEDIA directories -- starvation is not meaningful
                        DirectoryClassification classification =
                                DirectoryClassifier.classify(dir, workspaceRoot);
                        if (classification == DirectoryClassification.CODE
                                || classification == DirectoryClassification.MEDIA) {
                            return;
                        }

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        DirectoryWants wants = profile.wants();

                        // Skip directories without wants (no false positives)
                        if (wants == null || wants.isEmpty()) return;

                        // Check satisfaction threshold
                        if (wants.satisfaction() >= STARVATION_THRESHOLD) return;

                        // Check age -- use lastSynced from identity or file creation time
                        long ageDays = computeAgeDays(profile, synthesisFile);
                        if (ageDays < MIN_AGE_DAYS) return;

                        String relPath = workspaceRoot.relativize(dir).toString();
                        String wantsTopics = String.join(", ", wants.topics());
                        String wantsEntities = String.join(", ", wants.entities());
                        String wantsSummary = wantsTopics;
                        if (!wantsEntities.isEmpty()) {
                            wantsSummary += (wantsSummary.isEmpty() ? "" : ", ") + wantsEntities;
                        }

                        String message = String.format(
                                "[W020] Want starvation: %s — Wants: %s, Satisfaction: %.1f, Days: %d",
                                relPath, wantsSummary, wants.satisfaction(), ageDays);

                        findings.add(new W020Finding(dir, wants, wants.satisfaction(), ageDays, message));
                    });
        } catch (IOException e) {
            // Return whatever was collected
        }

        return findings;
    }

    /**
     * Computes age in days from the directory's last sync time or file modification time.
     */
    static long computeAgeDays(DirectoryProfile profile, Path synthesisFile) {
        Instant reference = null;

        // Try lastSynced from identity
        if (profile.identity().lastSynced() != null) {
            reference = profile.identity().lastSynced();
        }

        // Fall back to file modification time
        if (reference == null) {
            try {
                reference = Files.getLastModifiedTime(synthesisFile).toInstant();
            } catch (IOException e) {
                reference = Instant.now();
            }
        }

        return Duration.between(reference, Instant.now()).toDays();
    }
}
