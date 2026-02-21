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
 * Tests for {@link W020Check} -- want starvation health signal.
 */
class W020CheckTest {

    @TempDir
    Path tempDir;

    private final W020Check check = new W020Check();

    /**
     * Creates a directory with wants and a specific last sync time.
     * Writes .synthesis.md directly (bypassing writeProfile which overwrites last_synced).
     */
    private void createDirectoryWithWants(String dirName, List<String> wantsTopics,
                                           List<String> wantsEntities,
                                           double satisfaction, Instant lastSynced)
            throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("synthesis:\n");
        sb.append("  accepts:\n");
        sb.append("    types:\n");
        sb.append("      - \"client\"\n");
        sb.append("    formats:\n");
        sb.append("      - \"md\"\n");
        sb.append("      - \"pdf\"\n");
        sb.append("  scope:\n");
        sb.append("    level: \"WORKSPACE\"\n");
        sb.append("    organization: null\n");
        sb.append("    entity: null\n");
        sb.append("  confidence: 0.5\n");
        sb.append("  last_synced: \"").append(lastSynced.toString()).append("\"\n");
        sb.append("  source: \"test\"\n");
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
     * Creates a directory with no wants.
     */
    private void createDirectoryWithoutWants(String dirName) throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("docs"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, Instant.now().minus(10, ChronoUnit.DAYS), "test", ""
        );

        DirectoryProfile profile = DirectoryProfile.fromIdentity(identity);
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);
    }

    @Test
    void check_starvingDirectory_detected() throws IOException {
        // Directory with wants, low satisfaction, old (7 days)
        createDirectoryWithWants("opportunity-nova",
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp"),
                0.0,
                Instant.now().minus(7, ChronoUnit.DAYS));

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("[W020]"));
        assertTrue(findings.get(0).message().contains("Nova Corp"));
        assertEquals(0.0, findings.get(0).satisfaction());
        assertTrue(findings.get(0).ageDays() >= 6); // At least 6 days
    }

    @Test
    void check_satisfiedDirectory_notDetected() throws IOException {
        // Directory with wants that are satisfied
        createDirectoryWithWants("opportunity-greenfield",
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                0.87,
                Instant.now().minus(7, ChronoUnit.DAYS));

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(), "Satisfied directory should not trigger W020");
    }

    @Test
    void check_newDirectory_notDetected() throws IOException {
        // Directory with wants, low satisfaction, but only 1 day old
        createDirectoryWithWants("opportunity-new",
                List.of("New Corp"),
                List.of(),
                0.0,
                Instant.now().minus(1, ChronoUnit.DAYS));

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(), "New directory (< 3 days) should not trigger W020");
    }

    @Test
    void check_directoryWithoutWants_notDetected() throws IOException {
        // Directory without wants
        createDirectoryWithoutWants("docs");

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(), "Directory without wants should not trigger W020");
    }

    @Test
    void check_multipleFindings() throws IOException {
        createDirectoryWithWants("opportunity-a",
                List.of("Topic A"), List.of("Entity A"),
                0.0, Instant.now().minus(10, ChronoUnit.DAYS));
        createDirectoryWithWants("opportunity-b",
                List.of("Topic B"), List.of("Entity B"),
                0.05, Instant.now().minus(5, ChronoUnit.DAYS));
        createDirectoryWithWants("opportunity-c",
                List.of("Topic C"), List.of(),
                0.5, Instant.now().minus(10, ChronoUnit.DAYS)); // satisfied enough

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertEquals(2, findings.size(), "Should find exactly 2 starving directories");
    }

    @Test
    void check_borderlineSatisfaction_notDetected() throws IOException {
        // Exactly at threshold (0.1) -- should NOT be detected
        createDirectoryWithWants("borderline",
                List.of("Topic"),
                List.of(),
                0.1,
                Instant.now().minus(5, ChronoUnit.DAYS));

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertTrue(findings.isEmpty(), "Satisfaction at threshold (0.1) should not trigger W020");
    }

    @Test
    void check_justBelowThreshold_detected() throws IOException {
        createDirectoryWithWants("almoststarving",
                List.of("Topic"),
                List.of(),
                0.09,
                Instant.now().minus(5, ChronoUnit.DAYS));

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertEquals(1, findings.size(), "Satisfaction just below threshold should trigger W020");
    }

    @Test
    void check_emptyWorkspace_noFindings() {
        List<W020Check.W020Finding> findings = check.check(tempDir);
        assertTrue(findings.isEmpty());
    }

    @Test
    void finding_messageFormat() throws IOException {
        createDirectoryWithWants("opportunity-nova",
                List.of("Nova Corp", "cloud"),
                List.of("Nova Corp"),
                0.0,
                Instant.now().minus(6, ChronoUnit.DAYS));

        List<W020Check.W020Finding> findings = check.check(tempDir);

        assertEquals(1, findings.size());
        String msg = findings.get(0).message();
        assertTrue(msg.startsWith("[W020]"), "Should start with [W020]");
        assertTrue(msg.contains("Want starvation"), "Should mention starvation");
        assertTrue(msg.contains("Satisfaction: 0.0"), "Should show satisfaction");
    }
}
