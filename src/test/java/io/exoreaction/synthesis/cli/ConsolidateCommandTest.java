package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConsolidateCommand} static helpers.
 */
class ConsolidateCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // inferSubdir
    // -------------------------------------------------------------------------

    @Test
    void inferSubdir_marketingParent() {
        Path source = Path.of("/workspace/company/marketing/item-consulting");
        assertEquals("marketing", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_workshopParent() {
        Path source = Path.of("/workspace/products/workshop/item-consulting");
        assertEquals("workshop-prep", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_prospectParent() {
        Path source = Path.of("/workspace/products/prospects/item-consulting");
        assertEquals("workshop-prep", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_opportunitiesParent() {
        Path source = Path.of("/workspace/business/opportunities/item-consulting");
        assertEquals("proposals", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_proposalParent() {
        Path source = Path.of("/workspace/business/proposal-drafts/item-consulting");
        assertEquals("proposals", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_travelParent() {
        Path source = Path.of("/workspace/travel/item-consulting");
        assertEquals("personal", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_personalParent() {
        Path source = Path.of("/workspace/personal/item-consulting");
        assertEquals("personal", ConsolidateCommand.inferSubdir(source));
    }

    @Test
    void inferSubdir_unknownParent() {
        Path source = Path.of("/workspace/other/Item-Consulting");
        assertEquals("item-consulting", ConsolidateCommand.inferSubdir(source));
    }

    // -------------------------------------------------------------------------
    // proposeTarget
    // -------------------------------------------------------------------------

    @Test
    void proposeTarget_usesClientsDir() throws IOException {
        // Create a clients/ directory in the workspace
        Path clientsDir = Files.createDirectories(workspace.resolve("company/clients"));
        Path dir1 = Files.createDirectories(workspace.resolve("marketing/item-consulting"));
        Files.writeString(dir1.resolve("file1.md"), "content");
        Path dir2 = Files.createDirectories(workspace.resolve("business/item-consulting"));
        Files.writeString(dir2.resolve("file2.md"), "content");

        Path target = ConsolidateCommand.proposeTarget(
                workspace, "Item Consulting", List.of(dir1, dir2));

        assertEquals(clientsDir.resolve("ItemConsulting"), target);
    }

    @Test
    void proposeTarget_fallsBackToLargestParent() throws IOException {
        // No clients/ directory — should use parent of largest source dir
        Path dir1 = Files.createDirectories(workspace.resolve("area1/item-consulting"));
        Files.writeString(dir1.resolve("file1.md"), "content");

        Path dir2 = Files.createDirectories(workspace.resolve("area2/item-consulting"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir2.resolve("file" + i + ".md"), "content");
        }

        Path target = ConsolidateCommand.proposeTarget(
                workspace, "Item Consulting", List.of(dir1, dir2));

        // dir2 is larger, so target should be under dir2's parent (area2)
        assertEquals(workspace.resolve("area2/ItemConsulting"), target);
    }

    // -------------------------------------------------------------------------
    // moveFiles
    // -------------------------------------------------------------------------

    @Test
    void moveFiles_movesAllFilesFromSource() throws IOException {
        Path source = Files.createDirectories(workspace.resolve("source"));
        Files.writeString(source.resolve("file1.txt"), "content1");
        Files.writeString(source.resolve("file2.txt"), "content2");
        Path sub = Files.createDirectories(source.resolve("sub"));
        Files.writeString(sub.resolve("file3.txt"), "content3");

        Path target = workspace.resolve("target");

        ConsolidateCommand.moveFiles(source, target);

        assertTrue(Files.exists(target.resolve("file1.txt")));
        assertTrue(Files.exists(target.resolve("file2.txt")));
        assertTrue(Files.exists(target.resolve("sub/file3.txt")));

        // Source files should no longer exist
        assertFalse(Files.exists(source.resolve("file1.txt")));
        assertFalse(Files.exists(source.resolve("file2.txt")));
        assertFalse(Files.exists(sub.resolve("file3.txt")));
    }

    @Test
    void moveFiles_createsTargetDir() throws IOException {
        Path source = Files.createDirectories(workspace.resolve("source"));
        Files.writeString(source.resolve("file.txt"), "content");

        Path target = workspace.resolve("new-target/nested");
        assertFalse(Files.exists(target));

        ConsolidateCommand.moveFiles(source, target);

        assertTrue(Files.isDirectory(target));
        assertTrue(Files.exists(target.resolve("file.txt")));
    }

    @Test
    void moveFiles_returnsFileCount() throws IOException {
        Path source = Files.createDirectories(workspace.resolve("source"));
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("b.txt"), "b");
        Files.writeString(source.resolve("c.txt"), "c");

        Path target = workspace.resolve("target");

        int count = ConsolidateCommand.moveFiles(source, target);
        assertEquals(3, count);
    }

    @Test
    void moveFiles_emptySourceReturnsZero() throws IOException {
        Path source = Files.createDirectories(workspace.resolve("empty-source"));
        Path target = workspace.resolve("target");

        int count = ConsolidateCommand.moveFiles(source, target);
        assertEquals(0, count);
    }

    @Test
    void moveFiles_nonexistentSourceReturnsZero() throws IOException {
        Path source = workspace.resolve("nonexistent");
        Path target = workspace.resolve("target");

        int count = ConsolidateCommand.moveFiles(source, target);
        assertEquals(0, count);
    }

    // -------------------------------------------------------------------------
    // updateCrossReferences
    // -------------------------------------------------------------------------

    @Test
    void updateCrossReferences_updatesLinksInMarkdown() throws IOException {
        Path mdFile = workspace.resolve("notes.md");
        Files.writeString(mdFile, "See [link](old/path/item-consulting) for details.");

        int updated = ConsolidateCommand.updateCrossReferences(
                workspace, "old/path/item-consulting", "new/path/ItemConsulting");

        String content = Files.readString(mdFile);
        assertTrue(content.contains("new/path/ItemConsulting"));
        assertFalse(content.contains("old/path/item-consulting"));
    }

    @Test
    void updateCrossReferences_returnsCountOfUpdatedFiles() throws IOException {
        Files.writeString(workspace.resolve("file1.md"),
                "Reference to old/path here.");
        Files.writeString(workspace.resolve("file2.md"),
                "Another reference to old/path here.");
        Files.writeString(workspace.resolve("file3.md"),
                "No references here.");

        int count = ConsolidateCommand.updateCrossReferences(
                workspace, "old/path", "new/path");

        assertEquals(2, count);
    }

    @Test
    void updateCrossReferences_skipsNonMdFiles() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"),
                "Reference to old/path here.");

        int count = ConsolidateCommand.updateCrossReferences(
                workspace, "old/path", "new/path");

        assertEquals(0, count);
        // txt file should be unchanged
        String content = Files.readString(workspace.resolve("notes.txt"));
        assertTrue(content.contains("old/path"));
    }

    @Test
    void updateCrossReferences_skipsHiddenDirs() throws IOException {
        Path hidden = Files.createDirectories(workspace.resolve(".hidden"));
        Files.writeString(hidden.resolve("notes.md"),
                "Reference to old/path here.");

        int count = ConsolidateCommand.updateCrossReferences(
                workspace, "old/path", "new/path");

        assertEquals(0, count);
    }

    @Test
    void updateCrossReferences_samePathReturnsZero() throws IOException {
        Files.writeString(workspace.resolve("notes.md"),
                "Reference to same/path here.");

        int count = ConsolidateCommand.updateCrossReferences(
                workspace, "same/path", "same/path");

        assertEquals(0, count);
    }

    // -------------------------------------------------------------------------
    // generateMigrationScript
    // -------------------------------------------------------------------------

    @Test
    void generateMigrationScript_containsMkdirAndMv() {
        Path source = workspace.resolve("old/entity-dir");
        Path target = workspace.resolve("new/EntityDir/marketing");
        List<ConsolidateCommand.MigrationStep> steps = List.of(
                new ConsolidateCommand.MigrationStep(source, target, 5)
        );

        String script = ConsolidateCommand.generateMigrationScript(workspace, steps);

        assertTrue(script.contains("#!/bin/bash"));
        assertTrue(script.contains("mkdir -p"));
        assertTrue(script.contains("mv"));
        assertTrue(script.contains("5 file(s)"));
        assertTrue(script.contains("set -e"));
    }

    @Test
    void generateMigrationScript_multipleSteps() {
        Path source1 = workspace.resolve("area1/entity");
        Path target1 = workspace.resolve("target/marketing");
        Path source2 = workspace.resolve("area2/entity");
        Path target2 = workspace.resolve("target/proposals");

        List<ConsolidateCommand.MigrationStep> steps = List.of(
                new ConsolidateCommand.MigrationStep(source1, target1, 10),
                new ConsolidateCommand.MigrationStep(source2, target2, 3)
        );

        String script = ConsolidateCommand.generateMigrationScript(workspace, steps);

        assertTrue(script.contains("10 file(s)"));
        assertTrue(script.contains("3 file(s)"));
        assertTrue(script.contains("Migration complete"));
    }

    // -------------------------------------------------------------------------
    // toCamelCase
    // -------------------------------------------------------------------------

    @Test
    void toCamelCase_spaceSeparated() {
        assertEquals("ItemConsulting", ConsolidateCommand.toCamelCase("Item Consulting"));
    }

    @Test
    void toCamelCase_hyphenSeparated() {
        assertEquals("ItemConsulting", ConsolidateCommand.toCamelCase("item-consulting"));
    }

    @Test
    void toCamelCase_underscoreSeparated() {
        assertEquals("ItemConsulting", ConsolidateCommand.toCamelCase("item_consulting"));
    }

    @Test
    void toCamelCase_singleWord() {
        assertEquals("Mynder", ConsolidateCommand.toCamelCase("Mynder"));
    }

    @Test
    void toCamelCase_alreadyCamelCase() {
        assertEquals("ItemConsulting", ConsolidateCommand.toCamelCase("ItemConsulting"));
    }
}
