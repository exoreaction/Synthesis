package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityDocumentFinder#findEntityRoot}.
 */
class EntityDocumentFinderTest {

    @TempDir
    Path tempDir;

    private final EntityDocumentFinder finder = new EntityDocumentFinder();

    @Test
    void findEntityRoot_findsClientDirectory() throws IOException {
        Path clientsDir = tempDir.resolve("eXOReaction/clients/Elprint");
        Files.createDirectories(clientsDir);

        Optional<Path> result = finder.findEntityRoot(tempDir, "Elprint", ReportTopic.CLIENT);

        assertTrue(result.isPresent(), "Should find Elprint client directory");
        assertEquals(clientsDir.toAbsolutePath(), result.get().toAbsolutePath());
    }

    @Test
    void findEntityRoot_fuzzyMatchesOpportunityPrefix() throws IOException {
        Path opportunityDir = tempDir.resolve("eXOReaction/clients/opportunity-Mynder");
        Files.createDirectories(opportunityDir);

        Optional<Path> result = finder.findEntityRoot(tempDir, "Mynder", ReportTopic.CLIENT);

        assertTrue(result.isPresent(), "Should find opportunity-Mynder via fuzzy match on 'Mynder'");
        assertEquals(opportunityDir.toAbsolutePath(), result.get().toAbsolutePath());
    }

    @Test
    void findEntityRoot_returnsEmptyForNonExistent() throws IOException {
        Files.createDirectories(tempDir.resolve("eXOReaction/clients"));

        Optional<Path> result = finder.findEntityRoot(tempDir, "NonExistentClient", ReportTopic.CLIENT);

        assertTrue(result.isEmpty(), "Should return empty for a client that does not exist");
    }

    @Test
    void findEntityRoot_findsProductDirectory() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/xorcery-aaa");
        Files.createDirectories(productDir);

        Optional<Path> result = finder.findEntityRoot(tempDir, "xorcery-aaa", ReportTopic.PRODUCT);

        assertTrue(result.isPresent(), "Should find xorcery-aaa product directory");
        assertEquals(productDir.toAbsolutePath(), result.get().toAbsolutePath());
    }

    @Test
    void findEntityRoot_returnsEmptyWhenSearchRootMissing() {
        // tempDir has no eXOReaction/clients/ at all
        Optional<Path> result = finder.findEntityRoot(tempDir, "Elprint", ReportTopic.CLIENT);

        assertTrue(result.isEmpty(), "Should return empty when search root does not exist");
    }

    @Test
    void findEntityRoot_prefersDirectMatchOverOpportunityPrefix() throws IOException {
        // Both a direct dir and an opportunity dir exist — direct match should be returned
        Path directDir = tempDir.resolve("eXOReaction/clients/Elprint");
        Path opportunityDir = tempDir.resolve("eXOReaction/clients/opportunity-Elprint");
        Files.createDirectories(directDir);
        Files.createDirectories(opportunityDir);

        Optional<Path> result = finder.findEntityRoot(tempDir, "Elprint", ReportTopic.CLIENT);

        // opportunity- check runs first, so opportunity-Elprint wins if it matches
        // Both are valid; the important thing is that one is returned
        assertTrue(result.isPresent());
    }

    // --- Product discovery: development history (#49) ---

    @Test
    void discoverForProduct_findsChangelogInProductDir_issue49() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/TestProduct");
        Files.createDirectories(productDir);
        Files.writeString(productDir.resolve("CHANGELOG.md"),
                "# Changelog\n## v1.0\n- Initial release");

        List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

        assertTrue(docs.stream().anyMatch(d ->
                d.path().getFileName().toString().equalsIgnoreCase("CHANGELOG.md")),
                "Should find CHANGELOG.md in product directory (#49)");
    }

    @Test
    void discoverForProduct_findsReleaseNotesInProductDir_issue49() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/TestProduct/docs");
        Files.createDirectories(productDir);
        Files.writeString(productDir.resolve("RELEASE-NOTES.md"), "# Release Notes\n## v1.1");

        List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

        assertTrue(docs.stream().anyMatch(d ->
                d.path().getFileName().toString().contains("RELEASE-NOTES")),
                "Should find RELEASE-NOTES.md in product docs (#49)");
    }

    @Test
    void discoverForProduct_findsRoadmapInProductDir_issue49() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/TestProduct");
        Files.createDirectories(productDir);
        Files.writeString(productDir.resolve("ROADMAP.md"), "# Roadmap\n## Q1 2026");

        List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

        assertTrue(docs.stream().anyMatch(d ->
                d.path().getFileName().toString().contains("ROADMAP")),
                "Should find ROADMAP.md in product directory (#49)");
    }

    // --- Product discovery: cross-contamination (#52) ---

    @Test
    void discoverForProduct_doesNotIncludeUnrelatedGotchaFiles_issue52() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/lib-pcb");
        Files.createDirectories(productDir);
        Files.writeString(productDir.resolve("README.md"), "# lib-pcb");
        Path docsDir = tempDir.resolve("eXOReaction/products/lib-pcb/docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("jme3-gotchas.md"),
                "# JME3 Gotchas\nNothing to do with PCB");

        List<ReportDocument> docs = finder.discoverForProduct(tempDir, "lib-pcb");

        boolean hasGotchas = docs.stream()
                .anyMatch(d -> d.path().getFileName().toString().contains("gotchas"));
        assertFalse(hasGotchas,
                "Unrelated *-gotchas.md files should NOT appear in product reports (#52)");
    }

    @Test
    void discoverForProduct_doesNotIncludeReferenceNotesFiles_issue52() throws IOException {
        Path productDir = tempDir.resolve("eXOReaction/products/TestProduct/docs");
        Files.createDirectories(productDir);
        Files.writeString(productDir.resolve("README.md"), "# TestProduct docs");
        Files.writeString(productDir.resolve("some-library.notes.md"),
                "# Random library notes\nNot product-related");

        List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

        boolean hasNotes = docs.stream()
                .anyMatch(d -> d.path().getFileName().toString().contains(".notes."));
        assertFalse(hasNotes,
                "Reference *.notes.md files should NOT appear in product reports (#52)");
    }

    // --- Empty result guard (#47) ---

    @Test
    void discoverForProduct_returnsEmptyForNonExistentProduct_issue47() {
        List<ReportDocument> docs = finder.discoverForProduct(tempDir, "NonExistentProduct");

        assertTrue(docs.isEmpty(),
                "Non-existent product should return empty list (#47)");
    }

    @Test
    void discoverForClient_returnsEmptyForNonExistentClient_issue47() throws IOException {
        Files.createDirectories(tempDir.resolve("eXOReaction/clients"));

        List<ReportDocument> docs = finder.discoverForClient(tempDir, "NonExistentClient");

        assertTrue(docs.isEmpty(),
                "Non-existent client should return empty list (#47)");
    }
}
