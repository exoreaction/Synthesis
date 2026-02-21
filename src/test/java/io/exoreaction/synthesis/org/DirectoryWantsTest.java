package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryWants} record construction and empty patterns.
 */
class DirectoryWantsTest {

    @Test
    void empty_returnsEmptyLists_nullSource_zeroSatisfaction() {
        DirectoryWants empty = DirectoryWants.empty();

        assertTrue(empty.topics().isEmpty());
        assertTrue(empty.entities().isEmpty());
        assertTrue(empty.alsoLookingFor().isEmpty());
        assertNull(empty.source());
        assertEquals(0.0, empty.satisfaction());
    }

    @Test
    void empty_isEmpty_returnsTrue() {
        assertTrue(DirectoryWants.empty().isEmpty());
    }

    @Test
    void constructWithAllFields() {
        DirectoryWants wants = new DirectoryWants(
                List.of("GreenField opportunity lifecycle"),
                List.of("GreenField Energy"),
                List.of("invoice", "mentoring contract"),
                "inferred from directory name + 8 files",
                0.87
        );

        assertEquals(List.of("GreenField opportunity lifecycle"), wants.topics());
        assertEquals(List.of("GreenField Energy"), wants.entities());
        assertEquals(List.of("invoice", "mentoring contract"), wants.alsoLookingFor());
        assertEquals("inferred from directory name + 8 files", wants.source());
        assertEquals(0.87, wants.satisfaction(), 0.001);
    }

    @Test
    void constructWithNullLists_defaultsToEmpty() {
        DirectoryWants wants = new DirectoryWants(
                null, null, null, "test", 0.0
        );

        assertNotNull(wants.topics());
        assertTrue(wants.topics().isEmpty());
        assertNotNull(wants.entities());
        assertTrue(wants.entities().isEmpty());
        assertNotNull(wants.alsoLookingFor());
        assertTrue(wants.alsoLookingFor().isEmpty());
    }

    @Test
    void isEmpty_withTopics_returnsFalse() {
        DirectoryWants wants = new DirectoryWants(
                List.of("energy"), List.of(), List.of(), null, 0.0
        );
        assertFalse(wants.isEmpty());
    }

    @Test
    void isEmpty_withEntities_returnsFalse() {
        DirectoryWants wants = new DirectoryWants(
                List.of(), List.of("Corp"), List.of(), null, 0.0
        );
        assertFalse(wants.isEmpty());
    }

    @Test
    void isEmpty_withAlsoLookingFor_returnsFalse() {
        DirectoryWants wants = new DirectoryWants(
                List.of(), List.of(), List.of("invoice"), null, 0.0
        );
        assertFalse(wants.isEmpty());
    }

    @Test
    void isEmpty_withSourceOnly_returnsFalse() {
        DirectoryWants wants = new DirectoryWants(
                List.of(), List.of(), List.of(), "inferred", 0.0
        );
        assertFalse(wants.isEmpty());
    }

    @Test
    void isEmpty_withSatisfactionOnly_returnsTrue() {
        // Satisfaction alone is not meaningful data -- needs topics/entities/source
        DirectoryWants wants = new DirectoryWants(
                List.of(), List.of(), List.of(), null, 0.5
        );
        assertTrue(wants.isEmpty());
    }

    @Test
    void recordEquality() {
        DirectoryWants a = new DirectoryWants(
                List.of("energy"), List.of("Corp"), List.of("invoice"), "test", 0.5
        );
        DirectoryWants b = new DirectoryWants(
                List.of("energy"), List.of("Corp"), List.of("invoice"), "test", 0.5
        );
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
