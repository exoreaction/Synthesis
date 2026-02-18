package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.summary.CodebaseProfile.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodebaseProfile inner record classes — construction, field access, and defaults.
 */
class CodebaseProfileTest {

    // --- ScaleMetrics ---

    @Test
    void scaleMetrics_storesAllFields() {
        ScaleMetrics metrics = new ScaleMetrics(
                500, 1024L * 1024, Map.of("java", 300L, "md", 200L),
                Map.of("Java", 400L), List.of("repo-a", "repo-b"), 15);

        assertEquals(500, metrics.totalFiles());
        assertEquals(1024L * 1024, metrics.totalSizeBytes());
        assertEquals(300L, metrics.filesByType().get("java"));
        assertEquals(400L, metrics.filesByLanguage().get("Java"));
        assertEquals(2, metrics.repositories().size());
        assertEquals(15, metrics.directoryCount());
    }

    @Test
    void scaleMetrics_emptyCollections_allowed() {
        ScaleMetrics metrics = new ScaleMetrics(0, 0L, Map.of(), Map.of(), List.of(), 0);
        assertEquals(0, metrics.totalFiles());
        assertTrue(metrics.filesByType().isEmpty());
        assertTrue(metrics.repositories().isEmpty());
    }

    // --- QualityMetrics ---

    @Test
    void qualityMetrics_storesAllFields() {
        QualityMetrics metrics = new QualityMetrics(
                0.85, 0.20, 20, 80, 5, List.of("HotFile.java", "GodClass.java"));

        assertEquals(0.85, metrics.documentationCoverage(), 0.001);
        assertEquals(0.20, metrics.testRatio(), 0.001);
        assertEquals(20, metrics.testFiles());
        assertEquals(80, metrics.sourceFiles());
        assertEquals(5, metrics.deadCodeCandidates());
        assertEquals(2, metrics.hotspotFiles().size());
    }

    @Test
    void qualityMetrics_zeroValues_allowed() {
        QualityMetrics metrics = new QualityMetrics(0.0, 0.0, 0, 0, 0, List.of());
        assertEquals(0.0, metrics.documentationCoverage());
        assertEquals(0, metrics.testFiles());
        assertTrue(metrics.hotspotFiles().isEmpty());
    }

    @Test
    void qualityMetrics_highCoverage_storesProperly() {
        QualityMetrics metrics = new QualityMetrics(1.0, 0.5, 50, 50, 0, List.of());
        assertEquals(1.0, metrics.documentationCoverage());
        assertEquals(0.5, metrics.testRatio());
    }

    // --- ArchitectureMetrics ---

    @Test
    void architectureMetrics_storesAllFields() {
        Map<String, Integer> coupling = Map.of("ServiceA", 10, "ServiceB", 8);
        ArchitectureMetrics metrics = new ArchitectureMetrics(5, 2, 1, coupling, 3.5);

        assertEquals(5, metrics.moduleCount());
        assertEquals(2, metrics.circularDependencies());
        assertEquals(1, metrics.layeringViolations());
        assertEquals(2, metrics.topCoupledModules().size());
        assertEquals(3.5, metrics.averageRefsPerFile(), 0.001);
    }

    @Test
    void architectureMetrics_zeroCoupling_allowed() {
        ArchitectureMetrics metrics = new ArchitectureMetrics(3, 0, 0, Map.of(), 0.0);
        assertEquals(0, metrics.circularDependencies());
        assertTrue(metrics.topCoupledModules().isEmpty());
    }

    // --- HealthIndicator ---

    @Test
    void healthIndicator_storesAllFields() {
        HealthIndicator indicator = new HealthIndicator("Test Coverage", "yellow", "Coverage at 45%");
        assertEquals("Test Coverage", indicator.category());
        assertEquals("yellow", indicator.status());
        assertEquals("Coverage at 45%", indicator.detail());
    }

