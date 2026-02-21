package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CentroidComputer}.
 */
class CentroidComputerTest {

    private final CentroidComputer computer = new CentroidComputer();

    @Test
    void compute_emptyList_returnsEmpty() {
        DirectoryCentroid result = computer.compute(List.of(), 0);
        assertTrue(result.isEmpty());
    }

    @Test
    void compute_nullList_returnsEmpty() {
        DirectoryCentroid result = computer.compute(null, 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void compute_allEmptySignatures_returnsEmpty() {
        List<EnrichmentSignature> sigs = List.of(
                EnrichmentSignature.empty(),
                EnrichmentSignature.empty()
        );
        DirectoryCentroid result = computer.compute(sigs, 2);
        assertTrue(result.isEmpty());
    }

    @Test
    void compute_singleFile_scalesDownConfidence() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("energy", "renewable"),
                List.of("GreenField"),
                "proposal",
                "2026-Q1",
                "companion"
        );

        DirectoryCentroid result = computer.compute(List.of(sig), 1);

        assertFalse(result.isEmpty());
        assertEquals(1, result.contributingFiles());
        // Single file -> confidence scaled down by 0.5
        assertTrue(result.confidence() < 0.5,
                "Single file confidence should be scaled down, got: " + result.confidence());
    }

    @Test
    void compute_overlappingTopics_ranksCorrectly() {
        // 8 files, all mentioning "energy", 5 mentioning "renewable", 3 mentioning "solar"
        List<EnrichmentSignature> sigs = List.of(
                sig(List.of("energy", "renewable"), List.of()),
                sig(List.of("energy", "renewable"), List.of()),
                sig(List.of("energy", "renewable"), List.of()),
                sig(List.of("energy", "renewable"), List.of()),
                sig(List.of("energy", "renewable"), List.of()),
                sig(List.of("energy", "solar"), List.of()),
                sig(List.of("energy", "solar"), List.of()),
                sig(List.of("energy", "solar"), List.of())
        );

        DirectoryCentroid result = computer.compute(sigs, 8);

        assertEquals(8, result.contributingFiles());
        // "energy" should be first (8 mentions), "renewable" second (5), "solar" third (3)
        assertEquals("energy", result.topics().get(0));
        assertEquals("renewable", result.topics().get(1));
        assertEquals("solar", result.topics().get(2));
    }

    @Test
    void compute_entityRanking() {
        List<EnrichmentSignature> sigs = List.of(
                sig(List.of(), List.of("GreenField Energy", "Jane Smith")),
                sig(List.of(), List.of("GreenField Energy", "Thor Henning")),
                sig(List.of(), List.of("GreenField Energy")),
                sig(List.of(), List.of("Jane Smith"))
        );

        DirectoryCentroid result = computer.compute(sigs, 4);

        // "GreenField Energy" mentioned 3 times, should be first
        assertEquals("GreenField Energy", result.entities().get(0));
        // "Jane Smith" mentioned 2 times
        assertTrue(result.entities().contains("Jane Smith"));
    }

    @Test
    void compute_documentTypes_collectsUnique() {
        List<EnrichmentSignature> sigs = List.of(
                new EnrichmentSignature(List.of("topic"), List.of(), "proposal", null, "test"),
                new EnrichmentSignature(List.of("topic"), List.of(), "contract", null, "test"),
                new EnrichmentSignature(List.of("topic"), List.of(), "proposal", null, "test")
        );

        DirectoryCentroid result = computer.compute(sigs, 3);

        assertTrue(result.documentTypes().contains("proposal"));
        assertTrue(result.documentTypes().contains("contract"));
        assertEquals(2, result.documentTypes().size());
    }

