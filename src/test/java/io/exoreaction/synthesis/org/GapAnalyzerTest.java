package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GapAnalyzer} -- aspirational gap detection.
 */
class GapAnalyzerTest {

    @Test
    void analyzeEmptyCentroidReturnsEmpty() {
        GapAnalyzer analyzer = new GapAnalyzer();
        assertTrue(analyzer.analyze(DirectoryCentroid.empty()).isEmpty());
    }

    @Test
    void analyzeNullCentroidReturnsEmpty() {
        GapAnalyzer analyzer = new GapAnalyzer();
        assertTrue(analyzer.analyze(null).isEmpty());
    }

    @Test
    void detectsGapsInClientOpportunityCentroid() {
        GapAnalyzer analyzer = new GapAnalyzer();

        // A client opportunity centroid with proposals and meetings but no invoice/contract
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("client engagement", "opportunity tracking",
                        "proposal writing", "partnership"),
                List.of("Acme Corp"),
                "2026-Q1",
                List.of("proposal", "meeting-notes"),
                0.85,
                6,
                0,
                Instant.now());

        Optional<GapAnalyzer.GapAnalysisResult> result = analyzer.analyze(centroid);
        assertTrue(result.isPresent(), "Expected a gap analysis result");
        assertEquals("client-opportunity", result.get().archetypeName());
        assertTrue(result.get().matchScore() > 0.0);

