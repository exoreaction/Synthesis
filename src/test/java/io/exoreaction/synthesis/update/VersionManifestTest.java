package io.exoreaction.synthesis.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VersionManifest}.
 */
class VersionManifestTest {

    // --- Version comparison tests ---

    @Test
    void compareVersions_higherMajor() {
        assertTrue(VersionManifest.compareVersions("2.0.0", "1.0.0") > 0);
    }

    @Test
    void compareVersions_higherMinor() {
        assertTrue(VersionManifest.compareVersions("1.1.0", "1.0.0") > 0);
    }

    @Test
    void compareVersions_higherPatch() {
        assertTrue(VersionManifest.compareVersions("1.0.4", "1.0.3") > 0);
    }

    @Test
    void compareVersions_equal() {
        assertEquals(0, VersionManifest.compareVersions("1.0.3", "1.0.3"));
    }

    @Test
    void compareVersions_lower() {
        assertTrue(VersionManifest.compareVersions("1.0.2", "1.0.3") < 0);
    }

    @Test
    void compareVersions_snapshotLowerThanRelease() {
        assertTrue(VersionManifest.compareVersions("1.0.4-SNAPSHOT", "1.0.4") < 0);
    }

    @Test
    void compareVersions_releaseHigherThanSnapshot() {
        assertTrue(VersionManifest.compareVersions("1.0.4", "1.0.4-SNAPSHOT") > 0);
    }

    @Test
    void compareVersions_snapshotsEqual() {
        assertEquals(0, VersionManifest.compareVersions("1.0.4-SNAPSHOT", "1.0.4-SNAPSHOT"));
    }

    @Test
    void compareVersions_snapshotHigherThanPreviousRelease() {
        assertTrue(VersionManifest.compareVersions("1.0.4-SNAPSHOT", "1.0.3") > 0);
    }

    @Test
    void compareVersions_nullHandling() {
        assertEquals(0, VersionManifest.compareVersions(null, null));
        assertTrue(VersionManifest.compareVersions(null, "1.0.0") < 0);
        assertTrue(VersionManifest.compareVersions("1.0.0", null) > 0);
    }

    @Test
    void compareVersions_differentLengths() {
        assertTrue(VersionManifest.compareVersions("1.0.4", "1.0") > 0);
        assertTrue(VersionManifest.compareVersions("1.0", "1.0.4") < 0);
    }

    // --- Manifest loading tests ---

    @Test
    void loadFromClasspath_returnsManifest() throws IOException {
        // The manifest is embedded in the test classpath via resource filtering
        VersionManifest manifest = VersionManifest.loadFromClasspath();
        assertNotNull(manifest);
        assertNotNull(manifest.getVersion());
        assertFalse(manifest.getComponents().isEmpty());
    }

    @Test
    void loadFromString_parsesJson() throws IOException {
        String json = """
                {
                  "version": "1.0.4-SNAPSHOT",
                  "artifactId": "synthesis",
                  "buildTimestamp": "2026-02-15T12:00:00Z",
                  "components": [
                    {
                      "name": "synthesis-cli",
                      "type": "jar",
                      "required": true,
                      "since": "1.0.0"
                    },
                    {
                      "name": "synthesis-mcp-server",
                      "type": "jar",
                      "required": false,
                      "since": "1.0.4"
                    }
                  ],
                  "changelog": "https://example.com"
                }
                """;

        VersionManifest manifest = VersionManifest.loadFromString(json);
        assertEquals("1.0.4-SNAPSHOT", manifest.getVersion());
        assertEquals("synthesis", manifest.getArtifactId());
        assertEquals(2, manifest.getComponents().size());
        assertEquals("https://example.com", manifest.getChangelog());
    }

