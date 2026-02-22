package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * I020 health check: positive signal for want fulfillment.
 *
 * <p>Reports directories that are getting what they want -- high satisfaction
 * combined with a confident centroid and contributing files. This is the healthy
 * counterpart to W020 (starvation) and W021 (drift).
 *
 * <p>A directory qualifies for I020 when:
 * <ul>
 *   <li>It has wants with satisfaction >= 0.7 (HIGH), OR</li>
 *   <li>It has a centroid with confidence >= 0.6 and at least 3 contributing files</li>
 * </ul>
 *
 * <p>This check produces INFO-level findings (not warnings or errors) to help
 * users understand which directories are functioning well.
 *
 * @since v2.0 (P4-04)
 */
public class I020Check {

    /** Minimum satisfaction for want fulfillment (HIGH). */
    static final double SATISFACTION_THRESHOLD = 0.7;

    /** Minimum centroid confidence for healthy directory signal. */
    static final double CONFIDENCE_THRESHOLD = 0.6;

    /** Minimum contributing files for a meaningful centroid. */
    static final int MIN_CONTRIBUTING_FILES = 3;

    /**
     * An I020 finding: a directory that is fulfilling its wants.
     *
     * @param directory        the well-functioning directory
     * @param satisfaction     want satisfaction score (or 1.0 if no explicit wants)
     * @param centroidConfidence centroid confidence (0.0 if no centroid)
     * @param contributingFiles number of enriched files contributing to centroid
     * @param message          human-readable description
     */
    public record I020Finding(
            Path directory,
            double satisfaction,
            double centroidConfidence,
            int contributingFiles,
            String message
    ) {}

    /**
     * Checks for want fulfillment across the workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of I020 findings (INFO-level, positive signals)
     */
    public List<I020Finding> check(Path workspaceRoot) {
        List<I020Finding> findings = new ArrayList<>();
        DirectoryIdentityParser parser = new DirectoryIdentityParser();

        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        DirectoryWants wants = profile.wants();
                        DirectoryCentroid centroid = profile.centroid();

                        // Check: high satisfaction from wants
                        boolean highSatisfaction = wants != null && !wants.isEmpty()
                                && wants.satisfaction() >= SATISFACTION_THRESHOLD;

                        // Check: confident centroid with enough files
                        boolean confidentCentroid = centroid != null && !centroid.isEmpty()
                                && centroid.confidence() >= CONFIDENCE_THRESHOLD
                                && centroid.contributingFiles() >= MIN_CONTRIBUTING_FILES;

                        if (!highSatisfaction && !confidentCentroid) return;

                        double satisfaction = (wants != null && !wants.isEmpty())
                                ? wants.satisfaction() : 1.0;
                        double centroidConf = (centroid != null && !centroid.isEmpty())
                                ? centroid.confidence() : 0.0;
                        int files = (centroid != null && !centroid.isEmpty())
                                ? centroid.contributingFiles() : 0;

                        String relPath = workspaceRoot.relativize(dir).toString();

                        StringBuilder msg = new StringBuilder();
                        msg.append("[I020] Want fulfillment: ").append(relPath);
                        if (highSatisfaction) {
                            msg.append(String.format(" — satisfaction: %.0f%%", satisfaction * 100));
                        }
                        if (confidentCentroid) {
                            msg.append(String.format(" — centroid: %.2f (%d files)",
                                    centroidConf, files));
                        }

                        // Add topics summary
                        List<String> topics = new ArrayList<>();
                        if (centroid != null && !centroid.topics().isEmpty()) {
                            topics.addAll(centroid.topics().subList(
                                    0, Math.min(3, centroid.topics().size())));
                        } else if (wants != null && !wants.topics().isEmpty()) {
                            topics.addAll(wants.topics().subList(
                                    0, Math.min(3, wants.topics().size())));
                        }
                        if (!topics.isEmpty()) {
                            msg.append(" — topics: ").append(String.join(", ", topics));
                        }

                        findings.add(new I020Finding(
                                dir, satisfaction, centroidConf, files, msg.toString()));
                    });
        } catch (IOException e) {
            // Return whatever was collected
        }

        return findings;
    }
}
