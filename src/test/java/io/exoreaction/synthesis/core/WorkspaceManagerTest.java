package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorkspaceManager}, including the config-aware {@code getReportsPath(SynthesisConfig)}.
 */
class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void getReportsPath_returnsDefaultWhenConfigHasNoOutputDir() {
        SynthesisConfig config = new SynthesisConfig(); // report.outputDir is null
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        Path result = manager.getReportsPath(config);

        assertEquals(manager.getReportsPath(), result,
                "Should return default .synthesis/reports/ when outputDir is not set");
        assertTrue(result.startsWith(tempDir.resolve(".synthesis/reports")));
    }

    @Test
    void getReportsPath_returnsCustomDirWhenConfigured() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.ReportConfig reportConfig = new SynthesisConfig.ReportConfig();
        reportConfig.setOutputDir("custom-reports");
        config.setReport(reportConfig);

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Path result = manager.getReportsPath(config);

        assertEquals(tempDir.resolve("custom-reports").toAbsolutePath().normalize(), result,
                "Should resolve relative outputDir against workspace root");
    }

    @Test
    void getReportsPath_returnsAbsolutePathAsIs() {
        Path absolutePath = tempDir.resolve("somewhere-else");
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.ReportConfig reportConfig = new SynthesisConfig.ReportConfig();
        reportConfig.setOutputDir(absolutePath.toAbsolutePath().toString());
        config.setReport(reportConfig);

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Path result = manager.getReportsPath(config);

        assertEquals(absolutePath.toAbsolutePath(), result,
                "Absolute outputDir should be used without modification");
    }

    @Test
    void getReportsPath_treatsNullConfigAsDefault() {
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        Path result = manager.getReportsPath(null);

        assertEquals(manager.getReportsPath(), result,
                "Null config should fall back to default .synthesis/reports/");
    }

    @Test
    void getReportsPath_treatsBlankOutputDirAsDefault() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.ReportConfig reportConfig = new SynthesisConfig.ReportConfig();
        reportConfig.setOutputDir("   "); // blank
        config.setReport(reportConfig);

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Path result = manager.getReportsPath(config);

        assertEquals(manager.getReportsPath(), result,
                "Blank outputDir should fall back to default .synthesis/reports/");
    }

    // --- validate() tests (#87) ---

    @Test
    void validate_returnsEmptyWhenWorkspaceValid() throws IOException {
        Files.createDirectories(tempDir.resolve(".synthesis"));
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        assertTrue(manager.validate().isEmpty(), "Valid workspace should produce no error");
    }

    @Test
    void validate_errorWhenDirectoryMissing() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        WorkspaceManager manager = new WorkspaceManager(nonExistent);

        Optional<String> result = manager.validate();
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("does not exist"));
    }

    @Test
    void validate_errorIncludesSynthesisListHint() throws IOException {
        // No .synthesis/ at tempDir level
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        Optional<String> result = manager.validate();
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("synthesis list"),
                "Error should suggest 'synthesis list'");
    }

    @Test
    void validate_suggestsAncestorWhenFoundInParent() throws IOException {
        // Parent is a valid workspace; child is not
        Files.createDirectories(tempDir.resolve(".synthesis"));
        Path child = tempDir.resolve("sub").resolve("deep");
        Files.createDirectories(child);

        WorkspaceManager manager = new WorkspaceManager(child);
        Optional<String> result = manager.validate();

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Did you mean"),
                "Error should contain 'Did you mean'");
        assertTrue(result.get().contains(tempDir.toAbsolutePath().toString()),
                "Error should reference the ancestor workspace path");
    }

    @Test
    void validate_noAncestorSuggestionWhenNoneFound() throws IOException {
        // tempDir has no .synthesis/ and no ancestor with one
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        Optional<String> result = manager.validate();
        assertTrue(result.isPresent());
        assertFalse(result.get().contains("Did you mean"),
                "Should not suggest an ancestor when none exists");
    }

    @Test
    void validate_suggestsChildWorkspaceWhenFoundBelow() throws IOException {
        // Parent is NOT a workspace; child IS a workspace
        Path child = tempDir.resolve("Synthesis");
        Files.createDirectories(child.resolve(".synthesis"));

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Optional<String> result = manager.validate();

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Did you mean"),
                "Error should contain 'Did you mean'");
        assertTrue(result.get().contains("Synthesis"),
                "Error should reference the child workspace: " + result.get());
        assertTrue(result.get().contains("synthesis workspace"),
                "Error should label it as a synthesis workspace");
    }

    @Test
    void validate_suggestsBothAncestorAndChildWorkspaces() throws IOException {
        // tempDir is a workspace (ancestor), child/sub also has a workspace,
        // but we validate an intermediate directory
        Files.createDirectories(tempDir.resolve(".synthesis"));
        Path intermediate = tempDir.resolve("projects");
        Files.createDirectories(intermediate);
        Path child = intermediate.resolve("MyProject");
        Files.createDirectories(child.resolve(".synthesis"));

        WorkspaceManager manager = new WorkspaceManager(intermediate);
        Optional<String> result = manager.validate();

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Did you mean"),
                "Error should contain 'Did you mean'");
        assertTrue(result.get().contains("parent workspace"),
                "Should mention parent workspace: " + result.get());
        assertTrue(result.get().contains("synthesis workspace"),
                "Should mention child workspace: " + result.get());
    }

    @Test
    void findChildWorkspaces_findsNestedWorkspaces() throws IOException {
        Path child1 = tempDir.resolve("ws1");
        Files.createDirectories(child1.resolve(".synthesis"));
        Path child2 = tempDir.resolve("ws2");
        Files.createDirectories(child2.resolve(".synthesis"));
        // Non-workspace child
        Files.createDirectories(tempDir.resolve("other"));

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        var children = manager.findChildWorkspaces(tempDir, 2);

        assertEquals(2, children.size(), "Should find 2 child workspaces");
    }

    @Test
    void findChildWorkspaces_respectsMaxDepth() throws IOException {
        // Create workspace 3 levels deep -- maxDepth=2 should not find it
        Path deep = tempDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(deep.resolve(".synthesis"));

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        var children = manager.findChildWorkspaces(tempDir, 2);

        assertTrue(children.isEmpty(), "Workspace at depth 3 should not be found with maxDepth=2");
    }

    @Test
    void findAncestorWorkspace_returnsNullWhenNoAncestorHasSynthesis() throws IOException {
        Path child = tempDir.resolve("a").resolve("b");
        Files.createDirectories(child);
        WorkspaceManager manager = new WorkspaceManager(child);

        assertNull(manager.findAncestorWorkspace(child));
    }

    @Test
    void findAncestorWorkspace_returnsNearestAncestor() throws IOException {
        // tempDir is a workspace; child/grandchild are not
        Files.createDirectories(tempDir.resolve(".synthesis"));
        Path grandchild = tempDir.resolve("child").resolve("grandchild");
        Files.createDirectories(grandchild);
        WorkspaceManager manager = new WorkspaceManager(grandchild);

        Path found = manager.findAncestorWorkspace(grandchild);
        assertNotNull(found);
        assertEquals(tempDir.toAbsolutePath().normalize(), found);
    }
}
