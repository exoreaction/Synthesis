package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link W021Check} -- want drift health signal.
 *
 * <p>Drift means the centroid is confident but diverges from wants:
 * satisfaction < 0.4 AND centroid.confidence > 0.5.
 */
class W021CheckTest {

    @TempDir
    Path tempDir;

    /**
     * Writes a .synthesis.md with both centroid and wants, controlling all parameters.
     */
    private void createDirectoryWithCentroidAndWants(String dirName,
                                                       List<String> centroidTopics,
                                                       List<String> centroidEntities,
                                                       double centroidConfidence,
                                                       List<String> wantsTopics,
                                                       List<String> wantsEntities,
                                                       double satisfaction)
            throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        Instant lastSynced = Instant.now().minus(10, ChronoUnit.DAYS);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("synthesis:\n");
        sb.append("  accepts:\n");
        sb.append("    types:\n");
        sb.append("      - \"client\"\n");
        sb.append("  scope:\n");
        sb.append("    level: \"WORKSPACE\"\n");
        sb.append("    organization: null\n");
        sb.append("    entity: null\n");
        sb.append("  confidence: 0.5\n");
        sb.append("  last_synced: \"").append(lastSynced.toString()).append("\"\n");
        sb.append("  source: \"test\"\n");

        // centroid block
        sb.append("  centroid:\n");
        if (!centroidTopics.isEmpty()) {
            sb.append("    topics:\n");
            for (String t : centroidTopics) {
                sb.append("      - \"").append(t).append("\"\n");
            }
        }
        if (!centroidEntities.isEmpty()) {
            sb.append("    entities:\n");
            for (String e : centroidEntities) {
                sb.append("      - \"").append(e).append("\"\n");
            }
        }
        sb.append("    confidence: ").append(centroidConfidence).append("\n");
        sb.append("    contributing_files: 10\n");
        sb.append("    last_updated: \"").append(Instant.now().toString()).append("\"\n");

        // wants block
        sb.append("  wants:\n");
        if (!wantsTopics.isEmpty()) {
            sb.append("    topics:\n");
            for (String t : wantsTopics) {
                sb.append("      - \"").append(t).append("\"\n");
            }
        }
        if (!wantsEntities.isEmpty()) {
            sb.append("    entities:\n");
            for (String e : wantsEntities) {
                sb.append("      - \"").append(e).append("\"\n");
            }
        }
        sb.append("    source: \"inferred from directory name\"\n");
        sb.append("    satisfaction: ").append(satisfaction).append("\n");