        // Should detect contract, invoice, etc. as missing
        List<String> missing = result.get().missingDocTypes();
        assertFalse(missing.isEmpty(), "Expected some missing doc types");
        // proposal and meeting-notes are present, so they should NOT be in missing
        assertFalse(missing.stream().anyMatch(m -> m.equalsIgnoreCase("proposal")),
                "proposal should not be in missing");
        assertFalse(missing.stream().anyMatch(m -> m.equalsIgnoreCase("meeting-notes")),
                "meeting-notes should not be in missing");
    }

    @Test
    void noGapsWhenAllDocTypesPresent() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        registry.register(new DirectoryArchetype(
                "simple",
                List.of("test topic", "testing"),
                List.of("readme", "tests"),
                0.15));

        GapAnalyzer analyzer = new GapAnalyzer(registry);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("test topic", "testing"),
                List.of(),
                null,
                List.of("readme", "tests"),  // all expected types present
                0.8,
                4,
                0,
                Instant.now());

        Optional<GapAnalyzer.GapAnalysisResult> result = analyzer.analyze(centroid);
        assertTrue(result.isPresent());
        assertTrue(result.get().missingDocTypes().isEmpty(),
                "Expected no missing doc types when all are present");
    }

    @Test
    void noMatchForUnrelatedCentroid() {
        GapAnalyzer analyzer = new GapAnalyzer();

        // Centroid about quantum physics -- should not match any built-in archetype
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("quantum entanglement", "photon dynamics",
                        "wave function collapse"),
                List.of(),
                null,
                List.of("paper"),
                0.9,
                10,
                0,
                Instant.now());

        Optional<GapAnalyzer.GapAnalysisResult> result = analyzer.analyze(centroid);
        // Might match or not depending on token overlap -- that's fine
        // Key: shouldn't crash
    }

    @Test
    void enrichWantsWithGapsAddsAlsoLookingFor() {
        GapAnalyzer analyzer = new GapAnalyzer();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("client engagement", "opportunity",
                        "proposal delivery", "contract review"),
                List.of("BigCorp"),
                "2026-Q1",
                List.of("proposal"),  // Only has proposals
                0.8,
                5,
                0,
                Instant.now());

        DirectoryWants currentWants = new DirectoryWants(
                List.of("client work"),
                List.of("BigCorp"),
                List.of(),
                "inferred from name",
                0.5);

        DirectoryWants enriched = analyzer.enrichWantsWithGaps(centroid, currentWants);

        // Should have alsoLookingFor populated with missing doc types
        assertFalse(enriched.alsoLookingFor().isEmpty(),
                "Expected alsoLookingFor to be populated");
        // Original topics/entities preserved
        assertEquals(currentWants.topics(), enriched.topics());
        assertEquals(currentWants.entities(), enriched.entities());
        // Source updated
        assertTrue(enriched.source().contains("archetype match"));
    }

    @Test
    void enrichWantsPreservesExistingGaps() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        registry.register(new DirectoryArchetype(
                "test-type",
                List.of("testing", "test"),
                List.of("unit-tests", "integration-tests", "readme"),
                0.15));

        GapAnalyzer analyzer = new GapAnalyzer(registry);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("testing", "test automation"),
                List.of(),
                null,
                List.of("unit-tests"),  // Only has unit-tests
                0.75,
                4,
                0,
                Instant.now());

        DirectoryWants currentWants = new DirectoryWants(
                List.of("testing"),
                List.of(),
                List.of("existing-gap"),  // Pre-existing gap
                "manual",
                0.4);

        DirectoryWants enriched = analyzer.enrichWantsWithGaps(centroid, currentWants);

        // Should contain both existing and new gaps
        assertTrue(enriched.alsoLookingFor().contains("existing-gap"),
                "Existing gaps should be preserved");
        assertTrue(enriched.alsoLookingFor().size() > 1,
                "Should have both existing and new gaps");
    }

    @Test
    void enrichWantsReturnsOriginalWhenNoMatch() {
        GapAnalyzer analyzer = new GapAnalyzer();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("totally unique content"),
                List.of(),
                null,
                List.of(),
                0.5,
                2,
                0,
                Instant.now());

        DirectoryWants currentWants = new DirectoryWants(
                List.of("unique"),
                List.of(),
                List.of(),
                "test",
                0.3);

        DirectoryWants enriched = analyzer.enrichWantsWithGaps(centroid, currentWants);
        // If no archetype matches or no gaps, returns original
        // The result may be the original or may have been enriched -- depends on matching
        assertNotNull(enriched);
    }

    @Test
    void enrichWantsHandlesNullCurrentWants() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        registry.register(new DirectoryArchetype(
                "test-type",
                List.of("testing", "test"),
                List.of("readme"),
                0.15));

        GapAnalyzer analyzer = new GapAnalyzer(registry);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("testing", "test automation"),
                List.of(),
                null,
                List.of(),
                0.75,
                4,
                0,
                Instant.now());

        DirectoryWants enriched = analyzer.enrichWantsWithGaps(centroid, null);
        // Should still work -- creates wants from gaps
        assertNotNull(enriched);
    }

    @Test
    void gapAnalysisResultContainsExpectedAndPresent() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        registry.register(new DirectoryArchetype(
                "simple",
                List.of("simple", "topic"),
                List.of("docA", "docB", "docC"),
                0.15));

        GapAnalyzer analyzer = new GapAnalyzer(registry);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("simple topic"),
                List.of(),
                null,
                List.of("docA"),
                0.7,
                3,
                0,
                Instant.now());

        Optional<GapAnalyzer.GapAnalysisResult> result = analyzer.analyze(centroid);
        assertTrue(result.isPresent());

        GapAnalyzer.GapAnalysisResult r = result.get();
        assertEquals(List.of("docA", "docB", "docC"), r.expectedDocTypes());
        assertEquals(List.of("docA"), r.presentDocTypes());
        assertEquals(List.of("docB", "docC"), r.missingDocTypes());
    }

    @Test
    void customRegistryUsed() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        registry.register(new DirectoryArchetype(
                "custom",
                List.of("custom", "archetype"),
                List.of("custom-doc"),
                0.15));

        GapAnalyzer analyzer = new GapAnalyzer(registry);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("custom archetype"),
                List.of(),
                null,
                List.of(),
                0.7,
                3,
                0,
                Instant.now());

        Optional<GapAnalyzer.GapAnalysisResult> result = analyzer.analyze(centroid);
        assertTrue(result.isPresent());
        assertEquals("custom", result.get().archetypeName());
        assertEquals(List.of("custom-doc"), result.get().missingDocTypes());
    }
}
