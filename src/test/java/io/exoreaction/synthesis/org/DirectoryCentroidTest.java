package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryCentroid} record construction and empty patterns.
 */
class DirectoryCentroidTest {

    @Test
    void empty_returnsEmptyLists_zeroConfidence_nullTimestamp() {
        DirectoryCentroid empty = DirectoryCentroid.empty();

        assertTrue(empty.topics().isEmpty());
        assertTrue(empty.entities().isEmpty());
        assertNull(empty.timeframe());
        assertTrue(empty.documentTypes().isEmpty());
        assertEquals(0.0, empty.confidence());
        assertEquals(0, empty.contributingFiles());
        assertEquals(0, empty.virtualMembers());
        assertNull(empty.lastUpdated());
    }

    @Test
    void empty_isEmpty_returnsTrue() {
        assertTrue(DirectoryCentroid.empty().isEmpty());
    }

    @Test
    void constructWithAllFields() {
        Instant now = Instant.now();
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy"),
                "2025-Q4 / 2026-Q1",
                List.of("proposal", "contract"),
                0.87,
                8,
                2,
                now
        );

        assertEquals(List.of("renewable energy", "SDD methodology"), centroid.topics());
        assertEquals(List.of("GreenField Energy"), centroid.entities());
        assertEquals("2025-Q4 / 2026-Q1", centroid.timeframe());
        assertEquals(List.of("proposal", "contract"), centroid.documentTypes());
        assertEquals(0.87, centroid.confidence(), 0.001);
        assertEquals(8, centroid.contributingFiles());
        assertEquals(2, centroid.virtualMembers());
        assertEquals(now, centroid.lastUpdated());
    }

    @Test
    void constructWithNullLists_defaultsToEmpty() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                null, null, null, null, 0.5, 3, 0, null
        );

        assertNotNull(centroid.topics());
        assertTrue(centroid.topics().isEmpty());
        assertNotNull(centroid.entities());
        assertTrue(centroid.entities().isEmpty());
        assertNotNull(centroid.documentTypes());
        assertTrue(centroid.documentTypes().isEmpty());
    }

    @Test
    void isEmpty_withTopics_returnsFalse() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy"), List.of(), null, List.of(), 0.0, 0, 0, null
        );
        assertFalse(centroid.isEmpty());
    }

    @Test
    void isEmpty_withEntities_returnsFalse() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(), List.of("GreenField"), null, List.of(), 0.0, 0, 0, null
        );
        assertFalse(centroid.isEmpty());
    }

    @Test
    void isEmpty_withConfidence_returnsFalse() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(), List.of(), null, List.of(), 0.5, 1, 0, null
        );
        assertFalse(centroid.isEmpty());
    }

    @Test
    void isEmpty_withDocumentTypes_returnsFalse() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(), List.of(), null, List.of("proposal"), 0.0, 0, 0, null
        );
        assertFalse(centroid.isEmpty());
    }

    @Test
    void recordEquality() {
        Instant now = Instant.parse("2026-02-21T15:00:00Z");
        DirectoryCentroid a = new DirectoryCentroid(
                List.of("energy"), List.of("Corp"), "Q1", List.of("doc"), 0.5, 3, 1, now
        );
        DirectoryCentroid b = new DirectoryCentroid(
                List.of("energy"), List.of("Corp"), "Q1", List.of("doc"), 0.5, 3, 1, now
        );
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