        sb.append("---\n");
        Files.writeString(dir.resolve(".synthesis.md"), sb.toString());
    }

    /**
     * Creates a directory with only wants (no centroid) -- cannot drift.
     */
    private void createDirectoryWithOnlyWants(String dirName) throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("synthesis:\n");
        sb.append("  accepts:\n");
        sb.append("    types:\n");
        sb.append("      - \"docs\"\n");
        sb.append("  scope:\n");
        sb.append("    level: \"WORKSPACE\"\n");
        sb.append("    organization: null\n");
        sb.append("    entity: null\n");
        sb.append("  confidence: 0.5\n");
        sb.append("  last_synced: \"").append(Instant.now().minus(5, ChronoUnit.DAYS).toString()).append("\"\n");
        sb.append("  source: \"test\"\n");
        sb.append("  wants:\n");
        sb.append("    topics:\n");
        sb.append("      - \"Topic A\"\n");
        sb.append("    source: \"test\"\n");
        sb.append("    satisfaction: 0.0\n");
        sb.append("---\n");
        Files.writeString(dir.resolve(".synthesis.md"), sb.toString());
    }

    @Test
    void check_driftingDirectory_detected() throws IOException {
        // High-confidence centroid that diverges from wants (satisfaction=0.1)
        createDirectoryWithCentroidAndWants("clients/nova",
                List.of("cloud migration", "DevOps"),
                List.of("Nova Corp"),
                0.85,
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp"),
                0.1);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("[W021]"));
        assertTrue(findings.get(0).message().contains("Want drift"));
        assertEquals(0.1, findings.get(0).satisfaction());
        assertEquals(0.85, findings.get(0).centroidConfidence());
    }

    @Test
    void check_alignedDirectory_notDetected() throws IOException {
        // High satisfaction, no drift
        createDirectoryWithCentroidAndWants("clients/greenfield",
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                0.9,
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                0.85);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(), "Well-aligned directory should not trigger W021");
    }

    @Test
    void check_lowConfidenceCentroid_notDetected() throws IOException {
        // Low satisfaction but centroid not confident enough to diagnose drift
        createDirectoryWithCentroidAndWants("new-project",
                List.of("Topic A"),
                List.of(),
                0.3,  // Low confidence
                List.of("Topic B"),
                List.of(),
                0.1);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(),
                "Low-confidence centroid should not trigger drift (could be cold-start noise)");
    }

    @Test
    void check_wantsOnlyDirectory_notDetected() throws IOException {
        // No centroid means no drift (just starvation, which W020 handles)
        createDirectoryWithOnlyWants("docs");

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(),
                "Wants-only directory without centroid cannot drift");
    }

    @Test
    void check_borderlineSatisfaction_notDetected() throws IOException {
        // Exactly at threshold (0.4) -- should NOT trigger
        createDirectoryWithCentroidAndWants("borderline",
                List.of("Topic"),
                List.of(),
                0.8,
                List.of("Topic"),
                List.of(),
                0.4);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(),
                "Satisfaction at threshold (0.4) should not trigger W021");
    }

    @Test
    void check_borderlineConfidence_notDetected() throws IOException {
        // Exactly at confidence threshold (0.5) -- should NOT trigger
        createDirectoryWithCentroidAndWants("borderline-conf",
                List.of("Topic"),
                List.of(),
                0.5,
                List.of("Other Topic"),
                List.of(),
                0.2);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(),
                "Centroid confidence at threshold (0.5) should not trigger W021");
    }

    @Test
    void check_justBelowThresholds_detected() throws IOException {
        // Both just inside the "drift" zone
        createDirectoryWithCentroidAndWants("almostdrifting",
                List.of("Topic"),
                List.of(),
                0.51,  // Just above confidence threshold
                List.of("Other Topic"),
                List.of(),
                0.39);  // Just below satisfaction threshold

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertEquals(1, findings.size(),
                "Both thresholds just inside drift zone should trigger W021");
    }

    @Test
    void check_multipleFindings() throws IOException {
        createDirectoryWithCentroidAndWants("drifting-a",
                List.of("Topic A"),
                List.of(),
                0.9,
                List.of("Topic X"),
                List.of(),
                0.05);

        createDirectoryWithCentroidAndWants("drifting-b",
                List.of("Topic B"),
                List.of("Entity B"),
                0.7,
                List.of("Topic Y"),
                List.of("Entity Y"),
                0.2);

        createDirectoryWithCentroidAndWants("not-drifting",
                List.of("Topic C"),
                List.of(),
                0.9,
                List.of("Topic C"),
                List.of(),
                0.8);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertEquals(2, findings.size(), "Should find exactly 2 drifting directories");
    }

    @Test
    void check_emptyWorkspace_noFindings() {
        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);
        assertTrue(findings.isEmpty());
    }

    @Test
    void finding_messageFormat() throws IOException {
        createDirectoryWithCentroidAndWants("clients/acme",
                List.of("cloud migration"),
                List.of("ACME Corp"),
                0.88,
                List.of("ACME Corp", "enterprise architecture"),
                List.of("ACME Corp"),
                0.15);

        W021Check check = new W021Check();
        List<W021Check.W021Finding> findings = check.check(tempDir);

        assertEquals(1, findings.size());
        String msg = findings.get(0).message();
        assertTrue(msg.startsWith("[W021]"), "Should start with [W021]");
        assertTrue(msg.contains("Want drift"), "Should mention drift");
        assertTrue(msg.contains("Satisfaction: 0.2") || msg.contains("Satisfaction: 0.1"),
                "Should show satisfaction (formatted)");
        assertTrue(msg.contains("Centroid confidence: 0.9") || msg.contains("Centroid confidence: 0.88"),
                "Should show centroid confidence");
    }
}
