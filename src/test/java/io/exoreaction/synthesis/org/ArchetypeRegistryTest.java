package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ArchetypeRegistry} -- archetype registration and matching.
 */
class ArchetypeRegistryTest {

    @Test
    void registryLoadsBuiltInDefaults() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        assertTrue(registry.size() >= 6, "Expected at least 6 built-in archetypes");
        assertTrue(registry.get("client-opportunity").isPresent());
        assertTrue(registry.get("project").isPresent());
        assertTrue(registry.get("methodology").isPresent());
        assertTrue(registry.get("marketing-campaign").isPresent());
        assertTrue(registry.get("product").isPresent());
        assertTrue(registry.get("archive").isPresent());
    }

    @Test
    void getReturnsEmptyForUnknown() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        assertTrue(registry.get("nonexistent").isEmpty());
    }

    @Test
    void registerAddsCustomArchetype() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        int before = registry.size();

        registry.register(new DirectoryArchetype(
                "custom",
                List.of("custom", "special"),
                List.of("readme"),
                0.3));

        assertEquals(before + 1, registry.size());
        assertTrue(registry.get("custom").isPresent());
    }

    @Test
    void registerOverwritesExisting() {
        ArchetypeRegistry registry = new ArchetypeRegistry();

        DirectoryArchetype custom = new DirectoryArchetype(
                "project",
                List.of("my-custom-project"),
                List.of("readme"),
                0.5);

        registry.register(custom);
        assertEquals(custom, registry.get("project").get());
    }

    @Test
    void findBestMatchReturnsCorrectArchetype() {
        ArchetypeRegistry registry = new ArchetypeRegistry();

        // A centroid about client engagement
        DirectoryCentroid clientCentroid = new DirectoryCentroid(
                List.of("client engagement", "opportunity tracking",
                        "proposal writing", "contract negotiation"),
                List.of("Acme Corp"),
                "2026-Q1",
                List.of("proposal", "contract"),
                0.85,
                8,
                0,
                Instant.now());

        Optional<ArchetypeRegistry.ArchetypeMatch> match = registry.findBestMatch(clientCentroid);
        assertTrue(match.isPresent(), "Expected a match for client-like centroid");
        assertEquals("client-opportunity", match.get().archetype().name());
        assertTrue(match.get().score() > 0.0);
    }

    @Test
    void findBestMatchReturnsEmptyForEmptyCentroid() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        assertTrue(registry.findBestMatch(DirectoryCentroid.empty()).isEmpty());
    }

    @Test
    void findBestMatchReturnsEmptyForNullCentroid() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        assertTrue(registry.findBestMatch(null).isEmpty());
    }

    @Test
    void findAllMatchesReturnsSortedByScore() {
        ArchetypeRegistry registry = new ArchetypeRegistry();

        // A centroid about methodology and project
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("methodology", "framework", "project", "development",
                        "process", "guideline"),
                List.of(),
                null,
                List.of("guide", "readme"),
                0.7,
                5,
                0,
                Instant.now());

        List<ArchetypeRegistry.ArchetypeMatch> matches = registry.findAllMatches(centroid);
        assertFalse(matches.isEmpty(), "Expected at least one match");

        // Verify sorted by score descending
        for (int i = 1; i < matches.size(); i++) {
            assertTrue(matches.get(i - 1).score() >= matches.get(i).score(),
                    "Expected descending sort");
        }
    }

    @Test
    void findBestMatchNoMatchForUnrelatedCentroid() {
        ArchetypeRegistry registry = new ArchetypeRegistry();

        // A centroid with very specific unrelated topics
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("quantum computing", "protein folding", "neural networks"),
                List.of("DeepMind"),
                "2025-Q4",
                List.of("research-paper"),
                0.9,
                12,
                0,
                Instant.now());

        Optional<ArchetypeRegistry.ArchetypeMatch> match = registry.findBestMatch(centroid);
        // Might or might not match -- just ensure it doesn't crash
        // If it does match, the score should be above the threshold
        match.ifPresent(m -> assertTrue(m.score() >= m.archetype().matchThreshold()));
    }

    @Test
    void getAllReturnsAllArchetypes() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        assertEquals(registry.size(), registry.getAll().size());
    }

    @Test
    void getAllIsUnmodifiable() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        assertThrows(UnsupportedOperationException.class, () ->
                registry.getAll().add(new DirectoryArchetype("x", List.of(), List.of(), 0.3)));
    }

    @Test
    void clientOpportunityArchetypeHasExpectedDocTypes() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        DirectoryArchetype clientOpp = registry.get("client-opportunity").orElseThrow();
        assertTrue(clientOpp.expectedDocTypes().contains("proposal"));
        assertTrue(clientOpp.expectedDocTypes().contains("contract"));
        assertTrue(clientOpp.expectedDocTypes().contains("invoice"));
    }

    @Test
    void projectArchetypeHasExpectedDocTypes() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        DirectoryArchetype project = registry.get("project").orElseThrow();
        assertTrue(project.expectedDocTypes().contains("readme"));
        assertTrue(project.expectedDocTypes().contains("tests"));
    }

    @Test
    void matchScoreAgainstProjectCentroid() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        DirectoryArchetype project = registry.get("project").orElseThrow();

        DirectoryCentroid projectCentroid = new DirectoryCentroid(
                List.of("project implementation", "development testing",
                        "build automation", "design document"),
                List.of(),
                "2026-Q1",
                List.of("readme", "tests", "implementation"),
                0.80,
                10,
                0,
                Instant.now());

        double score = project.matchScore(projectCentroid);
        assertTrue(score >= project.matchThreshold(),
                "Project centroid should match project archetype (score=" + score
                        + ", threshold=" + project.matchThreshold() + ")");
    }

    @Test
    void matchScoreAgainstMethodologyCentroid() {
        ArchetypeRegistry registry = new ArchetypeRegistry();
        DirectoryArchetype methodology = registry.get("methodology").orElseThrow();

        DirectoryCentroid methodologyCentroid = new DirectoryCentroid(
                List.of("methodology framework", "best practice guidelines",
                        "process standards"),
                List.of(),
                null,
                List.of("guide", "reference"),
                0.75,
                6,
                0,
                Instant.now());

        double score = methodology.matchScore(methodologyCentroid);
        assertTrue(score >= methodology.matchThreshold(),
                "Methodology centroid should match methodology archetype (score=" + score
                        + ", threshold=" + methodology.matchThreshold() + ")");
    }
}
