package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WantSatisfactionComputer}.
 */
class WantSatisfactionComputerTest {

    private final WantSatisfactionComputer computer = new WantSatisfactionComputer();

    // ---- Helper factories ----

    static DirectoryCentroid centroid(List<String> topics, List<String> entities,
                                      List<String> docTypes) {
        return new DirectoryCentroid(topics, entities, "2026-Q1", docTypes,
                0.85, 5, 0, Instant.now());
    }

    static DirectoryWants wants(List<String> topics, List<String> entities,
                                 List<String> alsoLookingFor) {
        return new DirectoryWants(topics, entities, alsoLookingFor,
                "inferred from directory name", 0.0);
    }

    // ---- Special cases ----

    @Test
    void compute_emptyWants_returns1() {
        assertEquals(1.0, computer.compute(DirectoryCentroid.empty(), DirectoryWants.empty()));
    }

    @Test
    void compute_nullWants_returns1() {
        assertEquals(1.0, computer.compute(DirectoryCentroid.empty(), null));
    }

    @Test
    void compute_hasWantsButNoCentroid_returns0() {
        DirectoryWants w = wants(List.of("energy"), List.of("GreenField"), List.of());
        assertEquals(0.0, computer.compute(DirectoryCentroid.empty(), w));
    }

    @Test
    void compute_hasWantsButNullCentroid_returns0() {
        DirectoryWants w = wants(List.of("energy"), List.of("GreenField"), List.of());
        assertEquals(0.0, computer.compute(null, w));
    }

    // ---- Full coverage scenarios ----

    @Test
    void compute_perfectTopicCoverage_nothingElse() {
        DirectoryCentroid c = centroid(
                List.of("energy", "sdd"),
                List.of(),
                List.of()
        );
        DirectoryWants w = wants(
                List.of("energy", "sdd"),
                List.of(),
                List.of()
        );
        // topicCoverage = 1.0, weight = 0.5 => 0.5
        assertEquals(0.5, computer.compute(c, w), 0.001);
    }

    @Test
    void compute_perfectEntityCoverage_nothingElse() {
        DirectoryCentroid c = centroid(
                List.of(),
                List.of("GreenField Energy"),
                List.of()
        );
        DirectoryWants w = wants(
                List.of(),
                List.of("GreenField Energy"),
                List.of()
        );
        // entityCoverage = 1.0, weight = 0.3 => 0.3
        assertEquals(0.3, computer.compute(c, w), 0.001);
    }

    @Test
    void compute_perfectGapsCoverage_nothingElse() {
        DirectoryCentroid c = centroid(
                List.of(),
                List.of(),
                List.of("invoice", "contract")
        );
        DirectoryWants w = wants(
                List.of(),
                List.of(),
                List.of("invoice", "contract")
        );
        // gapsFilled = 1.0, weight = 0.2 => 0.2
        assertEquals(0.2, computer.compute(c, w), 0.001);
    }

    @Test
    void compute_allPerfect_returns1() {
        DirectoryCentroid c = centroid(
                List.of("energy", "sdd"),
                List.of("GreenField"),
                List.of("invoice")
        );
        DirectoryWants w = wants(
                List.of("energy", "sdd"),
                List.of("GreenField"),
                List.of("invoice")
        );
        // topicCoverage=1.0*0.5 + entityCoverage=1.0*0.3 + gapsFilled=1.0*0.2 = 1.0
        assertEquals(1.0, computer.compute(c, w), 0.001);
    }

    // ---- Partial coverage ----

    @Test
    void compute_halfTopicCoverage() {
        DirectoryCentroid c = centroid(
                List.of("energy"),
                List.of(),
                List.of()
        );
        DirectoryWants w = wants(
                List.of("energy", "sdd"),
                List.of(),
                List.of()
        );
        // topicCoverage = 0.5, weight = 0.5 => 0.25
        assertEquals(0.25, computer.compute(c, w), 0.001);
    }

