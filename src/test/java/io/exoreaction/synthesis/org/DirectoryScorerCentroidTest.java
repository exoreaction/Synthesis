package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 2 centroid-based similarity scoring in {@link DirectoryScorer}.
 */
class DirectoryScorerCentroidTest {

    @TempDir
    Path tempDir;

    // ---- scoreCentroid: unit tests ----

    @Test
    void scoreCentroid_nullSignature_returnsZero() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy"), List.of(), null, List.of(), 0.8, 5, 0, null
        );
        assertEquals(0.0, DirectoryScorer.scoreCentroid(null, centroid));
    }

    @Test
    void scoreCentroid_emptySignature_returnsZero() {
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy"), List.of(), null, List.of(), 0.8, 5, 0, null
        );
        assertEquals(0.0, DirectoryScorer.scoreCentroid(EnrichmentSignature.empty(), centroid));
    }

    @Test
    void scoreCentroid_nullCentroid_returnsZero() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("energy"), List.of(), null, null, "test"
        );
        assertEquals(0.0, DirectoryScorer.scoreCentroid(sig, null));
    }

    @Test
    void scoreCentroid_emptyCentroid_returnsZero() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("energy"), List.of(), null, null, "test"
        );
        assertEquals(0.0, DirectoryScorer.scoreCentroid(sig, DirectoryCentroid.empty()));
    }

    @Test
    void scoreCentroid_perfectTopicMatch_scores04() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("energy", "renewable"), List.of(), null, null, "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy", "renewable"), List.of(), null, List.of(), 0.8, 5, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // topicOverlap = 2/2 = 1.0, entityOverlap = 0, typeMatch = 0
        // score = 1.0 * 0.4 + 0 + 0 = 0.4
        assertEquals(0.4, score, 0.001, "Perfect topic match should score 0.4");
    }

    @Test
    void scoreCentroid_perfectEntityMatch_scores05() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of(), List.of("GreenField Energy"), null, null, "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(), List.of("GreenField Energy"), null, List.of(), 0.8, 5, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // topicOverlap = 0, entityOverlap = 1/1 = 1.0, typeMatch = 0
        // score = 0 + 1.0 * 0.5 + 0 = 0.5
        assertEquals(0.5, score, 0.001, "Perfect entity match should score 0.5");
    }

    @Test
    void scoreCentroid_perfectTypeMatch_scores01() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("topic"), List.of(), "proposal", null, "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of(), List.of(), null, List.of("proposal"), 0.8, 5, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // topicOverlap = 0 (file has "topic", centroid has none), entityOverlap = 0
        // typeMatch = 1.0
        // score = 0 + 0 + 1.0 * 0.1 = 0.1
        assertEquals(0.1, score, 0.001, "Perfect type match should score 0.1");
    }

    @Test
    void scoreCentroid_fullMatch_scores10() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("energy", "renewable"),
                List.of("GreenField Energy"),
                "proposal",
                null,
                "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy", "renewable", "solar"),
                List.of("GreenField Energy", "Jane Smith"),
                null,
                List.of("proposal", "contract"),
                0.9, 8, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // topicOverlap = 2/2 = 1.0 (file topics: energy, renewable; both in centroid)
        // entityOverlap = 1/1 = 1.0 (GreenField Energy in both)
        // typeMatch = 1.0 (proposal in both)
        // score = 1.0 * 0.4 + 1.0 * 0.5 + 1.0 * 0.1 = 1.0
        assertEquals(1.0, score, 0.001, "Perfect full match should score 1.0");
    }

    @Test
    void scoreCentroid_partialTopicMatch_scoresProportionally() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("energy", "renewable", "solar", "wind"),
                List.of(), null, null, "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy", "solar"),
                List.of(), null, List.of(), 0.8, 5, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // topicOverlap = 2/4 = 0.5
        // score = 0.5 * 0.4 = 0.2
        assertEquals(0.2, score, 0.001, "Partial topic match (2/4) should score 0.2");
    }

    @Test
    void scoreCentroid_caseInsensitive() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("Energy", "RENEWABLE"),
                List.of("greenfield energy"),
                null, null, "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy", "renewable"),
                List.of("GreenField Energy"),
                null, List.of(), 0.8, 5, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // Should match case-insensitively
        // topicOverlap = 2/2 = 1.0
        // entityOverlap = 1/1 = 1.0
        // score = 0.4 + 0.5 = 0.9
        assertEquals(0.9, score, 0.001, "Should match case-insensitively");
    }

    @Test
    void scoreCentroid_noOverlap_returnsZero() {
        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("banking", "finance"),
                List.of("SpareBank"),
                "report", null, "test"
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy", "renewable"),
                List.of("GreenField Energy"),
                null,
                List.of("proposal"),
                0.8, 5, 0, null
        );

        double score = DirectoryScorer.scoreCentroid(sig, centroid);

        // No overlap in topics, entities, or document type
        assertEquals(0.0, score, 0.001, "No overlap should score 0.0");
    }

    // ---- score() with centroid data ----

    @Test
    void score_withCentroidData_improvesScore() throws Exception {
        Path workspaceRoot = tempDir;
        Path dir1 = Files.createDirectories(tempDir.resolve("greenfield"));

        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker, workspaceRoot);

        // File about renewable energy
        Path file = tempDir.resolve("renewable-energy-report.md");
        Files.writeString(file, "content");

        EnrichmentSignature fileSignature = new EnrichmentSignature(
                List.of("renewable", "energy"),
                List.of("GreenField Energy"),
                "report",
                null,
                "content-headers"
        );

        // Directory with matching centroid
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("report"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable", "energy", "solar"),
                List.of("GreenField Energy"),
                null,
                List.of("report"),
                0.9, 8, 0, null
        );

        DirectoryScorer.DirectoryCandidate candidate =
                new DirectoryScorer.DirectoryCandidate(dir1, identity);

        ScopeResolver.ResolvedScope fileScope =
                new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // Score without centroid
        List<DirectoryScorer.ScoredCandidate> withoutCentroid =
                scorer.score(file, fileScope, List.of(candidate));

        // Score with centroid
        Map<Path, DirectoryCentroid> centroids = Map.of(dir1, centroid);
        List<DirectoryScorer.ScoredCandidate> withCentroid =
                scorer.score(file, fileScope, List.of(candidate), fileSignature, centroids);

        // Centroid scoring should only improve (or equal) the score
        assertTrue(withCentroid.get(0).totalScore() >= withoutCentroid.get(0).totalScore(),
                "Centroid scoring should not reduce score. Without: "
                        + withoutCentroid.get(0).totalScore()
                        + ", With: " + withCentroid.get(0).totalScore());
    }

    @Test
    void score_withNullCentroidData_fallsBackToStandard() throws Exception {
        Path workspaceRoot = tempDir;
        Path dir1 = Files.createDirectories(tempDir.resolve("meetings"));

        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker, workspaceRoot);

        Path file = tempDir.resolve("standup.md");
        Files.writeString(file, "content");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("meeting-notes"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", ""
        );

        DirectoryScorer.DirectoryCandidate candidate =
                new DirectoryScorer.DirectoryCandidate(dir1, identity);

        ScopeResolver.ResolvedScope fileScope =
                new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // Score with null enrichment data
        List<DirectoryScorer.ScoredCandidate> result =
                scorer.score(file, fileScope, List.of(candidate), null, null);

        // Should produce the same result as standard scoring
        List<DirectoryScorer.ScoredCandidate> standard =
                scorer.score(file, fileScope, List.of(candidate));

        assertEquals(standard.get(0).totalScore(), result.get(0).totalScore(), 0.001,
                "Null enrichment data should fall back to standard scoring");
    }

    @Test
    void score_withEmptyCentroidMap_fallsBackToStandard() throws Exception {
        Path workspaceRoot = tempDir;
        Path dir1 = Files.createDirectories(tempDir.resolve("meetings"));

        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker, workspaceRoot);

        Path file = tempDir.resolve("standup.md");
        Files.writeString(file, "content");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("meeting-notes"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", ""
        );

        DirectoryScorer.DirectoryCandidate candidate =
                new DirectoryScorer.DirectoryCandidate(dir1, identity);

        EnrichmentSignature sig = new EnrichmentSignature(
                List.of("standup"), List.of(), null, null, "test"
        );

        ScopeResolver.ResolvedScope fileScope =
                new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // Score with empty centroid map
        List<DirectoryScorer.ScoredCandidate> result =
                scorer.score(file, fileScope, List.of(candidate), sig, Map.of());

        // Should produce the same result as standard scoring
        List<DirectoryScorer.ScoredCandidate> standard =
                scorer.score(file, fileScope, List.of(candidate));

        assertEquals(standard.get(0).totalScore(), result.get(0).totalScore(), 0.001,
                "Empty centroid map should fall back to standard scoring");
    }

    @Test
    void score_centroidBoostSelectsCorrectDirectory() throws Exception {
        Path workspaceRoot = tempDir;
        Path greenfield = Files.createDirectories(tempDir.resolve("greenfield"));
        Path banking = Files.createDirectories(tempDir.resolve("banking"));

        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker, workspaceRoot);

        // File about GreenField Energy
        Path file = tempDir.resolve("greenfield-proposal.pdf");
        Files.writeString(file, "");

        EnrichmentSignature fileSignature = new EnrichmentSignature(
                List.of("renewable", "energy"),
                List.of("GreenField Energy"),
                "proposal",
                null,
                "companion"
        );

        // GreenField directory with matching centroid
        DirectoryIdentity greenfieldIdentity = new DirectoryIdentity(
                List.of("client-material"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.7, null, "test", ""
        );
        DirectoryCentroid greenfieldCentroid = new DirectoryCentroid(
                List.of("renewable", "energy"),
                List.of("GreenField Energy"),
                null,
                List.of("proposal"),
                0.9, 8, 0, null
        );

        // Banking directory with non-matching centroid
        DirectoryIdentity bankingIdentity = new DirectoryIdentity(
                List.of("financial"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.7, null, "test", ""
        );
        DirectoryCentroid bankingCentroid = new DirectoryCentroid(
                List.of("banking", "finance"),
                List.of("SpareBank"),
                null,
                List.of("report"),
                0.9, 8, 0, null
        );

        List<DirectoryScorer.DirectoryCandidate> candidates = List.of(
                new DirectoryScorer.DirectoryCandidate(greenfield, greenfieldIdentity),
                new DirectoryScorer.DirectoryCandidate(banking, bankingIdentity)
        );

        Map<Path, DirectoryCentroid> centroids = Map.of(
                greenfield, greenfieldCentroid,
                banking, bankingCentroid
        );

        ScopeResolver.ResolvedScope fileScope =
                new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        List<DirectoryScorer.ScoredCandidate> result =
                scorer.score(file, fileScope, candidates, fileSignature, centroids);

        // GreenField directory should score highest
        assertEquals(greenfield, result.get(0).directory(),
                "GreenField directory should be top match. Results: "
                        + result.stream().map(r -> r.directory().getFileName() + "="
                        + String.format("%.3f", r.totalScore())).toList());
        assertTrue(result.get(0).totalScore() > result.get(1).totalScore(),
                "GreenField should score higher than banking");
    }

    @Test
    void score_centroidReasons_includeCentroidBoost() throws Exception {
        Path workspaceRoot = tempDir;
        Path dir1 = Files.createDirectories(tempDir.resolve("greenfield"));

        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker, workspaceRoot);

        Path file = tempDir.resolve("energy-report.md");
        Files.writeString(file, "");

        EnrichmentSignature fileSignature = new EnrichmentSignature(
                List.of("energy", "renewable"),
                List.of("GreenField Energy"),
                "report",
                null,
                "test"
        );

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of(), List.of(), List.of(),  // no type match
                ScopeLevel.WORKSPACE, null, null,
                0.5, null, "test", ""
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy", "renewable"),
                List.of("GreenField Energy"),
                null,
                List.of("report"),
                0.9, 5, 0, null
        );

        DirectoryScorer.DirectoryCandidate candidate =
                new DirectoryScorer.DirectoryCandidate(dir1, identity);

        Map<Path, DirectoryCentroid> centroids = Map.of(dir1, centroid);

        ScopeResolver.ResolvedScope fileScope =
                new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        List<DirectoryScorer.ScoredCandidate> result =
                scorer.score(file, fileScope, List.of(candidate), fileSignature, centroids);

        // Should have centroid-boost reason
        boolean hasCentroidReason = result.get(0).reasons().stream()
                .anyMatch(r -> r.contains("centroid-boost"));
        assertTrue(hasCentroidReason,
                "Reasons should include centroid-boost. Reasons: " + result.get(0).reasons());
    }

    // ---- Existing tests still pass (no centroid = same as before) ----

    @Test
    void score_existingBehavior_unchanged() throws Exception {
        Path workspaceRoot = tempDir;
        Path dir1 = Files.createDirectories(tempDir.resolve("meetings"));

        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker, workspaceRoot);

        Path file = tempDir.resolve("standup.md");
        Files.writeString(file, "");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("meeting-notes"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", ""
        );

        DirectoryScorer.DirectoryCandidate candidate =
                new DirectoryScorer.DirectoryCandidate(dir1, identity);

        ScopeResolver.ResolvedScope fileScope =
                new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // Standard scoring (no centroid)
        List<DirectoryScorer.ScoredCandidate> result =
                scorer.score(file, fileScope, List.of(candidate));

        // Should have a non-zero score from type matching
        assertTrue(result.get(0).totalScore() > 0,
                "Standard scoring should still work. Score: " + result.get(0).totalScore());
    }
}
