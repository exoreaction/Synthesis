package io.exoreaction.synthesis.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UpdateManager}.
 */
class UpdateManagerTest {

    @TempDir
    Path tempDir;

    private Path synthesisHome;

    @BeforeEach
    void setUp() throws IOException {
        synthesisHome = tempDir.resolve(".synthesis");
        Files.createDirectories(synthesisHome.resolve("lib"));
        Files.createDirectories(synthesisHome.resolve("bin"));
        Files.createDirectories(synthesisHome.resolve(".metadata"));
        Files.writeString(synthesisHome.resolve(".metadata/version"), "1.0.3");
    }

    @Test
    void checkHealth_detectsMissingComponents() throws IOException {
        // Minimal installation -- only CLI JAR
        Files.createFile(synthesisHome.resolve("lib/current.jar"));
        Path synthesisBin = synthesisHome.resolve("bin/synthesis");
        Files.createFile(synthesisBin);
        synthesisBin.toFile().setExecutable(true);

        UpdateManager manager = new UpdateManager(synthesisHome);
        InstallationHealth health = manager.checkHealth();

        assertNotNull(health);
        assertEquals("1.0.3", health.getVersion());
        assertFalse(health.isHealthy()); // MCP/LSP missing
        assertFalse(health.hasCriticalIssues()); // But not critical

        // Should have INFO-level issues for missing MCP/LSP
        var infoIssues = health.getIssues(InstallationHealth.Severity.INFO);
        assertTrue(infoIssues.stream().anyMatch(i -> i.component().contains("mcp")),
                "Should detect missing MCP server");
        assertTrue(infoIssues.stream().anyMatch(i -> i.component().contains("lsp")),
                "Should detect missing LSP server");
    }

    @Test
    void checkHealth_detectsCriticalMissingJar() {
        // No CLI JAR at all
        UpdateManager manager = new UpdateManager(synthesisHome);
        InstallationHealth health = manager.checkHealth();

        assertTrue(health.hasCriticalIssues());
        var criticalIssues = health.getIssues(InstallationHealth.Severity.CRITICAL);
        assertTrue(criticalIssues.stream().anyMatch(i -> i.component().equals("synthesis-cli")));
    }

    @Test
    void checkHealth_detectsFullInstallation() throws IOException {
        // Complete installation
        Files.createFile(synthesisHome.resolve("lib/current.jar"));
        Files.createFile(synthesisHome.resolve("lib/synthesis-mcp-server.jar"));
        Files.createFile(synthesisHome.resolve("lib/synthesis-lsp-server.jar"));

        Path synthesisBin = synthesisHome.resolve("bin/synthesis");
        Files.createFile(synthesisBin);
        synthesisBin.toFile().setExecutable(true);

        Path mcpBin = synthesisHome.resolve("bin/synthesis-mcp-server");
        Files.createFile(mcpBin);
        mcpBin.toFile().setExecutable(true);

        Path lspBin = synthesisHome.resolve("bin/synthesis-lsp-server");
        Files.createFile(lspBin);
        lspBin.toFile().setExecutable(true);

        Path updateBin = synthesisHome.resolve("bin/update.sh");
        Files.createFile(updateBin);
        updateBin.toFile().setExecutable(true);

        // Write fingerprint
        InstallationFingerprint fp = InstallationFingerprint.createNew("1.0.3", "source", "source-build");
        fp.save(synthesisHome);

        UpdateManager manager = new UpdateManager(synthesisHome);
        InstallationHealth health = manager.checkHealth();

        assertFalse(health.hasCriticalIssues());
        assertFalse(health.hasWarnings());
        // Only INFO issues (no fingerprint missing since we wrote one)
    }

    @Test
    void checkHealth_detectsMissingFingerprint() throws IOException {
        Files.createFile(synthesisHome.resolve("lib/current.jar"));
        Path synthesisBin = synthesisHome.resolve("bin/synthesis");
        Files.createFile(synthesisBin);
        synthesisBin.toFile().setExecutable(true);

        UpdateManager manager = new UpdateManager(synthesisHome);
        InstallationHealth health = manager.checkHealth();

        var infoIssues = health.getIssues(InstallationHealth.Severity.INFO);
        assertTrue(infoIssues.stream().anyMatch(i -> i.component().equals("fingerprint")),
                "Should detect missing fingerprint");
    }