    @Test
    void loadFromFile_readsJsonFile(@TempDir Path tempDir) throws IOException {
        String json = """
                {
                  "version": "1.0.5",
                  "components": [
                    { "name": "test-component", "type": "jar", "required": true, "since": "1.0.0" }
                  ]
                }
                """;
        Path manifestFile = tempDir.resolve("manifest.json");
        Files.writeString(manifestFile, json);

        VersionManifest manifest = VersionManifest.loadFromFile(manifestFile);
        assertEquals("1.0.5", manifest.getVersion());
        assertEquals(1, manifest.getComponents().size());
    }

    @Test
    void loadOrEmpty_returnsEmptyOnMissingResource() {
        // This should not throw, even if classpath resource is somehow missing
        VersionManifest manifest = VersionManifest.loadOrEmpty();
        assertNotNull(manifest);
    }

    // --- Component queries ---

    @Test
    void getComponent_findsByName() throws IOException {
        String json = """
                {
                  "version": "1.0.4",
                  "components": [
                    { "name": "synthesis-cli", "type": "jar", "required": true, "since": "1.0.0" },
                    { "name": "synthesis-mcp-server", "type": "jar", "required": false, "since": "1.0.4" }
                  ]
                }
                """;
        VersionManifest manifest = VersionManifest.loadFromString(json);

        assertTrue(manifest.getComponent("synthesis-cli").isPresent());
        assertTrue(manifest.getComponent("synthesis-mcp-server").isPresent());
        assertFalse(manifest.getComponent("nonexistent").isPresent());
    }

    @Test
    void getComponentsByType_filtersCorrectly() throws IOException {
        String json = """
                {
                  "version": "1.0.4",
                  "components": [
                    { "name": "cli", "type": "jar", "required": true, "since": "1.0.0" },
                    { "name": "mcp", "type": "jar", "required": false, "since": "1.0.4" },
                    { "name": "launcher", "type": "script", "required": true, "since": "1.0.0" }
                  ]
                }
                """;
        VersionManifest manifest = VersionManifest.loadFromString(json);

        assertEquals(2, manifest.getComponentsByType("jar").size());
        assertEquals(1, manifest.getComponentsByType("script").size());
        assertEquals(0, manifest.getComponentsByType("docs").size());
    }

    @Test
    void getRequiredComponents_filtersCorrectly() throws IOException {
        String json = """
                {
                  "version": "1.0.4",
                  "components": [
                    { "name": "cli", "type": "jar", "required": true, "since": "1.0.0" },
                    { "name": "mcp", "type": "jar", "required": false, "since": "1.0.4" },
                    { "name": "launcher", "type": "script", "required": true, "since": "1.0.0" }
                  ]
                }
                """;
        VersionManifest manifest = VersionManifest.loadFromString(json);

        assertEquals(2, manifest.getRequiredComponents().size());
    }

    @Test
    void getComponentsNewSince_returnsNewComponents() throws IOException {
        String json = """
                {
                  "version": "1.0.4",
                  "components": [
                    { "name": "cli", "type": "jar", "since": "1.0.0" },
                    { "name": "mcp", "type": "jar", "since": "1.0.4" },
                    { "name": "lsp", "type": "jar", "since": "1.0.4" },
                    { "name": "docs", "type": "docs", "since": "1.0.0" }
                  ]
                }
                """;
        VersionManifest manifest = VersionManifest.loadFromString(json);

        // Components new since 1.0.3 -- should include MCP and LSP (since 1.0.4)
        var newSince103 = manifest.getComponentsNewSince("1.0.3");
        assertEquals(2, newSince103.size());
        assertTrue(newSince103.stream().anyMatch(c -> "mcp".equals(c.getName())));
        assertTrue(newSince103.stream().anyMatch(c -> "lsp".equals(c.getName())));

        // Components new since 1.0.4 -- none (1.0.4 is not > 1.0.4)
        var newSince104 = manifest.getComponentsNewSince("1.0.4");
        assertEquals(0, newSince104.size());

        // Components new since 1.0.0 -- MCP and LSP
        var newSince100 = manifest.getComponentsNewSince("1.0.0");
        assertEquals(2, newSince100.size());
    }
}
