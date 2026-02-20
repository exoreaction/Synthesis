package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SweepCommand} static helpers.
 */
class SweepCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // isEphemeralName
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "TONIGHT-COMPLETE-PLAN.md",
            "TONIGHT-PROCESSING-README.md",
            "READY-TO-RUN.md",
            "PRE-FLIGHT-CHECK.sh",
            "PROCESS-ALL-TONIGHT.sh",
            "BIGPROJECT-COMPLETE.md",
            "SESSION-PLAN.md",
            "DEPLOY-STATUS.md"
    })
    void isEphemeralName_returnsTrueForEphemeralPatterns(String name) {
        assertTrue(SweepCommand.isEphemeralName(name),
                name + " should be identified as ephemeral");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "README.md",
            "ACTIVITY-LOG.md",
            "synthesis-config.yaml",
            "important-docs.md",
            "client-report.pdf"
    })
    void isEphemeralName_returnsFalseForNormalFiles(String name) {
        assertFalse(SweepCommand.isEphemeralName(name),
                name + " should NOT be identified as ephemeral");
    }

    // -------------------------------------------------------------------------
    // classify
    // -------------------------------------------------------------------------

    @Test
    void classify_zipFile_isArtifact_regardlessOfAge() throws IOException {
        Path f = workspace.resolve("export.zip");
        Files.writeString(f, "data");
        // Even a brand-new zip file should be classified
        SweepCommand.SweepCandidate result = SweepCommand.classify(f, Instant.now().plusSeconds(3600));
        assertNotNull(result);
        assertEquals(SweepCommand.Category.ARTIFACTS, result.category());
    }

    @Test
    void classify_oldShellScript_isScript() throws IOException {
        Path f = workspace.resolve("cleanup.sh");
        Files.writeString(f, "#!/bin/bash\necho done");
        setOldModifiedTime(f, 60);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        SweepCommand.SweepCandidate result = SweepCommand.classify(f, cutoff);
        assertNotNull(result);
        assertEquals(SweepCommand.Category.SCRIPTS, result.category());
    }

    @Test
    void classify_recentShellScript_isNotSwept() throws IOException {
        Path f = workspace.resolve("new-script.sh");
        Files.writeString(f, "#!/bin/bash\necho hi");
        // Modified today — should NOT be a candidate
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        assertNull(SweepCommand.classify(f, cutoff));
    }

    @Test
    void classify_oldEphemeralFile_isEphemeral() throws IOException {
        Path f = workspace.resolve("TONIGHT-NOTES.md");
        Files.writeString(f, "notes");
        setOldModifiedTime(f, 60);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        SweepCommand.SweepCandidate result = SweepCommand.classify(f, cutoff);
        assertNotNull(result);
        assertEquals(SweepCommand.Category.EPHEMERAL, result.category());
    }

    @Test
    void classify_recentEphemeralFile_isNotSwept() throws IOException {
        Path f = workspace.resolve("TONIGHT-NOTES.md");
        Files.writeString(f, "notes");
        // Not old enough — below threshold
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        assertNull(SweepCommand.classify(f, cutoff));
    }

    @Test
    void classify_datedReport_isCompletedReport() throws IOException {
        Path f = workspace.resolve("2024-SCREENSHOT-DISCOVERY-REPORT.md");
        Files.writeString(f, "report");
        setOldModifiedTime(f, 90);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        SweepCommand.SweepCandidate result = SweepCommand.classify(f, cutoff);
        assertNotNull(result);
        assertEquals(SweepCommand.Category.COMPLETED_REPORTS, result.category());
    }

    @Test
    void classify_normalFile_isNotSwept() throws IOException {
        Path f = workspace.resolve("important-notes.md");
        Files.writeString(f, "notes");
        setOldModifiedTime(f, 90);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        assertNull(SweepCommand.classify(f, cutoff));
    }

    @Test
    void classify_dotFile_isIgnoredByFindCandidates() throws IOException {
        // The findCandidates method filters hidden files before classify
        Path f = workspace.resolve(".DS_Store");
        Files.writeString(f, "data");
        List<SweepCommand.SweepCandidate> candidates = SweepCommand.findCandidates(workspace, 0);
        assertTrue(candidates.stream()
                .noneMatch(c -> c.path().getFileName().toString().equals(".DS_Store")));
    }

    // -------------------------------------------------------------------------
    // findCandidates
    // -------------------------------------------------------------------------

    @Test
    void findCandidates_onlyScansRootLevel() throws IOException {
        // A stale script in a subdirectory should NOT be swept
        Path subdir = Files.createDirectories(workspace.resolve("subdir"));
        Path script = subdir.resolve("cleanup.sh");
        Files.writeString(script, "#!/bin/bash");
        setOldModifiedTime(script, 60);

        List<SweepCommand.SweepCandidate> candidates = SweepCommand.findCandidates(workspace, 30);
        assertTrue(candidates.stream()
                .noneMatch(c -> c.path().equals(script)),
                "Scripts in subdirectories should not be swept");
    }

    @Test
    void findCandidates_findsMixedCategories() throws IOException {
        Path zip = workspace.resolve("export.zip");
        Files.writeString(zip, "data");

        Path sh = workspace.resolve("batch.sh");
        Files.writeString(sh, "#!/bin/bash");
        setOldModifiedTime(sh, 60);

        Path ephemeral = workspace.resolve("TONIGHT-PLAN.md");
        Files.writeString(ephemeral, "plan");
        setOldModifiedTime(ephemeral, 60);

        List<SweepCommand.SweepCandidate> candidates = SweepCommand.findCandidates(workspace, 30);
        assertEquals(3, candidates.size());

        Set<SweepCommand.Category> categories = new java.util.HashSet<>();
        for (var c : candidates) categories.add(c.category());
        assertTrue(categories.contains(SweepCommand.Category.ARTIFACTS));
        assertTrue(categories.contains(SweepCommand.Category.SCRIPTS));
        assertTrue(categories.contains(SweepCommand.Category.EPHEMERAL));
    }

    @Test
    void findCandidates_emptyRoot_returnsEmpty() throws IOException {
        assertTrue(SweepCommand.findCandidates(workspace, 30).isEmpty());
    }

    // -------------------------------------------------------------------------
    // parseSelection
    // -------------------------------------------------------------------------

    @Test
    void parseSelection_singleNumber() {
        assertEquals(Set.of(2), SweepCommand.parseSelection("2", 5));
    }

    @Test
    void parseSelection_commaList() {
        assertEquals(Set.of(1, 3, 5), SweepCommand.parseSelection("1,3,5", 5));
    }

    @Test
    void parseSelection_range() {
        assertEquals(Set.of(2, 3, 4), SweepCommand.parseSelection("2-4", 5));
    }

    @Test
    void parseSelection_mixedRangeAndSingle() {
        Set<Integer> result = SweepCommand.parseSelection("1,3-5", 6);
        assertTrue(result.containsAll(Set.of(1, 3, 4, 5)));
    }

    @Test
    void parseSelection_clampsToBounds() {
        // Out-of-range numbers should be ignored
        Set<Integer> result = SweepCommand.parseSelection("0,5,10", 5);
        assertEquals(Set.of(5), result);
    }

    @Test
    void parseSelection_emptyInput_returnsEmpty() {
        assertTrue(SweepCommand.parseSelection("", 5).isEmpty());
    }

    @Test
    void parseSelection_invalidInput_returnsEmpty() {
        assertTrue(SweepCommand.parseSelection("abc", 5).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setOldModifiedTime(Path path, int daysAgo) throws IOException {
        Instant old = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        Files.setLastModifiedTime(path, FileTime.from(old));
    }
}