    @Test
    void performUpdate_dryRunMakesNoChanges() throws IOException {
        Files.createFile(synthesisHome.resolve("lib/current.jar"));
        Files.writeString(synthesisHome.resolve(".metadata/source-dir"), "/nonexistent");

        UpdateManager manager = new UpdateManager(synthesisHome);
        UpdateOptions options = new UpdateOptions().dryRun(true);
        UpdateResult result = manager.performUpdate(options);

        assertTrue(result.isDryRun());
        // Should not have modified any files in a dry run
    }

    @Test
    void installComponent_failsForUnknown() throws IOException {
        Files.writeString(synthesisHome.resolve(".metadata/source-dir"), tempDir.toString());

        UpdateManager manager = new UpdateManager(synthesisHome);
        boolean result = manager.installComponent("nonexistent-component");

        assertFalse(result);
    }

    @Test
    void serverJarUrl_buildsClassifiedArtifactUrl() {
        // #405: server jars are published as classified artifacts alongside the CLI jar
        assertEquals(
                "https://mvnrepo.cantara.no/content/repositories/releases/io/exoreaction/synthesis"
                        + "/1.42.0/synthesis-1.42.0-mcp-server.jar",
                UpdateManager.serverJarUrl("1.42.0", "synthesis-mcp-server"));
        assertEquals(
                "https://mvnrepo.cantara.no/content/repositories/releases/io/exoreaction/synthesis"
                        + "/1.42.0/synthesis-1.42.0-lsp-server.jar",
                UpdateManager.serverJarUrl("1.42.0", "synthesis-lsp-server"));
    }

    @Test
    void checkHealth_flagsServerJarVersionDrift() throws IOException {
        // #405: CLI at 1.42.0 but MCP server jar recorded at 1.37.0 — drift must be loud
        Files.createFile(synthesisHome.resolve("lib/current.jar"));
        Files.createFile(synthesisHome.resolve("lib/synthesis-mcp-server.jar"));
        Files.createFile(synthesisHome.resolve("lib/synthesis-lsp-server.jar"));
        Path synthesisBin = synthesisHome.resolve("bin/synthesis");
        Files.createFile(synthesisBin);
        synthesisBin.toFile().setExecutable(true);

        InstallationFingerprint fp = InstallationFingerprint.createNew("1.42.0", "installer", "cantara-maven");
        fp.setComponent("synthesis-cli", true, "1.42.0");
        fp.setComponent("synthesis-mcp-server", true, "1.37.0");
        fp.setComponent("synthesis-lsp-server", true, "1.42.0");
        fp.save(synthesisHome);

        UpdateManager manager = new UpdateManager(synthesisHome);
        InstallationHealth health = manager.checkHealth();

        var warnings = health.getIssues(InstallationHealth.Severity.WARNING);
        assertTrue(warnings.stream().anyMatch(i ->
                        i.component().equals("synthesis-mcp-server") && i.message().contains("1.37.0")),
                "Should flag MCP server version drift, got: " + warnings);
        assertFalse(warnings.stream().anyMatch(i -> i.component().equals("synthesis-lsp-server")),
                "LSP server at CLI version must not be flagged");
    }

