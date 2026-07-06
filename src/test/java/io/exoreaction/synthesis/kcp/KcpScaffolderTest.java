package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.YamlAnalyzer;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpScaffolder} — knowledge.yaml generation from repository
 * structure (issue #357 / #310).
 */
class KcpScaffolderTest {

    @TempDir
    Path tempDir;

    @Test
    void scaffoldsReadmeDocsAndPolicyUnits() throws Exception {
        Files.writeString(tempDir.resolve("README.md"),
                "# Demo Service\n\nA service that demos things.\n\n## Getting Started\n");
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/api.md"), "# API Reference\n\n## Endpoints\n");
        Files.writeString(tempDir.resolve("SECURITY.md"), "# Security Policy\n");

        String yaml = KcpScaffolder.scaffold(tempDir, "1.38.0", Map.of());

        assertNotNull(yaml);
        assertTrue(yaml.contains("kcp_version: \"0.25\""));
        assertTrue(yaml.contains("generated_by: synthesis@1.38.0"),
                "Generated marker authorises later automated refresh: " + yaml);
        assertTrue(yaml.contains("path: README.md"));
        assertTrue(yaml.contains("intent: \"Demo Service\""), "Intent from first heading");
        assertTrue(yaml.contains("path: docs/api.md"));
        assertTrue(yaml.contains("kind: policy"), "SECURITY.md gets kind: policy");
        assertTrue(yaml.contains("triggers: [demo-service, getting-started]"),
                "Triggers from headings: " + yaml);
        assertTrue(yaml.contains("content_hash:"), "Markdown units carry sha256");
        assertTrue(yaml.contains("verification_status: declared"));
    }

    @Test
    void scaffoldsMavenModulesAndCiAndTests() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Multi\n");
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modules>
                    <module>core</module>
                    <module>api</module>
                    <!-- <module>disabled</module> -->
                  </modules>
                </project>
                """);
        Files.createDirectories(tempDir.resolve("core"));
        Files.createDirectories(tempDir.resolve("api"));
        Files.createDirectories(tempDir.resolve(".github/workflows"));
        Files.createDirectories(tempDir.resolve("src/test"));

        String yaml = KcpScaffolder.scaffold(tempDir, "1.38.0", Map.of());

        assertTrue(yaml.contains("path: core"), "Module unit per pom module");
        assertTrue(yaml.contains("path: api"));
        assertFalse(yaml.contains("disabled"), "Commented-out modules must be ignored");
        assertTrue(yaml.contains("id: ci"), "Workflows directory yields a ci unit");
        assertTrue(yaml.contains("id: tests"), "src/test yields a tests unit");
        // Directory units: no hash (nothing to digest), reference modality
        assertTrue(yaml.contains("primary: reference"));
    }

    @Test
    void emptyDirectoryYieldsNoManifest() {
        assertNull(KcpScaffolder.scaffold(tempDir, "1.38.0", Map.of()),
                "Nothing recognisable → no manifest");
    }

    @Test
    void recordedAtComesFromGitDates() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# R\n");
        String yaml = KcpScaffolder.scaffold(tempDir, "1.38.0",
                Map.of("README.md", "2026-05-01T09:00:00+00:00"));
        assertTrue(yaml.contains("recorded_at: \"2026-05-01T09:00:00+00:00\""),
                "temporal.recorded_at from git map: " + yaml);
    }

    @Test
    void scaffoldedManifestRoundTripsThroughYamlAnalyzer() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Round Trip\n\n## Works\n");
        Files.writeString(tempDir.resolve("CONTRIBUTING.md"), "# Contributing\n");
        String yaml = KcpScaffolder.scaffold(tempDir, "1.38.0", Map.of());

        Path manifest = tempDir.resolve("knowledge.yaml");
        Files.writeString(manifest, yaml);
        FileMetadata metadata = new FileMetadata(
                manifest, "knowledge.yaml", "knowledge.yaml",
                ".yaml", FileUtils.FileType.YAML, null,
                Files.size(manifest), Instant.now(), null);
        AnalysisResult result = new YamlAnalyzer().analyze(metadata);

        assertEquals("kcp-manifest", result.metrics().get("yamlType"),
                "Scaffolded manifest must be detected as KCP");
        assertEquals("0.25", result.metrics().get("kcpVersion"));
        assertEquals(2, result.metrics().get("unitCount"));

        @SuppressWarnings("unchecked")
        var units = (java.util.List<KcpUnit>) result.metrics().get("kcpUnits");
        KcpUnit readme = units.stream().filter(u -> "README.md".equals(u.path()))
                .findFirst().orElseThrow();
        assertEquals("declared", readme.verificationStatus());
        assertNotNull(readme.contentHashValue());
        assertNotNull(readme.extensionsJson(), "generated hints/format land in extensions");
    }

    // -----------------------------------------------------------------------
    // Refresh support: generation marker + volatile normalization
    // -----------------------------------------------------------------------

    @Test
    void generationMarkerDetection() {
        assertTrue(KcpScaffolder.isSynthesisGenerated("hints:\n  generated_by: synthesis@1.38.0\n"));
        assertFalse(KcpScaffolder.isSynthesisGenerated("kcp_version: \"0.25\"\nproject: x\n"));
        assertFalse(KcpScaffolder.isSynthesisGenerated(null));
    }

    @Test
    void normalizeVolatileMakesRegeneratedScaffoldsEqual() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Same\n\n## Structure\n");
        String first = KcpScaffolder.scaffold(tempDir, "1.38.0",
                Map.of("README.md", "2026-01-01T00:00:00+00:00"));
        // Different generator version, different git date, changed file bytes
        Files.writeString(tempDir.resolve("README.md"), "# Same\n\n## Structure\n\nmore prose\n");
        String second = KcpScaffolder.scaffold(tempDir, "9.9.9",
                Map.of("README.md", "2026-06-30T00:00:00+00:00"));

        assertNotEquals(first, second, "Raw scaffolds differ in volatile fields");
        assertEquals(KcpScaffolder.normalizeVolatile(first),
                KcpScaffolder.normalizeVolatile(second),
                "Normalized scaffolds of the same structure must be equal");
    }

    @Test
    void normalizeVolatileDetectsStructuralEdits() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Base\n");
        String generated = KcpScaffolder.scaffold(tempDir, "1.38.0", Map.of());
        String handEdited = generated.replace("intent: \"Base\"",
                "intent: \"A human rewrote this intent\"");

        assertNotEquals(KcpScaffolder.normalizeVolatile(generated),
                KcpScaffolder.normalizeVolatile(handEdited),
                "Intent edits are structural — refresh must not clobber them");
    }

    @Test
    void mavenModuleParserStripsComments() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project><modules>
                  <module> spaced </module>
                  <!-- <module>ghost</module> -->
                </modules></project>
                """);
        var modules = KcpScaffolder.parseMavenModules(pom);
        assertEquals(java.util.List.of("spaced"), modules);
    }
}
