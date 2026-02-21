package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryHealth} and its compute() logic.
 */
class DirectoryHealthTest {

    @Test
    void empty_returnsUnknownStatus() {
        DirectoryHealth health = DirectoryHealth.empty();
        assertEquals("unknown", health.status());
        assertEquals(0.0, health.cohesion());
        assertFalse(health.drift());
        assertEquals(0.0, health.satisfaction());
        assertTrue(health.outliers().isEmpty());
        assertTrue(health.isEmpty());
    }

    @Test
    void compute_noWantsNoCentroid_bootstrapping() {
        DirectoryHealth health = DirectoryHealth.compute(
                DirectoryCentroid.empty(),
                DirectoryWants.empty());

        assertEquals("bootstrapping", health.status());
        assertEquals(0.0, health.cohesion());
        assertFalse(health.drift());
        assertEquals(1.0, health.satisfaction(), "No wants = fully satisfied");
    }

    @Test
    void compute_lowConfidenceCentroid_bootstrapping() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Topic"), List.of(), null, List.of(),
                0.2, 2, 0, Instant.now());

        DirectoryHealth health = DirectoryHealth.compute(centroid, DirectoryWants.empty());

        assertEquals("bootstrapping", health.status());
        assertEquals(0.2, health.cohesion());
    }

    @Test
    void compute_starvingWants_starving() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Different Topic"), List.of(), null, List.of(),
                0.8, 10, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("Desired Topic"), List.of("Entity"),
                List.of(), "test", 0.05);

        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        assertEquals("starving", health.status());
        assertEquals(0.05, health.satisfaction());
        assertFalse(health.drift());
    }

    @Test
    void compute_driftingContent_drifting() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Actual Topic"), List.of("Actual Entity"), null, List.of(),
                0.85, 15, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("Desired Topic"), List.of("Desired Entity"),
                List.of(), "test", 0.25);

        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        assertEquals("drifting", health.status());
        assertTrue(health.drift());
        assertEquals(0.25, health.satisfaction());
        assertEquals(0.85, health.cohesion());
    }

    @Test
    void compute_healthyDirectory_healthy() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Topic"), List.of("Entity"), null, List.of(),
                0.9, 20, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("Topic"), List.of("Entity"),
                List.of(), "test", 0.85);

        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        assertEquals("healthy", health.status());
        assertFalse(health.drift());
        assertEquals(0.85, health.satisfaction());
        assertEquals(0.9, health.cohesion());
    }

    @Test
    void compute_noWantsHighCentroid_healthy() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Topic"), List.of(), null, List.of(),
                0.9, 20, 0, Instant.now());

        DirectoryHealth health = DirectoryHealth.compute(centroid, DirectoryWants.empty());

        assertEquals("healthy", health.status());
        assertFalse(health.drift());
        assertEquals(1.0, health.satisfaction(), "No wants = fully satisfied");
    }

    @Test
    void compute_starvationTakesPrecedenceOverDrift() {
        // satisfaction < 0.1 AND centroid confidence > 0.5 -- both apply,
        // but starvation is checked first
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Topic"), List.of(), null, List.of(),
                0.8, 10, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("Different Topic"), List.of(),
                List.of(), "test", 0.05);

        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        assertEquals("starving", health.status(),
                "Starvation should take precedence over drift when satisfaction < 0.1");
    }

    @Test
    void compute_borderlineSatisfaction_healthy() {
        // satisfaction = 0.4 exactly -- should be healthy (threshold is strict <)
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Topic"), List.of(), null, List.of(),
                0.8, 10, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("Topic"), List.of(),
                List.of(), "test", 0.4);

        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        assertEquals("healthy", health.status());
    }

    @Test
    void compute_borderlineConfidence_notDrifting() {
        // centroid confidence = 0.5 exactly -- should not trigger drift (threshold is strict >)
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("Topic"), List.of(), null, List.of(),
                0.5, 10, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("Different Topic"), List.of(),
                List.of(), "test", 0.2);

        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        // confidence is at threshold (not above), so starving (< 0.1 is false for 0.2),
        // not drifting (confidence not > 0.5). Should be healthy.
        assertEquals("healthy", health.status());
    }

    @Test
    void isEmpty_nonEmpty_returnsFalse() {
        DirectoryHealth health = new DirectoryHealth(0.9, false, 0.85, "healthy", List.of());
        assertFalse(health.isEmpty());
    }

    @Test
    void isEmpty_withDrift_returnsFalse() {
        DirectoryHealth health = new DirectoryHealth(0.0, true, 0.0, "unknown", List.of());
        assertFalse(health.isEmpty());
    }

    @Test
    void isEmpty_withOutliers_returnsFalse() {
        DirectoryHealth health = new DirectoryHealth(0.0, false, 0.0, "unknown",
                List.of("stray-file.txt"));
        assertFalse(health.isEmpty());
    }

    @Test
    void nullWants_treatedAsFullySatisfied() {
        DirectoryHealth health = DirectoryHealth.compute(DirectoryCentroid.empty(), null);
        assertEquals(1.0, health.satisfaction());
    }
}
