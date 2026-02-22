package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryArchetype} -- archetype definition and matching.
 */
class DirectoryArchetypeTest {

    @Test
    void constructorRejectsNullName() {
        assertThrows(IllegalArgumentException.class, () ->
                new DirectoryArchetype(null, List.of(), List.of(), 0.3));
    }

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () ->
                new DirectoryArchetype("  ", List.of(), List.of(), 0.3));
    }

    @Test
    void constructorRejectsInvalidThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
                new DirectoryArchetype("test", List.of(), List.of(), -0.1));
        assertThrows(IllegalArgumentException.class, () ->
                new DirectoryArchetype("test", List.of(), List.of(), 1.1));
    }

    @Test
    void constructorNormalizesNullLists() {
        DirectoryArchetype archetype = new DirectoryArchetype("test", null, null, 0.3);
        assertNotNull(archetype.expectedTopics());
        assertNotNull(archetype.expectedDocTypes());
        assertTrue(archetype.expectedTopics().isEmpty());
        assertTrue(archetype.expectedDocTypes().isEmpty());
    }

    @Test
    void matchScoreZeroForEmptyCentroid() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "test", List.of("project", "build"), List.of(), 0.3);
        assertEquals(0.0, archetype.matchScore(DirectoryCentroid.empty()));
    }

    @Test
    void matchScoreZeroForNullCentroid() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "test", List.of("project"), List.of(), 0.3);
        assertEquals(0.0, archetype.matchScore(null));
    }

    @Test
    void matchScoreHighForOverlappingTopics() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "client-opportunity",
                List.of("client", "opportunity", "proposal", "contract"),
                List.of("proposal", "contract"),
                0.2);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("client engagement", "proposal writing", "contract negotiation"),
                List.of("Acme Corp"),
                "2026-Q1",
                List.of("proposal"),
                0.85,
                5,
                0,
                Instant.now());

        double score = archetype.matchScore(centroid);
        assertTrue(score > 0.2, "Expected score > 0.2 but got " + score);
    }

    @Test
    void matchScoreZeroForNoOverlap() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "archive",
                List.of("archive", "historical", "legacy"),
                List.of(),
                0.3);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy"),
                "2026-Q1",
                List.of("proposal"),
                0.85,
                5,
                0,
                Instant.now());

        double score = archetype.matchScore(centroid);
        assertEquals(0.0, score);
    }

    @Test
    void findMissingDocTypesDetectsMissing() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "client-opportunity",
                List.of("client", "opportunity"),
                List.of("proposal", "contract", "invoice", "meeting-notes"),
                0.3);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("client"),
                List.of(),
                null,
                List.of("proposal", "meeting-notes"),
                0.7,
                3,
                0,
                Instant.now());

        List<String> missing = archetype.findMissingDocTypes(centroid);
        assertEquals(2, missing.size());
        assertTrue(missing.contains("contract"));
        assertTrue(missing.contains("invoice"));
    }

    @Test
    void findMissingDocTypesCaseInsensitive() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "test",
                List.of(),
                List.of("Proposal", "Contract"),
                0.3);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(),
                List.of(),
                null,
                List.of("proposal"),
                0.5,
                1,
                0,
                Instant.now());

        List<String> missing = archetype.findMissingDocTypes(centroid);
        assertEquals(1, missing.size());
        assertEquals("Contract", missing.get(0));
    }

    @Test
    void findMissingDocTypesAllPresent() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "test",
                List.of(),
                List.of("proposal", "contract"),
                0.3);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(),
                List.of(),
                null,
                List.of("proposal", "contract", "extras"),
                0.5,
                3,
                0,
                Instant.now());

        List<String> missing = archetype.findMissingDocTypes(centroid);
        assertTrue(missing.isEmpty());
    }

    @Test
    void findMissingDocTypesWithNullCentroid() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "test",
                List.of(),
                List.of("proposal", "contract"),
                0.3);

        List<String> missing = archetype.findMissingDocTypes(null);
        assertEquals(2, missing.size());
    }

    @Test
    void matchScoreHandlesMultiWordTopics() {
        DirectoryArchetype archetype = new DirectoryArchetype(
                "methodology",
                List.of("methodology", "framework", "best-practice"),
                List.of(),
                0.2);

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("SDD methodology", "development framework"),
                List.of(),
                null,
                List.of(),
                0.7,
                3,
                0,
                Instant.now());

        double score = archetype.matchScore(centroid);
        // "methodology" and "framework" should match
        assertTrue(score > 0.1, "Expected some match from multi-word topics, got " + score);
    }
}
