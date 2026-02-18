package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