    @Test
    void compute_mixedCoverage() {
        DirectoryCentroid c = centroid(
                List.of("energy", "sdd", "workshop"),
                List.of("GreenField"),
                List.of("proposal")
        );
        DirectoryWants w = wants(
                List.of("energy", "sdd"),               // 2/2 = 1.0
                List.of("GreenField", "Jane Smith"),     // 1/2 = 0.5
                List.of("invoice", "proposal")           // 1/2 = 0.5
        );
        // 1.0*0.5 + 0.5*0.3 + 0.5*0.2 = 0.5 + 0.15 + 0.1 = 0.75
        assertEquals(0.75, computer.compute(c, w), 0.001);
    }

    @Test
    void compute_zeroCoverage() {
        DirectoryCentroid c = centroid(
                List.of("marketing"),
                List.of("Other Corp"),
                List.of("brochure")
        );
        DirectoryWants w = wants(
                List.of("energy"),
                List.of("GreenField"),
                List.of("invoice")
        );
        assertEquals(0.0, computer.compute(c, w), 0.001);
    }

    // ---- Case insensitivity ----

    @Test
    void compute_caseInsensitive() {
        DirectoryCentroid c = centroid(
                List.of("Renewable Energy"),
                List.of("greenfield energy"),
                List.of()
        );
        DirectoryWants w = wants(
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                List.of()
        );
        // Both fully match (case-insensitive) => topic 0.5 + entity 0.3 = 0.8
        assertEquals(0.8, computer.compute(c, w), 0.001);
    }

    // ---- withSatisfaction ----

    @Test
    void withSatisfaction_updatesScore() {
        DirectoryCentroid c = centroid(
                List.of("energy", "sdd"),
                List.of("GreenField"),
                List.of()
        );
        DirectoryWants w = wants(
                List.of("energy", "sdd"),
                List.of("GreenField"),
                List.of()
        );

        DirectoryWants updated = computer.withSatisfaction(c, w);

        assertEquals(0.8, updated.satisfaction(), 0.001);
        // Other fields preserved
        assertEquals(w.topics(), updated.topics());
        assertEquals(w.entities(), updated.entities());
        assertEquals(w.source(), updated.source());
    }

    @Test
    void withSatisfaction_emptyWants_returns1() {
        DirectoryWants updated = computer.withSatisfaction(
                DirectoryCentroid.empty(), DirectoryWants.empty());
        assertEquals(1.0, updated.satisfaction(), 0.001);
    }

    // ---- coverageRatio ----

    @Test
    void coverageRatio_emptyWanted_returns0() {
        assertEquals(0.0, WantSatisfactionComputer.coverageRatio(Set.of("a"), Set.of()));
    }

    @Test
    void coverageRatio_fullCoverage() {
        assertEquals(1.0, WantSatisfactionComputer.coverageRatio(
                Set.of("a", "b", "c"), Set.of("a", "b")));
    }

    @Test
    void coverageRatio_noCoverage() {
        assertEquals(0.0, WantSatisfactionComputer.coverageRatio(
                Set.of("x", "y"), Set.of("a", "b")));
    }

    @Test
    void coverageRatio_partialCoverage() {
        assertEquals(0.5, WantSatisfactionComputer.coverageRatio(
                Set.of("a", "x"), Set.of("a", "b")));
    }

    // ---- Clamp behavior ----

    @Test
    void compute_neverExceeds1() {
        // Even with generous matching, should clamp at 1.0
        DirectoryCentroid c = centroid(
                List.of("a", "b", "c"),
                List.of("d", "e"),
                List.of("f", "g")
        );
        DirectoryWants w = wants(
                List.of("a", "b", "c"),
                List.of("d", "e"),
                List.of("f", "g")
        );
        assertTrue(computer.compute(c, w) <= 1.0);
    }

    @Test
    void compute_neverBelowZero() {
        DirectoryCentroid c = centroid(List.of(), List.of(), List.of());
        DirectoryWants w = wants(List.of("x"), List.of("y"), List.of("z"));
        assertTrue(computer.compute(c, w) >= 0.0);
    }
}