    @Test
    void compute_timeframe_singleValue() {
        List<EnrichmentSignature> sigs = List.of(
                new EnrichmentSignature(List.of("topic"), List.of(), null, "2026-Q1", "test"),
                new EnrichmentSignature(List.of("topic"), List.of(), null, "2026-Q1", "test")
        );

        DirectoryCentroid result = computer.compute(sigs, 2);
        assertEquals("2026-Q1", result.timeframe());
    }

    @Test
    void compute_timeframe_range() {
        List<EnrichmentSignature> sigs = List.of(
                new EnrichmentSignature(List.of("topic"), List.of(), null, "2025-Q4", "test"),
                new EnrichmentSignature(List.of("topic"), List.of(), null, "2026-Q1", "test"),
                new EnrichmentSignature(List.of("topic"), List.of(), null, "2026-Q2", "test")
        );

        DirectoryCentroid result = computer.compute(sigs, 3);
        assertEquals("2025-Q4 / 2026-Q2", result.timeframe());
    }

    @Test
    void compute_confidenceReflectsEnrichmentCoverage() {
        // Only 2 out of 10 files are enriched -> low coverage
        EnrichmentSignature sig = sig(List.of("energy", "energy"), List.of());
        DirectoryCentroid result = computer.compute(List.of(sig, sig), 10);

        // Coverage = 2/10 = 0.2, confidence should be low
        assertTrue(result.confidence() < 0.3,
                "Low coverage should produce low confidence, got: " + result.confidence());
    }

    @Test
    void compute_highCoverageWithTightCluster_highConfidence() {
        // All 5 files enriched with the same topic -> tight cluster, full coverage
        List<EnrichmentSignature> sigs = List.of(
                sig(List.of("energy"), List.of()),
                sig(List.of("energy"), List.of()),
                sig(List.of("energy"), List.of()),
                sig(List.of("energy"), List.of()),
                sig(List.of("energy"), List.of())
        );

        DirectoryCentroid result = computer.compute(sigs, 5);

        // Coverage = 5/5 = 1.0, cluster is very tight (1 unique topic)
        assertTrue(result.confidence() > 0.4,
                "High coverage tight cluster should have high confidence, got: " + result.confidence());
    }

    @Test
    void compute_topicsLimitedToMaxTen() {
        // Create signatures with 15 unique topics
        List<EnrichmentSignature> sigs = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            sigs.add(sig(List.of("topic" + i), List.of()));
        }

        DirectoryCentroid result = computer.compute(sigs, 15);

        assertTrue(result.topics().size() <= 10,
                "Topics should be limited to 10, got: " + result.topics().size());
    }

    @Test
    void compute_entitiesLimitedToMaxFive() {
        List<EnrichmentSignature> sigs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sigs.add(sig(List.of("topic"), List.of("Entity" + i)));
        }

        DirectoryCentroid result = computer.compute(sigs, 8);

        assertTrue(result.entities().size() <= 5,
                "Entities should be limited to 5, got: " + result.entities().size());
    }

    @Test
    void compute_topicsCaseInsensitive() {
        List<EnrichmentSignature> sigs = List.of(
                sig(List.of("Energy"), List.of()),
                sig(List.of("energy"), List.of()),
                sig(List.of("ENERGY"), List.of())
        );

        DirectoryCentroid result = computer.compute(sigs, 3);

        assertEquals(1, result.topics().size(), "Same topic in different cases should merge");
        assertEquals("energy", result.topics().get(0));
    }

    @Test
    void computeTimeframe_empty_returnsNull() {
        assertNull(computer.computeTimeframe(List.of()));
    }

    @Test
    void computeTimeframe_noTimeframes_returnsNull() {
        List<EnrichmentSignature> sigs = List.of(
                EnrichmentSignature.empty(),
                sig(List.of("topic"), List.of())
        );
        assertNull(computer.computeTimeframe(sigs));
    }

    // Helper to create a minimal signature with topics and entities
    private static EnrichmentSignature sig(List<String> topics, List<String> entities) {
        return new EnrichmentSignature(topics, entities, null, null, "test");
    }
}
