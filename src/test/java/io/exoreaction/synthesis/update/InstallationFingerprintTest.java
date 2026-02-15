package io.exoreaction.synthesis.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InstallationFingerprint}.
 */
class InstallationFingerprintTest {

    @TempDir
    Path tempDir;

    @Test
    void createNew_setsBasicFields() {
        InstallationFingerprint fp = InstallationFingerprint.createNew("1.0.4", "installer", "github-release");

        assertEquals("1.0.4", fp.getVersion());
        assertEquals("installer", fp.getInstallMethod());
        assertEquals("github-release", fp.getInstallSource());
        assertNotNull(fp.getInstallDate());
    }

    @Test
    void setComponent_tracksInstalledState() {
        InstallationFingerprint fp = new InstallationFingerprint();
        fp.setComponent("synthesis-cli", true, "1.0.4");
        fp.setComponent("synthesis-mcp-server", false, null);

        assertTrue(fp.hasComponent("synthesis-cli"));
        assertFalse(fp.hasComponent("synthesis-mcp-server"));
        assertFalse(fp.hasComponent("nonexistent"));
    }

    @Test
    void setComponent_withChecksum() {
        InstallationFingerprint fp = new InstallationFingerprint();
        fp.setComponent("synthesis-cli", true, "1.0.4", "sha256:abc123");

        assertTrue(fp.hasComponent("synthesis-cli"));
        assertEquals("sha256:abc123", fp.getComponent("synthesis-cli").getChecksum());
    }

    @Test
    void installedCount_returnsCorrectCount() {
        InstallationFingerprint fp = new InstallationFingerprint();
        fp.setComponent("a", true, "1.0");
        fp.setComponent("b", true, "1.0");
        fp.setComponent("c", false, null);

        assertEquals(2, fp.installedCount());
    }

    @Test
    void markUpdated_updatesVersionAndDate() {
        InstallationFingerprint fp = InstallationFingerprint.createNew("1.0.3", "installer", "source");
        assertNull(fp.getLastUpdateDate());

        fp.markUpdated("1.0.4");
        assertEquals("1.0.4", fp.getVersion());
        assertNotNull(fp.getLastUpdateDate());
    }

    @Test
    void saveAndLoad_roundTrip() throws IOException {
        // Create and save
        InstallationFingerprint fp = InstallationFingerprint.createNew("1.0.4", "source", "source-build");
        fp.setSourceDirectory("/home/user/src/synthesis");
        fp.setComponent("synthesis-cli", true, "1.0.4");
        fp.setComponent("synthesis-mcp-server", true, "1.0.4");
        fp.setComponent("synthesis-lsp-server", false, null);
        fp.save(tempDir);

        // Verify file was created
        assertTrue(Files.exists(tempDir.resolve(".installation.json")));

        // Load and verify
        InstallationFingerprint loaded = InstallationFingerprint.load(tempDir);
        assertEquals("1.0.4", loaded.getVersion());
        assertEquals("source", loaded.getInstallMethod());
        assertEquals("source-build", loaded.getInstallSource());
        assertEquals("/home/user/src/synthesis", loaded.getSourceDirectory());
        assertTrue(loaded.hasComponent("synthesis-cli"));
        assertTrue(loaded.hasComponent("synthesis-mcp-server"));
        assertFalse(loaded.hasComponent("synthesis-lsp-server"));
        assertEquals(2, loaded.installedCount());
    }

    @Test
    void load_returnsEmptyForMissingFile() {
        InstallationFingerprint fp = InstallationFingerprint.load(tempDir);
        assertNotNull(fp);
        assertNull(fp.getVersion());
        assertEquals(0, fp.installedCount());
    }

    @Test
    void exists_returnsFalseForMissingFile() {
        assertFalse(InstallationFingerprint.exists(tempDir));
    }

    @Test
    void exists_returnsTrueAfterSave() throws IOException {
        InstallationFingerprint fp = InstallationFingerprint.createNew("1.0.4", "installer", "source");
        fp.save(tempDir);
        assertTrue(InstallationFingerprint.exists(tempDir));
    }

    @Test
    void detect_buildsFromExistingInstallation() throws IOException {
        // Create a simulated installation directory
        Files.createDirectories(tempDir.resolve(".metadata"));
        Files.createDirectories(tempDir.resolve("lib"));
        Files.createDirectories(tempDir.resolve("bin"));

        // Version metadata
        Files.writeString(tempDir.resolve(".metadata/version"), "1.0.3");
        Files.writeString(tempDir.resolve(".metadata/install-date"), "2026-01-15T08:23:45+01:00");

        // Create simulated files
        Files.createFile(tempDir.resolve("lib/current.jar"));
        Files.createFile(tempDir.resolve("lib/synthesis-mcp-server.jar"));
        // No LSP server JAR
        Files.createFile(tempDir.resolve("bin/synthesis"));
        Files.createFile(tempDir.resolve("bin/synthesis-mcp-server"));
        Files.createFile(tempDir.resolve("bin/update.sh"));

        // Detect
        InstallationFingerprint fp = InstallationFingerprint.detect(tempDir);

        assertEquals("1.0.3", fp.getVersion());
        assertTrue(fp.hasComponent("synthesis-cli"));
        assertTrue(fp.hasComponent("synthesis-mcp-server"));
        assertFalse(fp.hasComponent("synthesis-lsp-server"));
        assertTrue(fp.hasComponent("launcher-synthesis"));
        assertTrue(fp.hasComponent("launcher-mcp-server"));
        assertFalse(fp.hasComponent("launcher-lsp-server"));
        assertTrue(fp.hasComponent("update-script"));
    }

    @Test
    void detect_handlesMinimalInstallation() throws IOException {
        // Only lib/current.jar exists -- minimal installation
        Files.createDirectories(tempDir.resolve("lib"));
        Files.createDirectories(tempDir.resolve("bin"));
        Files.createFile(tempDir.resolve("lib/current.jar"));

        InstallationFingerprint fp = InstallationFingerprint.detect(tempDir);

        assertEquals("unknown", fp.getVersion());
        assertTrue(fp.hasComponent("synthesis-cli"));
        assertFalse(fp.hasComponent("synthesis-mcp-server"));
    }

    @Test
    void load_handleCorruptedFile() throws IOException {
        // Write invalid JSON
        Files.writeString(tempDir.resolve(".installation.json"), "not valid json {{{");

        // Should not throw, returns empty fingerprint
        InstallationFingerprint fp = InstallationFingerprint.load(tempDir);
        assertNotNull(fp);
        assertNull(fp.getVersion());
    }
}