    @Test
    void checkHealth_noDriftWarning_whenComponentsMatchCliVersion() throws IOException {
        Files.createFile(synthesisHome.resolve("lib/current.jar"));
        Files.createFile(synthesisHome.resolve("lib/synthesis-mcp-server.jar"));
        Files.createFile(synthesisHome.resolve("lib/synthesis-lsp-server.jar"));
        Path synthesisBin = synthesisHome.resolve("bin/synthesis");
        Files.createFile(synthesisBin);
        synthesisBin.toFile().setExecutable(true);
        Path updateBin = synthesisHome.resolve("bin/update.sh");
        Files.createFile(updateBin);

        InstallationFingerprint fp = InstallationFingerprint.createNew("1.42.0", "installer", "cantara-maven");
        fp.setComponent("synthesis-cli", true, "1.42.0");
        fp.setComponent("synthesis-mcp-server", true, "1.42.0");
        fp.setComponent("synthesis-lsp-server", true, "1.42.0");
        fp.save(synthesisHome);

        UpdateManager manager = new UpdateManager(synthesisHome);
        InstallationHealth health = manager.checkHealth();

        assertTrue(health.getIssues(InstallationHealth.Severity.WARNING).stream()
                        .noneMatch(i -> i.message().contains("Version drift")),
                "In-sync components must not produce drift warnings");
    }

    @Test
    void updateOptions_defaults() {
        UpdateOptions options = new UpdateOptions();

        assertFalse(options.isDryRun());
        assertFalse(options.isForce());
        assertFalse(options.isSkipDocs());
        assertFalse(options.isSkipVisuals());
        assertFalse(options.isSkipBuild());
        assertNull(options.getTargetVersion());
    }

    @Test
    void updateOptions_builderStyle() {
        UpdateOptions options = new UpdateOptions()
                .dryRun(true)
                .force(true)
                .skipDocs(true)
                .skipVisuals(true)
                .skipBuild(true)
                .targetVersion("1.0.5");

        assertTrue(options.isDryRun());
        assertTrue(options.isForce());
        assertTrue(options.isSkipDocs());
        assertTrue(options.isSkipVisuals());
        assertTrue(options.isSkipBuild());
        assertEquals("1.0.5", options.getTargetVersion());
    }

    @Test
    void updateCheckResult_summary() {
        UpdateCheckResult result = new UpdateCheckResult(
                "1.0.3", "1.0.4", true,
                java.util.List.of("synthesis-mcp-server"),
                java.util.List.of(),
                null, null
        );

        assertTrue(result.hasUpdate());
        assertTrue(result.hasVersionUpdate());
        assertEquals(1, result.getMissingComponents().size());
        String summary = result.getSummary();
        assertTrue(summary.contains("1.0.3"));
        assertTrue(summary.contains("1.0.4"));
    }

    @Test
    void updateCheckResult_noUpdate() {
        UpdateCheckResult result = new UpdateCheckResult(
                "1.0.4", "1.0.4", false,
                java.util.List.of(), java.util.List.of(),
                null, null
        );

        assertFalse(result.hasUpdate());
        assertFalse(result.hasVersionUpdate());
        assertTrue(result.getSummary().contains("Up to date"));
    }

    @Test
    void updateResult_success() {
        UpdateResult result = new UpdateResult(
                "1.0.3", "1.0.4",
                java.util.List.of("synthesis-cli", "synthesis-mcp-server"),
                java.util.List.of(),
                false
        );

        assertTrue(result.isSuccessful());
        assertTrue(result.hasUpdates());
        assertEquals(2, result.getUpdatedComponents().size());
        assertFalse(result.isDryRun());
    }

    @Test
    void updateResult_failure() {
        UpdateResult result = new UpdateResult(
                "1.0.3", null,
                java.util.List.of(),
                java.util.List.of("Download failed"),
                false
        );

        assertFalse(result.isSuccessful());
        assertFalse(result.hasUpdates());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void installationHealth_severity() {
        InstallationHealth health = new InstallationHealth(
                "1.0.3", "2026-01-15", "installer", 3, 7,
                java.util.List.of(
                        new InstallationHealth.Issue("cli", "missing", InstallationHealth.Severity.CRITICAL),
                        new InstallationHealth.Issue("mcp", "not installed", InstallationHealth.Severity.INFO)
                )
        );

        assertFalse(health.isHealthy());
        assertTrue(health.hasCriticalIssues());
        assertFalse(health.hasWarnings());
        assertEquals(1, health.getIssues(InstallationHealth.Severity.CRITICAL).size());
        assertEquals(1, health.getIssues(InstallationHealth.Severity.INFO).size());
        assertEquals(0, health.getIssues(InstallationHealth.Severity.WARNING).size());
    }
}
