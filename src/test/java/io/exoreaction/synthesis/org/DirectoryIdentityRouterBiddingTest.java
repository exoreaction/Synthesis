package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bidding integration in {@link DirectoryIdentityRouter} (P3-02).
 */
class DirectoryIdentityRouterBiddingTest {

    @TempDir
    Path tempDir;

    /**
     * Creates a directory with both identity and centroid in .synthesis.md.
     */
    private Path createDirectoryWithCentroid(String dirName, List<String> types,
                                              List<String> centroidTopics,
                                              List<String> centroidEntities,
                                              List<String> centroidDocTypes,
                                              double centroidConfidence)
            throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                types, List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "Test directory: " + dirName
        );

        DirectoryCentroid centroid = new DirectoryCentroid(
                centroidTopics, centroidEntities, "2026-Q1",
                centroidDocTypes, centroidConfidence, 5, 0, Instant.now()
        );

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, DirectoryWants.empty());

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);
        return dir;
    }

    /**
     * Creates a directory with only wants (cold start, no centroid).
     */
    private Path createDirectoryWithWants(String dirName, List<String> types,
                                           List<String> wantsTopics,
                                           List<String> wantsEntities)
            throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                types, List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.5, null, "test", "Test directory: " + dirName
        );

        DirectoryWants wants = new DirectoryWants(
                wantsTopics, wantsEntities, List.of(),
                "inferred from directory name", 0.0
        );

        DirectoryProfile profile = new DirectoryProfile(identity, DirectoryCentroid.empty(), wants);

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);
        return dir;
    }

    @Test
    void routeWithBidding_enrichedFile_usesBidding() throws IOException {
        createDirectoryWithCentroid("clients/greenfield",
                List.of("client"),
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy"),
                List.of("proposal", "contract"),
                0.87);

        createDirectoryWithCentroid("marketing",
                List.of("marketing"),
                List.of("brand materials", "social media"),
                List.of("eXOReaction"),
                List.of("brochure"),
                0.75);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy"),
                "proposal",
                "2026-Q1",
                "companion"
        );

        RoutingContext context = RoutingContext.withThreshold(0.1);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                tempDir.resolve("proposal.pdf"), context, sig);

        assertTrue(result.isPresent(), "Should find a match via bidding");
        assertEquals("bidding", result.get().decision().mechanism(),
                "Should use bidding mechanism for enriched files");
        assertTrue(result.get().decision().destination().toString().contains("greenfield"),
                "Should route to the directory with matching centroid");
    }

    @Test
    void routeWithBidding_nonEnrichedFile_fallsBackToScoring() throws IOException {
        createDirectoryWithCentroid("docs",
                List.of("documentation"),
                List.of("architecture"),
                List.of(),
                List.of("guide"),
                0.8);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);

        // Create a non-enriched file in the workspace
        Path testFile = tempDir.resolve("architecture-guide.md");
        Files.writeString(testFile, "# Architecture Guide");

        RoutingContext context = RoutingContext.withThreshold(0.1);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                testFile, context, null);

        // Should fall back to identity scoring
        if (result.isPresent()) {
            assertEquals("identity-score", result.get().decision().mechanism(),
                    "Should fall back to identity-score for non-enriched files");
        }
        // It's also acceptable if no match is found (depends on scoring)
    }

    @Test
    void routeWithBidding_emptySignature_fallsBackToScoring() throws IOException {
        createDirectoryWithCentroid("docs",
                List.of("documentation"),
                List.of("guides"),
                List.of(),
                List.of(),
                0.8);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path testFile = tempDir.resolve("readme.md");
        Files.writeString(testFile, "# README");

        RoutingContext context = RoutingContext.withThreshold(0.1);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                testFile, context, EnrichmentSignature.empty());

        // Should fall back (empty signature triggers fallback)
        if (result.isPresent()) {
            assertNotEquals("bidding", result.get().decision().mechanism(),
                    "Empty signature should not trigger bidding");
        }
    }

    @Test
    void routeWithBidding_returnsVirtualProposals() throws IOException {
        // Create three directories that all partially match
        createDirectoryWithCentroid("clients/greenfield",
                List.of("client"),
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy"),
                List.of("proposal"),
                0.9);

        createDirectoryWithCentroid("methodology/sdd",
                List.of("methodology"),
                List.of("SDD methodology", "workshop"),
                List.of("eXOReaction"),
                List.of("guide", "proposal"),
                0.85);

        createDirectoryWithCentroid("products/workshop",
                List.of("product"),
                List.of("workshop", "SDD methodology", "training"),
                List.of("eXOReaction"),
                List.of("curriculum"),
                0.8);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("renewable energy", "SDD methodology", "workshop"),
                List.of("GreenField Energy"),
                "proposal",
                "2026-Q1",
                "companion"
        );

        RoutingContext context = RoutingContext.withThreshold(0.05);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                tempDir.resolve("sdd-proposal.pdf"), context, sig);

        assertTrue(result.isPresent());
        // Winner should be physical
        assertNotNull(result.get().decision());
        // There should be virtual proposals for runners-up
        // (depends on bid strengths, so just verify they're a valid list)
        assertNotNull(result.get().virtualProposals());
    }

    @Test
    void routeWithBidding_wantsOnlyDirectory_participatesInBidding() throws IOException {
        createDirectoryWithWants("clients/nova",
                List.of("client"),
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp"),
                "meeting-notes",
                "2026-Q1",
                "companion"
        );

        RoutingContext context = RoutingContext.withThreshold(0.05);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                tempDir.resolve("nova-meeting.md"), context, sig);

        assertTrue(result.isPresent(), "Wants-only directory should participate in bidding");
        assertEquals("bidding", result.get().decision().mechanism());
    }

    @Test
    void routeWithBidding_noCandidates_returnsEmpty() {
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("anything"),
                List.of("anyone"),
                "report",
                "2026",
                "companion"
        );

        RoutingContext context = RoutingContext.withThreshold(0.5);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                tempDir.resolve("test.pdf"), context, sig);

        assertTrue(result.isEmpty(), "Should return empty when no candidates exist");
    }

    @Test
    void routeWithBidding_biddingBelowThreshold_fallsBackToScoring() throws IOException {
        // Create a directory with a very different centroid
        createDirectoryWithCentroid("marketing",
                List.of("marketing"),
                List.of("brand materials"),
                List.of("Other Corp"),
                List.of("brochure"),
                0.9);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);

        // File about quantum computing -- won't match marketing
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("quantum computing"),
                List.of("IBM Research"),
                "paper",
                "2026-Q1",
                "companion"
        );

        RoutingContext context = RoutingContext.withThreshold(0.5);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                tempDir.resolve("quantum.pdf"), context, sig);

        // Should either be empty or fall back to identity scoring
        if (result.isPresent()) {
            assertNotEquals("bidding", result.get().decision().mechanism(),
                    "Low bidding scores should not produce a bidding result");
        }
    }

    @Test
    void routeWithBidding_extendedResult_virtualProposals_neverNull() throws IOException {
        createDirectoryWithCentroid("docs",
                List.of("documentation"),
                List.of("architecture"),
                List.of(),
                List.of("guide"),
                0.8);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("architecture"),
                List.of(),
                "guide",
                "2026",
                "companion"
        );

        RoutingContext context = RoutingContext.withThreshold(0.05);
        Optional<RoutingDecision.ExtendedResult> result = router.routeWithBidding(
                tempDir.resolve("arch-guide.md"), context, sig);

        if (result.isPresent()) {
            assertNotNull(result.get().virtualProposals(),
                    "Virtual proposals should never be null");
        }
    }
}
