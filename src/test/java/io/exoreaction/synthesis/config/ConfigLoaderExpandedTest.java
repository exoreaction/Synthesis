package io.exoreaction.synthesis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expanded tests for ConfigLoader — resolveSubWorkspace, resolveSubWorkspaceScanConfig, validate.
 */
class ConfigLoaderExpandedTest {

    @TempDir
    Path tempDir;

    // === resolveSubWorkspace ===

    @Test
    void resolveSubWorkspace_nullRelativePath_returnsNull() {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("MyOrg", "MyOrg")
        );
        assertNull(ConfigLoader.resolveSubWorkspace(null, subWorkspaces));
    }

    @Test
    void resolveSubWorkspace_emptySubWorkspaces_returnsNull() {
        assertNull(ConfigLoader.resolveSubWorkspace("eXOReaction/README.md", List.of()));
    }

    @Test
    void resolveSubWorkspace_nullSubWorkspaces_returnsNull() {
        assertNull(ConfigLoader.resolveSubWorkspace("eXOReaction/README.md", null));
    }

    @Test
    void resolveSubWorkspace_matchingPrefix_returnsName() {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("eXOReaction", "eXOReaction")
        );
        assertEquals("eXOReaction", ConfigLoader.resolveSubWorkspace(
                "eXOReaction/README.md", subWorkspaces));
    }

    @Test
    void resolveSubWorkspace_noMatchingPrefix_returnsNull() {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("eXOReaction", "eXOReaction")
        );
        assertNull(ConfigLoader.resolveSubWorkspace(
                "Quadim/README.md", subWorkspaces));
    }

    @Test
    void resolveSubWorkspace_longestPrefixWins() {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("Root", "eXOReaction"),
                sw("Clients", "eXOReaction/clients")
        );
        // Should match the longer prefix "eXOReaction/clients"
        assertEquals("Clients", ConfigLoader.resolveSubWorkspace(
                "eXOReaction/clients/Elprint/README.md", subWorkspaces));
    }

    @Test
    void resolveSubWorkspace_rootLevelFile_matchesRootPrefix() {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("eXOReaction", "eXOReaction"),
                sw("Quadim", "Quadim")
        );
        assertEquals("eXOReaction", ConfigLoader.resolveSubWorkspace(
                "eXOReaction/SomeFile.md", subWorkspaces));
    }

    @Test
    void resolveSubWorkspace_exactPathMatch_returnsName() {
        // File at exactly the prefix path (not inside it)
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("eXOReaction", "eXOReaction")
        );
        // relativePath equals the prefix exactly (no trailing slash)
        assertEquals("eXOReaction", ConfigLoader.resolveSubWorkspace(
                "eXOReaction", subWorkspaces));
    }

    @ParameterizedTest
    @CsvSource({
        "eXOReaction/file.md, eXOReaction",
        "Quadim/file.md,      Quadim",
        "Other/file.md,       ~null~"
    })
    void resolveSubWorkspace_parameterized(String path, String expectedName) {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("eXOReaction", "eXOReaction"),
                sw("Quadim", "Quadim")
        );
        String result = ConfigLoader.resolveSubWorkspace(path, subWorkspaces);
        if ("~null~".equals(expectedName)) {
            assertNull(result, "Expected null for path: " + path);
        } else {
            assertEquals(expectedName, result, "Expected match for path: " + path);
        }
    }

    @Test
    void resolveSubWorkspace_subWorkspaceWithEmptyPath_isSkipped() {
        List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces = List.of(
                sw("EmptyPath", ""),
                sw("ValidOrg", "ValidOrg")
        );
        // Empty path sub-workspace should be skipped
        assertEquals("ValidOrg", ConfigLoader.resolveSubWorkspace(
                "ValidOrg/README.md", subWorkspaces));
    }

    // === resolveSubWorkspaceScanConfig ===

    @Test
    void resolveSubWorkspaceScanConfig_noOverride_inheritsParentIncludePatterns() {
        SynthesisConfig.ScanConfig parent = new SynthesisConfig.ScanConfig();
        parent.setIncludePatterns(List.of("**/*.java", "**/*.md"));

        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Test", "test");
        // No includePatterns override
        SynthesisConfig.ScanConfig resolved = ConfigLoader.resolveSubWorkspaceScanConfig(parent, sw);

        assertEquals(parent.getIncludePatterns(), resolved.getIncludePatterns(),
                "Without override, sub-workspace should inherit parent include patterns");
    }

    @Test
    void resolveSubWorkspaceScanConfig_withIncludeOverride_usesSubWorkspacePatterns() {
        SynthesisConfig.ScanConfig parent = new SynthesisConfig.ScanConfig();
        parent.setIncludePatterns(List.of("**/*.java", "**/*.md"));

        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Test", "test");
        sw.setIncludePatterns(List.of("**/*.sql", "**/*.xml"));

        SynthesisConfig.ScanConfig resolved = ConfigLoader.resolveSubWorkspaceScanConfig(parent, sw);

        assertEquals(List.of("**/*.sql", "**/*.xml"), resolved.getIncludePatterns(),
                "With override, sub-workspace include patterns take precedence");
    }

    @Test
    void resolveSubWorkspaceScanConfig_excludePatterns_addedToParent() {
        SynthesisConfig.ScanConfig parent = new SynthesisConfig.ScanConfig();
        parent.setExcludePatterns(List.of("**/target/**"));

        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Test", "test");
        sw.setExcludePatterns(List.of("**/archive/**"));

        SynthesisConfig.ScanConfig resolved = ConfigLoader.resolveSubWorkspaceScanConfig(parent, sw);

        assertTrue(resolved.getExcludePatterns().contains("**/target/**"),
                "Parent excludes should be in resolved");
        assertTrue(resolved.getExcludePatterns().contains("**/archive/**"),
                "Sub-workspace excludes should be added to parent");
    }

    @Test
    void resolveSubWorkspaceScanConfig_duplicateExcludes_notDuplicated() {
        SynthesisConfig.ScanConfig parent = new SynthesisConfig.ScanConfig();
        parent.setExcludePatterns(List.of("**/target/**", "**/node_modules/**"));

        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Test", "test");
        sw.setExcludePatterns(List.of("**/target/**")); // duplicate

        SynthesisConfig.ScanConfig resolved = ConfigLoader.resolveSubWorkspaceScanConfig(parent, sw);

        long targetCount = resolved.getExcludePatterns().stream()
                .filter("**/target/**"::equals).count();
        assertEquals(1, targetCount, "Duplicate exclude patterns should not be added twice");
    }

    @Test
    void resolveSubWorkspaceScanConfig_inheritsComputeHashes() {
        SynthesisConfig.ScanConfig parent = new SynthesisConfig.ScanConfig();
        parent.setComputeHashes(false);

        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Test", "test");
        SynthesisConfig.ScanConfig resolved = ConfigLoader.resolveSubWorkspaceScanConfig(parent, sw);

        assertFalse(resolved.isComputeHashes(), "computeHashes should be inherited from parent");
    }

    @Test
    void resolveSubWorkspaceScanConfig_inheritsMaxFileSizeBytes() {
        SynthesisConfig.ScanConfig parent = new SynthesisConfig.ScanConfig();
        parent.setMaxFileSizeBytes(5 * 1024 * 1024); // 5 MB

        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Test", "test");
        SynthesisConfig.ScanConfig resolved = ConfigLoader.resolveSubWorkspaceScanConfig(parent, sw);

        assertEquals(5L * 1024 * 1024, resolved.getMaxFileSizeBytes(),
                "maxFileSizeBytes should be inherited from parent");
    }

    // === validate ===

    @Test
    void validate_defaultConfig_isValid() {
        SynthesisConfig config = new SynthesisConfig();
        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isEmpty(), "Default config should be valid");
    }

    @Test
    void validate_negativeMaxFileSize_returnsError() {
        SynthesisConfig config = new SynthesisConfig();
        config.getScan().setMaxFileSizeBytes(-1);
        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Negative maxFileSizeBytes should be invalid");
        assertTrue(error.get().contains("maxFileSizeBytes"));
    }

    @Test
    void validate_zeroMaxFileSize_returnsError() {
        SynthesisConfig config = new SynthesisConfig();
        config.getScan().setMaxFileSizeBytes(0);
        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Zero maxFileSizeBytes should be invalid");
    }

    @Test
    void validate_zeroMaxResults_returnsError() {
        SynthesisConfig config = new SynthesisConfig();
        config.getSearch().setMaxResults(0);
        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Zero maxResults should be invalid");
        assertTrue(error.get().contains("maxResults"));
    }

    @Test
    void validate_negativeMaxResults_returnsError() {
        SynthesisConfig config = new SynthesisConfig();
        config.getSearch().setMaxResults(-5);
        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Negative maxResults should be invalid");
    }

    @Test
    void validate_subWorkspaceWithEmptyName_returnsError() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.SubWorkspaceConfig badSw = new SynthesisConfig.SubWorkspaceConfig();
        badSw.setName("");
        badSw.setPath("some/path");
        config.setSubWorkspaces(List.of(badSw));

        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Sub-workspace with empty name should be invalid");
        assertTrue(error.get().contains("name"), "Error message should mention 'name'");
    }

    @Test
    void validate_subWorkspaceWithEmptyPath_returnsError() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.SubWorkspaceConfig badSw = new SynthesisConfig.SubWorkspaceConfig();
        badSw.setName("ValidName");
        badSw.setPath("");
        config.setSubWorkspaces(List.of(badSw));

        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Sub-workspace with empty path should be invalid");
        assertTrue(error.get().contains("path"), "Error message should mention 'path'");
    }

    @Test
    void validate_validSubWorkspace_passes() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("MyOrg", "MyOrg");
        config.setSubWorkspaces(List.of(sw));
        Optional<String> error = ConfigLoader.validate(config);
        assertTrue(error.isEmpty(), "Valid sub-workspace should not produce an error");
    }

    // === generateDefaultConfig ===

    @Test
    void generateDefaultConfig_containsWorkspaceName() throws IOException {
        String yaml = ConfigLoader.generateDefaultConfig("my-project", "monorepo");
        assertTrue(yaml.contains("my-project"));
    }

    @Test
    void generateDefaultConfig_containsWorkspaceType() throws IOException {
        String yaml = ConfigLoader.generateDefaultConfig("test-ws", "source-code");
        assertTrue(yaml.contains("source-code"));
    }

    @Test
    void generateDefaultConfig_aiDisabledByDefault() throws IOException {
        String yaml = ConfigLoader.generateDefaultConfig("test", "general");
        assertTrue(yaml.contains("enabled: false"));
    }

    @Test
    void generateDefaultConfig_producesReadableYaml() throws IOException {
        String yaml = ConfigLoader.generateDefaultConfig("roundtrip-test", "monorepo");
        Path configFile = tempDir.resolve("test-config.yaml");
        Files.writeString(configFile, yaml);

        SynthesisConfig loaded = ConfigLoader.loadFromFile(configFile);
        assertEquals("roundtrip-test", loaded.getWorkspace().getName());
    }

    // === load ===

    @Test
    void load_missingConfig_returnsDefaultConfig() throws IOException {
        SynthesisConfig config = ConfigLoader.load(tempDir);
        assertNotNull(config);
        assertNotNull(config.getWorkspace());
    }

    @Test
    void load_synthesisDirConfig_isRead() throws IOException {
        Path configDir = tempDir.resolve(".synthesis");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                workspace:
                  name: "synthesis-dir-ws"
                """);

        SynthesisConfig config = ConfigLoader.load(tempDir);
        assertEquals("synthesis-dir-ws", config.getWorkspace().getName());
    }

    // === helpers ===

    private static SynthesisConfig.SubWorkspaceConfig sw(String name, String path) {
        return new SynthesisConfig.SubWorkspaceConfig(name, path);
    }
}
