package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HealthCommand} static audit helpers.
 */
class HealthCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SynthesisConfig configWith(String... paths) {
        SynthesisConfig config = new SynthesisConfig();
        List<SubWorkspaceConfig> subs = new ArrayList<>();
        for (String path : paths) {
            SubWorkspaceConfig sw = new SubWorkspaceConfig();
            sw.setName(path);   // use path as name for simplicity in tests
            sw.setPath(path);
            subs.add(sw);
        }
        config.setSubWorkspaces(subs);
        return config;
    }

    // -------------------------------------------------------------------------
    // findPhantomSubWorkspaces
    // -------------------------------------------------------------------------

    @Test
    void findPhantomSubWorkspaces_noPhantoms_whenAllDirsExist() throws IOException {
        Files.createDirectories(workspace.resolve("clients"));
        Files.createDirectories(workspace.resolve("archive"));

        SynthesisConfig config = configWith("clients", "archive");
        assertTrue(HealthCommand.findPhantomSubWorkspaces(workspace, config).isEmpty());
    }

    @Test
    void findPhantomSubWorkspaces_detectsMissingDirectory() throws IOException {
        SynthesisConfig config = configWith("clients/@active/Elprint");
        List<SubWorkspaceConfig> result = HealthCommand.findPhantomSubWorkspaces(workspace, config);
        assertEquals(1, result.size());
        assertEquals("clients/@active/Elprint", result.get(0).getPath());
    }

    @Test
    void findPhantomSubWorkspaces_multiplePhantoms() throws IOException {
        Files.createDirectories(workspace.resolve("realdir"));
        SynthesisConfig config = configWith("realdir", "ghost1", "ghost2");
        List<SubWorkspaceConfig> phantoms =
                HealthCommand.findPhantomSubWorkspaces(workspace, config);
        assertEquals(2, phantoms.size());
    }

    @Test
    void findPhantomSubWorkspaces_emptyConfig_returnsEmpty() throws IOException {
        SynthesisConfig config = new SynthesisConfig();
        assertTrue(HealthCommand.findPhantomSubWorkspaces(workspace, config).isEmpty());
    }

    // -------------------------------------------------------------------------
    // findBuildArtifacts
    // -------------------------------------------------------------------------

    @Test
    void findBuildArtifacts_detectsNodeModules() throws IOException {
        Path nm = workspace.resolve("frontend/node_modules");
        Files.createDirectories(nm);
        Files.writeString(nm.resolve("package.json"), "{}");

        List<Path> artifacts = HealthCommand.findBuildArtifacts(workspace);
        assertFalse(artifacts.isEmpty());
        assertTrue(artifacts.stream()
                .anyMatch(p -> p.getFileName().toString().equals("node_modules")));
    }

    @Test
    void findBuildArtifacts_detectsStrayClassFiles() throws IOException {
        // .class files NOT inside a target/ directory should be flagged
        Path dir = workspace.resolve("archive/java-old");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Foo.class"), "bytes");

        List<Path> artifacts = HealthCommand.findBuildArtifacts(workspace);
        assertFalse(artifacts.isEmpty());
    }

    @Test
    void findBuildArtifacts_ignoresClassFilesInsideTarget() throws IOException {
        Path targetDir = workspace.resolve("myapp/target/Foo.class");
        Files.createDirectories(targetDir.getParent());
        Files.writeString(targetDir, "bytes");

        assertTrue(HealthCommand.findBuildArtifacts(workspace).isEmpty());
    }

    @Test
    void findBuildArtifacts_noArtifacts_returnsEmpty() throws IOException {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/README.md"), "# Docs");
        assertTrue(HealthCommand.findBuildArtifacts(workspace).isEmpty());
    }

    // -------------------------------------------------------------------------
    // findEmptyDirectories
    // -------------------------------------------------------------------------

    @Test
    void findEmptyDirectories_detectsEmptyDir() throws IOException {
        Files.createDirectories(workspace.resolve("empty-placeholder"));
        List<Path> empty = HealthCommand.findEmptyDirectories(workspace);
        assertEquals(1, empty.size());
    }

    @Test
    void findEmptyDirectories_noEmptyDirs_whenFilesPresent() throws IOException {
        Path dir = workspace.resolve("docs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("README.md"), "content");
        assertTrue(HealthCommand.findEmptyDirectories(workspace).isEmpty());
    }

    @Test
    void findEmptyDirectories_ignoresDotDirs() throws IOException {
        Files.createDirectories(workspace.resolve(".synthesis/empty"));
        // .synthesis is a hidden dir — should be skipped
        assertTrue(HealthCommand.findEmptyDirectories(workspace).isEmpty());
    }

    // -------------------------------------------------------------------------
    // countLooseRootFiles
    // -------------------------------------------------------------------------

    @Test
    void countLooseRootFiles_countsUnexpectedRootFiles() throws IOException {
        Files.writeString(workspace.resolve("TONIGHT-PLAN.md"), "plan");
        Files.writeString(workspace.resolve("finish-overnight.sh"), "#!/bin/bash");
        assertEquals(2, HealthCommand.countLooseRootFiles(workspace));
    }

    @Test
    void countLooseRootFiles_excludesKnownConfigFiles() throws IOException {
        Files.writeString(workspace.resolve("synthesis-config.yaml"), "");
        Files.writeString(workspace.resolve("README.md"), "");
        Files.writeString(workspace.resolve("ACTIVITY-LOG.md"), "");
        Files.writeString(workspace.resolve("CLAUDE.md"), "");
        assertEquals(0, HealthCommand.countLooseRootFiles(workspace));
    }

    @Test
    void countLooseRootFiles_excludesHiddenFiles() throws IOException {
        Files.writeString(workspace.resolve(".DS_Store"), "");
        Files.writeString(workspace.resolve(".gitignore"), "");
        assertEquals(0, HealthCommand.countLooseRootFiles(workspace));
    }

    @Test
    void countLooseRootFiles_doesNotCountSubdirectories() throws IOException {
        Files.createDirectories(workspace.resolve("clients"));
        Files.createDirectories(workspace.resolve("archive"));
        assertEquals(0, HealthCommand.countLooseRootFiles(workspace));
    }

    // -------------------------------------------------------------------------
    // calculateScore and scoreGrade
    // -------------------------------------------------------------------------

    @Test
    void calculateScore_noIssues_returns100() {
        assertEquals(100, HealthCommand.calculateScore(List.of()));
    }

    @Test
    void calculateScore_oneError_returns85() {
        var issue = new HealthCommand.HealthIssue(
                HealthCommand.HealthIssue.Severity.ERROR, "E001", "test");
        assertEquals(85, HealthCommand.calculateScore(List.of(issue)));
    }

    @Test
    void calculateScore_oneWarning_returns95() {
        var issue = new HealthCommand.HealthIssue(
                HealthCommand.HealthIssue.Severity.WARNING, "W001", "test");
        assertEquals(95, HealthCommand.calculateScore(List.of(issue)));
    }

    @Test
    void calculateScore_twoErrorsTwoWarnings_returns60() {
        var e1 = new HealthCommand.HealthIssue(HealthCommand.HealthIssue.Severity.ERROR, "E001", "a");
        var e2 = new HealthCommand.HealthIssue(HealthCommand.HealthIssue.Severity.ERROR, "E002", "b");
        var w1 = new HealthCommand.HealthIssue(HealthCommand.HealthIssue.Severity.WARNING, "W001", "c");
        var w2 = new HealthCommand.HealthIssue(HealthCommand.HealthIssue.Severity.WARNING, "W002", "d");
        assertEquals(60, HealthCommand.calculateScore(List.of(e1, e2, w1, w2)));
    }

    @Test
    void calculateScore_clampsToZero() {
        List<HealthCommand.HealthIssue> issues = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            issues.add(new HealthCommand.HealthIssue(
                    HealthCommand.HealthIssue.Severity.ERROR, "E" + i, "e"));
        }
        assertEquals(0, HealthCommand.calculateScore(issues));
    }

    @ParameterizedTest
    @CsvSource({"100,Excellent", "90,Excellent", "89,Good", "75,Good", "74,Fair", "60,Fair", "59,Poor", "0,Poor"})
    void scoreGrade_returnsCorrectGrade(int score, String expectedGrade) {
        assertEquals(expectedGrade, HealthCommand.scoreGrade(score));
    }

    // -------------------------------------------------------------------------
    // levenshtein
    // -------------------------------------------------------------------------

    @Test
    void levenshtein_identicalStrings_returnsZero() {
        assertEquals(0, HealthCommand.levenshtein("elprint", "elprint"));
    }

    @Test
    void levenshtein_emptyStrings_returnsZero() {
        assertEquals(0, HealthCommand.levenshtein("", ""));
    }

    @Test
    void levenshtein_oneDeletion_returnsOne() {
        assertEquals(1, HealthCommand.levenshtein("hello", "helo"));
    }

    @Test
    void levenshtein_oneInsertion_returnsOne() {
        assertEquals(1, HealthCommand.levenshtein("helo", "hello"));
    }

    @Test
    void levenshtein_oneSubstitution_returnsOne() {
        assertEquals(1, HealthCommand.levenshtein("cat", "bat"));
    }

    @Test
    void levenshtein_completelyDifferent_returnsLargeValue() {
        assertTrue(HealthCommand.levenshtein("abc", "xyz") >= 3);
    }

    // -------------------------------------------------------------------------
    // findBestMatch
    // -------------------------------------------------------------------------

    @Test
    void findBestMatch_exactNameMatch() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("Elprint"));
        List<Path> candidates = List.of(dir);
        String match = HealthCommand.findBestMatch(
                "clients/@active/Elprint", candidates, workspace);
        assertEquals("Elprint", match);
    }

    @Test
    void findBestMatch_candidateWithOpportunityPrefix() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("opportunity-Mynder"));
        List<Path> candidates = List.of(dir);
        String match = HealthCommand.findBestMatch(
                "clients/@opportunities/Mynder", candidates, workspace);
        assertEquals("opportunity-Mynder", match);
    }

    @Test
    void findBestMatch_noSuitableMatch_returnsNull() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("completely-unrelated"));
        List<Path> candidates = List.of(dir);
        String match = HealthCommand.findBestMatch(
                "clients/@active/Elprint", candidates, workspace);
        assertNull(match);
    }

    @Test
    void findBestMatch_allAtSymbolSegments_returnsNull() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("something"));
        List<Path> candidates = List.of(dir);
        String match = HealthCommand.findBestMatch(
                "clients/@active/@opportunities", candidates, workspace);
        assertNull(match, "Phantom path with only @-segments should return null");
    }

    @Test
    void findBestMatch_substringMatch() throws IOException {
        // "CatalystOne-past" contains "CatalystOne"
        Path dir = Files.createDirectories(workspace.resolve("CatalystOne-past"));
        List<Path> candidates = List.of(dir);
        String match = HealthCommand.findBestMatch(
                "clients/@past/CatalystOne-past", candidates, workspace);
        // exact suffix match — should resolve
        assertNotNull(match);
    }

    // -------------------------------------------------------------------------
    // formatSize
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "0,          0 B",
        "512,        512 B",
        "1024,       1 KB",
        "2048,       2 KB",
        "1048576,    1 MB",
        "10485760,   10 MB"
    })
    void formatSize_returnsCorrectUnit(long bytes, String expected) {
        assertEquals(expected, HealthCommand.formatSize(bytes));
    }

    // -------------------------------------------------------------------------
    // saveConfig
    // -------------------------------------------------------------------------

    @Test
    void saveConfig_updatesPathInYamlFile() throws IOException {
        // Create a minimal synthesis-config.yaml
        String yaml = "subWorkspaces:\n  - name: Elprint\n    path: clients/@active/Elprint\n";
        Path configFile = workspace.resolve("synthesis-config.yaml");
        Files.writeString(configFile, yaml);

        // Build config with updated path
        SynthesisConfig config = configWith("Elprint");  // name=Elprint, path=Elprint (remapped)
        config.getSubWorkspaces().get(0).setPath("clients/Elprint");

        HealthCommand.saveConfig(workspace, config);

        String updated = Files.readString(configFile);
        assertTrue(updated.contains("clients/Elprint"),
                "Updated YAML should contain the new path");
        assertFalse(updated.contains("clients/@active/Elprint"),
                "Updated YAML should not contain the old phantom path");
    }

    @Test
    void saveConfig_fallsBackToInternalConfig() throws IOException {
        // Create .synthesis/config.yaml instead of root config
        Path internalConfig = workspace.resolve(".synthesis/config.yaml");
        Files.createDirectories(internalConfig.getParent());
        Files.writeString(internalConfig,
                "subWorkspaces:\n  - name: Inbox\n    path: old/Inbox\n");

        SynthesisConfig config = configWith("Inbox");
        config.getSubWorkspaces().get(0).setPath("Inbox");

        HealthCommand.saveConfig(workspace, config);

        String updated = Files.readString(internalConfig);
        assertTrue(updated.contains("Inbox"));
        assertFalse(updated.contains("old/Inbox"));
    }
}