    @Test
    void healthIndicator_greenStatus_stored() {
        HealthIndicator indicator = new HealthIndicator("Overall", "green", "Healthy");
        assertEquals("green", indicator.status());
    }

    @Test
    void healthIndicator_redStatus_stored() {
        HealthIndicator indicator = new HealthIndicator("Security", "red", "Critical vulnerabilities found");
        assertEquals("red", indicator.status());
    }

    // --- Profile ---

    @Test
    void profile_storesAllFields() {
        Instant now = Instant.now();
        ScaleMetrics scale = new ScaleMetrics(100, 1024L, Map.of(), Map.of(), List.of(), 5);
        QualityMetrics quality = new QualityMetrics(0.7, 0.15, 15, 85, 3, List.of());
        ArchitectureMetrics arch = new ArchitectureMetrics(4, 0, 0, Map.of(), 2.0);
        List<HealthIndicator> health = List.of(new HealthIndicator("Overall", "green", "OK"));
        List<String> warnings = List.of("Warning 1");
        List<String> recommendations = List.of("Recommendation 1", "Recommendation 2");

        Profile profile = new Profile(scale, quality, arch, health, warnings, recommendations, now);

        assertSame(scale, profile.scale());
        assertSame(quality, profile.quality());
        assertSame(arch, profile.architecture());
        assertEquals(1, profile.health().size());
        assertEquals(1, profile.warnings().size());
        assertEquals(2, profile.recommendations().size());
        assertEquals(now, profile.generatedAt());
    }

    @Test
    void profile_emptyLists_allowed() {
        Profile profile = new Profile(
                new ScaleMetrics(0, 0L, Map.of(), Map.of(), List.of(), 0),
                new QualityMetrics(0.0, 0.0, 0, 0, 0, List.of()),
                new ArchitectureMetrics(0, 0, 0, Map.of(), 0.0),
                List.of(), List.of(), List.of(), Instant.now());

        assertTrue(profile.health().isEmpty());
        assertTrue(profile.warnings().isEmpty());
        assertTrue(profile.recommendations().isEmpty());
    }

    // --- SummaryResult ---

    @Test
    void fromProfile_setsFromCacheFalse() {
        Profile profile = emptyProfile();
        SummaryResult result = SummaryResult.fromProfile(profile,
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, 100L);
        assertFalse(result.fromCache());
    }

    @Test
    void fromProfile_aiSummaryIsNull() {
        Profile profile = emptyProfile();
        SummaryResult result = SummaryResult.fromProfile(profile,
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, 100L);
        assertNull(result.aiSummary());
    }

    @Test
    void withAiSummary_storesAiSummary() {
        Profile profile = emptyProfile();
        SummaryResult result = SummaryResult.withAiSummary(profile,
                "AI generated summary text",
                SummaryLevel.MANAGER, SummaryPerspective.ARCHITECT, 500L);
        assertEquals("AI generated summary text", result.aiSummary());
        assertFalse(result.fromCache());
    }

    @Test
    void withTemporal_storesTemporalContext() {
        Profile profile = emptyProfile();
        SummaryResult result = SummaryResult.withTemporal(profile,
                "AI summary", SummaryLevel.DEVELOPER, SummaryPerspective.DEVOPS,
                "Changes in last 7 days: 15 files modified", 300L);
        assertEquals("Changes in last 7 days: 15 files modified", result.temporalContext());
    }

    @Test
    void summaryResult_generationTimePreserved() {
        Profile profile = emptyProfile();
        SummaryResult result = SummaryResult.fromProfile(profile,
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, 1500L);
        assertEquals(1500L, result.generationTimeMs());
    }

    // --- helpers ---

    private static Profile emptyProfile() {
        return new Profile(
                new ScaleMetrics(0, 0L, Map.of(), Map.of(), List.of(), 0),
                new QualityMetrics(0.0, 0.0, 0, 0, 0, List.of()),
                new ArchitectureMetrics(0, 0, 0, Map.of(), 0.0),
                List.of(), List.of(), List.of(), Instant.now()
        );
    }
}
