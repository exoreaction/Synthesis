package io.exoreaction.synthesis.report;

import io.exoreaction.synthesis.core.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the auto-save path determination and file-write logic used by ReportCommand.
 *
 * <p>The auto-save rules are:
 * <ul>
 *   <li>Client/product reports are co-located under the entity's workspace directory
 *       at {@code <entity>/reports/<date>-<topic>-<target>.md}</li>
 *   <li>Business topic reports are saved to {@code .synthesis/reports/<date>-<topic>-<target>.md}</li>
 *   <li>Auto-save is suppressed when {@code --no-save} or {@code --output} is in effect</li>
 *   <li>Parent directories are created automatically if they don't exist</li>
 * </ul>
 */
class ReportAutoSaveTest {

    @TempDir
    Path tempDir;

    private final EntityDocumentFinder finder = new EntityDocumentFinder();

    // ---- Path derivation: entity reports ----

    @Test
    void autoSave_clientReport_savesToEntityReportsDir() throws IOException {
        Path clientDir = tempDir.resolve("eXOReaction/clients/Elprint");
        Files.createDirectories(clientDir);

        Optional<Path> entityRoot = finder.findEntityRoot(tempDir, "Elprint", ReportTopic.CLIENT);
        assertTrue(entityRoot.isPresent());

        Path reportsDir = entityRoot.get().resolve("reports");
        String filename = todayFilename("client", "ceo");
        Path savePath = reportsDir.resolve(filename);

        // Simulate auto-save: create parent dirs + write
        Files.createDirectories(savePath.getParent());
        Files.writeString(savePath, "# Elprint Report\nTest content");

        assertTrue(Files.exists(savePath), "Report file should be created in entity reports dir");
        assertEquals("# Elprint Report\nTest content", Files.readString(savePath));
        assertTrue(savePath.startsWith(clientDir.resolve("reports")),
                "Save path should be under the client's reports/ directory");
    }

    @Test
    void autoSave_clientReport_fuzzyMatchesOpportunityPrefix() throws IOException {
        Path opportunityDir = tempDir.resolve("eXOReaction/clients/opportunity-Mynder");
        Files.createDirectories(opportunityDir);

        Optional<Path> entityRoot = finder.findEntityRoot(tempDir, "Mynder", ReportTopic.CLIENT);
        assertTrue(entityRoot.isPresent(), "Should fuzzy-match opportunity-Mynder");

        Path savePath = entityRoot.get().resolve("reports").resolve(todayFilename("client", "ceo"));
        Files.createDirectories(savePath.getParent());
        Files.writeString(savePath, "# Mynder Report");

        assertTrue(savePath.startsWith(opportunityDir.resolve("reports")),
                "Save path should be co-located under opportunity-Mynder/reports/");
    }

    @Test
    void autoSave_productReport_savesToProductReportsDir() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/Synthesis");
        Files.createDirectories(productDir);

        Optional<Path> entityRoot = finder.findEntityRoot(tempDir, "Synthesis", ReportTopic.PRODUCT);
        assertTrue(entityRoot.isPresent());

        Path savePath = entityRoot.get().resolve("reports").resolve(todayFilename("product", "ceo"));
        Files.createDirectories(savePath.getParent());
        Files.writeString(savePath, "# Synthesis Report");

        assertTrue(savePath.startsWith(productDir.resolve("reports")));
    }

    // ---- Path derivation: business topic reports ----

    @Test
    void autoSave_topicReport_savesToSynthesisReportsDir() throws IOException {
        WorkspaceManager workspace = new WorkspaceManager(tempDir);

        Path reportsBase = workspace.getReportsPath();
        String filename = todayFilename("pipeline", "ceo");
        Path savePath = reportsBase.resolve(filename);

        Files.createDirectories(savePath.getParent());
        Files.writeString(savePath, "# Pipeline Report");

        assertTrue(Files.exists(savePath));
        assertTrue(savePath.startsWith(tempDir.resolve(".synthesis/reports")),
                "Business topic report should be under .synthesis/reports/");
    }

    // ---- Parent directory creation ----

    @Test
    void autoSave_createsParentDirectoriesIfNotExist() throws IOException {
        Path clientDir = tempDir.resolve("eXOReaction/clients/Elprint");
        Files.createDirectories(clientDir);
        // Note: reports/ subdirectory does NOT exist yet

        Optional<Path> entityRoot = finder.findEntityRoot(tempDir, "Elprint", ReportTopic.CLIENT);
        assertTrue(entityRoot.isPresent());

        Path savePath = entityRoot.get().resolve("reports").resolve(todayFilename("client", "ceo"));

        assertFalse(Files.exists(savePath.getParent()),
                "reports/ directory should not exist before auto-save");

        Files.createDirectories(savePath.getParent());
        Files.writeString(savePath, "# Report");

        assertTrue(Files.exists(savePath.getParent()), "reports/ directory should be created");
        assertTrue(Files.exists(savePath), "Report file should be written");
    }

    // ---- Filename convention ----

    @Test
    void filename_convention_isDateSortable() {
        String filename = todayFilename("client", "ceo");
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        assertTrue(filename.startsWith(today),
                "Filename should start with ISO date for natural sort order");
        assertTrue(filename.endsWith(".md"),
                "Filename should end with .md");
        assertEquals(today + "-client-ceo.md", filename);
    }

    @Test
    void filename_sameDay_overwritesPrevious() throws IOException {
        Path dir = tempDir.resolve("reports");
        Files.createDirectories(dir);
        Path savePath = dir.resolve(todayFilename("weekly", "ceo"));

        Files.writeString(savePath, "First version");
        Files.writeString(savePath, "Second version");

        assertEquals("Second version", Files.readString(savePath),
                "Same-day report should overwrite (refresh, not accumulate)");
    }

    // ---- Research auto-save path ----

    @Test
    void researchAutoSave_savesToSynthesisReportsResearchDir() throws IOException {
        WorkspaceManager workspace = new WorkspaceManager(tempDir);

        Path researchDir = workspace.getReportsPath().resolve("research");
        String filename = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + "-chatgpt.md";
        Path savePath = researchDir.resolve(filename);

        Files.createDirectories(savePath.getParent());
        Files.writeString(savePath, "# Research Report");

        assertTrue(Files.exists(savePath));
        assertTrue(savePath.startsWith(tempDir.resolve(".synthesis/reports/research")),
                "Research report should be under .synthesis/reports/research/");
    }

    // ---- Helper ----

    private static String todayFilename(String topic, String target) {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "-" + topic + "-" + target + ".md";
    }
}
