package io.exoreaction.synthesis.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillInstaller}.
 */
class SkillInstallerTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;
    private Path globalDir;
    private SkillInstaller installer;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        globalDir = tempDir.resolve("global-skills");
        Files.createDirectories(skillsDir);

        installer = new SkillInstaller(skillsDir, "Documents", globalDir);
    }

    // --- installAll ---

    @Test
    void installAll_emptySources_noFiles() throws IOException {
        SkillInstaller.InstallResult result = installer.installAll();

        assertEquals(0, result.count());
        assertTrue(result.installed().isEmpty());
    }

    @Test
    void installAll_copiesAllYamlFiles() throws IOException {
        Files.writeString(skillsDir.resolve("workspace-context.yaml"), "name: ws\n");
        Files.writeString(skillsDir.resolve("navigate-clients.yaml"), "name: nav\n");

        SkillInstaller.InstallResult result = installer.installAll();

        assertEquals(2, result.count());
        assertTrue(Files.exists(globalDir.resolve("Documents-workspace-context.yaml")));
        assertTrue(Files.exists(globalDir.resolve("Documents-navigate-clients.yaml")));
    }

    @Test
    void installAll_prefixesWorkspaceName() throws IOException {
        Files.writeString(skillsDir.resolve("workspace-context.yaml"), "name: ws\n");

        SkillInstaller.InstallResult result = installer.installAll();

        assertTrue(result.installed().containsKey("workspace-context.yaml"));
        assertEquals("Documents-workspace-context.yaml",
                result.installed().get("workspace-context.yaml"));
    }

    @Test
    void installAll_orgSkillsUseOrgNamePrefix() throws IOException {
        Files.writeString(skillsDir.resolve("organization-exoreaction.yaml"), "name: org\n");

        SkillInstaller.InstallResult result = installer.installAll();

        assertTrue(result.installed().containsValue("exoreaction-context.yaml"));
    }

    @Test
    void installAll_skipsNonYamlFiles() throws IOException {
        Files.writeString(skillsDir.resolve("workspace-context.yaml"), "name: ws\n");
        Files.writeString(skillsDir.resolve("readme.txt"), "not a skill\n");

        SkillInstaller.InstallResult result = installer.installAll();

        assertEquals(1, result.count());
    }

    @Test
    void installAll_overwritesExistingFiles() throws IOException {
        Files.createDirectories(globalDir);
        Files.writeString(globalDir.resolve("Documents-workspace-context.yaml"), "old content");
        Files.writeString(skillsDir.resolve("workspace-context.yaml"), "new content");

        installer.installAll();

        String content = Files.readString(globalDir.resolve("Documents-workspace-context.yaml"));
        assertEquals("new content", content);
    }

    @Test
    void installAll_createsGlobalDir() throws IOException {
        // Delete the global dir that setUp would create
        assertFalse(Files.exists(globalDir));

        Files.writeString(skillsDir.resolve("workspace-context.yaml"), "name: ws\n");

        installer.installAll();

        assertTrue(Files.isDirectory(globalDir));
    }

    // --- install (single file) ---

    @Test
    void install_singleFile_copiesCorrectly() throws IOException {
        Files.writeString(skillsDir.resolve("workspace-context.yaml"), "name: ws\n");

        Path dest = installer.install("workspace-context.yaml");

        assertTrue(Files.exists(dest));
        assertEquals("name: ws\n", Files.readString(dest));
    }

    @Test
    void install_missingFile_throwsException() {
        assertThrows(IOException.class,
                () -> installer.install("nonexistent.yaml"));
    }

    // --- uninstallAll ---

    @Test
    void uninstallAll_removesWorkspaceSkills() throws IOException {
        Files.createDirectories(globalDir);
        Files.writeString(globalDir.resolve("Documents-workspace-context.yaml"), "ws");
        Files.writeString(globalDir.resolve("Documents-navigate-clients.yaml"), "nav");
        Files.writeString(globalDir.resolve("Other-workspace-context.yaml"), "other");

        int removed = installer.uninstallAll();

        assertEquals(2, removed);
        assertFalse(Files.exists(globalDir.resolve("Documents-workspace-context.yaml")));
        assertFalse(Files.exists(globalDir.resolve("Documents-navigate-clients.yaml")));
        assertTrue(Files.exists(globalDir.resolve("Other-workspace-context.yaml")));
    }

    @Test
    void uninstallAll_noMatchingFiles_returnsZero() throws IOException {
        Files.createDirectories(globalDir);
        Files.writeString(globalDir.resolve("Other-workspace-context.yaml"), "other");

        int removed = installer.uninstallAll();

        assertEquals(0, removed);
    }

    @Test
    void uninstallAll_missingGlobalDir_returnsZero() throws IOException {
        int removed = installer.uninstallAll();

        assertEquals(0, removed);
    }

    // --- listInstalled ---

    @Test
    void listInstalled_returnsMatchingFiles() throws IOException {
        Files.createDirectories(globalDir);
        Files.writeString(globalDir.resolve("Documents-workspace-context.yaml"), "ws");
        Files.writeString(globalDir.resolve("Documents-navigate-clients.yaml"), "nav");
        Files.writeString(globalDir.resolve("Other-workspace-context.yaml"), "other");

        List<String> installed = installer.listInstalled();

        assertEquals(2, installed.size());
        assertTrue(installed.contains("Documents-workspace-context.yaml"));
        assertTrue(installed.contains("Documents-navigate-clients.yaml"));
    }

    @Test
    void listInstalled_missingDir_returnsEmpty() throws IOException {
        List<String> installed = installer.listInstalled();

        assertTrue(installed.isEmpty());
    }

    // --- prefixedName ---

    @Test
    void prefixedName_regularFile_addsPrefix() {
        assertEquals("Documents-workspace-context.yaml",
                installer.prefixedName("workspace-context.yaml"));
    }

    @Test
    void prefixedName_organizationFile_usesOrgName() {
        assertEquals("exoreaction-context.yaml",
                installer.prefixedName("organization-exoreaction.yaml"));
    }

    @Test
    void prefixedName_pipelineFile_addsPrefix() {
        assertEquals("Documents-pipeline-tracker.yaml",
                installer.prefixedName("pipeline-tracker.yaml"));
    }
}
