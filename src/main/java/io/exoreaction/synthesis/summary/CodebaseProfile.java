package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.insights.InsightsEngine;
import io.exoreaction.synthesis.insights.InsightsEngine.InsightsReport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates an instant rule-based codebase profile from indexed data.
 * No AI required -- pure computation from Lucene index + InsightsEngine.
 *
 * <p>Produces structured metrics covering:
 * <ul>
 *   <li>Scale: file counts, total size, language distribution</li>
 *   <li>Quality: documentation coverage, test ratio, dead code</li>
 *   <li>Architecture: module count, coupling, circular deps</li>
 *   <li>Health: warnings, recommendations, risk indicators</li>
 * </ul>
 */
public class CodebaseProfile {

    // Records for structured metrics
    public record ScaleMetrics(
        int totalFiles,
        long totalSizeBytes,
        Map<String, Long> filesByType,
        Map<String, Long> filesByLanguage,
        List<String> repositories,
        int directoryCount
    ) {}

    public record QualityMetrics(
        double documentationCoverage,
        double testRatio,
        int testFiles,
        int sourceFiles,
        int deadCodeCandidates,
        List<String> hotspotFiles
    ) {}

    public record ArchitectureMetrics(
        int moduleCount,
        int circularDependencies,
        int layeringViolations,
        Map<String, Integer> topCoupledModules,
        double averageRefsPerFile
    ) {}

    public record HealthIndicator(
        String category,
        String status,  // "green", "yellow", "red"
        String detail
    ) {}

    public record Profile(
        ScaleMetrics scale,
        QualityMetrics quality,
        ArchitectureMetrics architecture,
        List<HealthIndicator> health,
        List<String> warnings,
        List<String> recommendations,
        Instant generatedAt
    ) {}

    /**
     * Generates a profile from the search index.
     */
    public Profile generate(SearchIndex index, Path workspaceRoot) throws IOException {
        List<SearchResult> allFiles = index.listAll(null, 50000);
        if (allFiles.isEmpty()) {
            return emptyProfile();
        }

        // Use InsightsEngine for heavy analysis
        InsightsEngine engine = new InsightsEngine();
        InsightsReport insights = engine.analyze(allFiles, workspaceRoot);

        // Build scale metrics
        ScaleMetrics scale = buildScaleMetrics(allFiles);

        // Build quality metrics from insights
        QualityMetrics quality = new QualityMetrics(
            insights.quality().documentationCoverage(),
            insights.quality().testRatio(),
            insights.quality().testFiles(),
            insights.quality().sourceFiles(),
            insights.quality().deadCodeCandidates().size(),
            insights.quality().hotspotFiles().stream().limit(10).toList()
        );

        // Build architecture metrics
        ArchitectureMetrics arch = buildArchitectureMetrics(insights, allFiles);

        // Build health indicators
        List<HealthIndicator> health = assessHealth(scale, quality, arch);

        return new Profile(
            scale,
            quality,
            arch,
            health,
            insights.warnings(),
            insights.recommendations(),
            Instant.now()
        );
    }

    private ScaleMetrics buildScaleMetrics(List<SearchResult> allFiles) {
        int totalFiles = allFiles.size();
        long totalSize = allFiles.stream()
            .mapToLong(SearchResult::sizeBytes)
            .sum();

        // Group by file type
        Map<String, Long> byType = allFiles.stream()
            .collect(Collectors.groupingBy(
                f -> f.fileType() != null ? f.fileType() : "UNKNOWN",
                Collectors.counting()
            ));

        // Group by language
        Map<String, Long> byLanguage = allFiles.stream()
            .filter(f -> f.language() != null && !f.language().isBlank())
            .collect(Collectors.groupingBy(
                SearchResult::language,
                Collectors.counting()
            ));

        // Extract unique repositories
        Set<String> repoSet = allFiles.stream()
            .map(SearchResult::repository)
            .filter(r -> r != null && !r.isBlank())
            .collect(Collectors.toSet());
        List<String> repositories = new ArrayList<>(repoSet);
        repositories.sort(String::compareTo);

        // Count unique directories
        Set<String> dirs = allFiles.stream()
            .map(f -> {
                String path = f.relativePath();
                int lastSlash = path.lastIndexOf('/');
                return lastSlash > 0 ? path.substring(0, lastSlash) : "";
            })
            .filter(d -> !d.isBlank())
            .collect(Collectors.toSet());

        return new ScaleMetrics(
            totalFiles,
            totalSize,
            byType,
            byLanguage,
            repositories,
            dirs.size()
        );
    }

    private ArchitectureMetrics buildArchitectureMetrics(
            InsightsReport insights,
            List<SearchResult> allFiles) {

        var archReport = insights.architecture();
        var connectivityReport = insights.connectivity();

        // Top coupled modules (from directory coupling)
        Map<String, Integer> topCoupled = archReport.directoryCoupling().entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, b) -> a,
                LinkedHashMap::new
            ));

        // Calculate average refs per file from connectivity metrics
        double avgRefs = connectivityReport.averageRefsPerFile();

        return new ArchitectureMetrics(
            archReport.moduleCount(),
            connectivityReport.circularClusters().size(),  // Count of circular dependency clusters
            archReport.layeringViolations().size(),  // Count of layering violations
            topCoupled,
            avgRefs
        );
    }

    private List<HealthIndicator> assessHealth(
            ScaleMetrics scale,
            QualityMetrics quality,
            ArchitectureMetrics arch) {

        List<HealthIndicator> indicators = new ArrayList<>();

        // Documentation coverage
        String docStatus = quality.documentationCoverage() >= 60.0 ? "green" :
                          quality.documentationCoverage() >= 30.0 ? "yellow" : "red";
        indicators.add(new HealthIndicator(
            "Documentation",
            docStatus,
            String.format("%.0f%% coverage", quality.documentationCoverage())
        ));

        // Test ratio
        String testStatus = quality.testRatio() >= 0.5 ? "green" :
                           quality.testRatio() >= 0.2 ? "yellow" : "red";
        indicators.add(new HealthIndicator(
            "Testing",
            testStatus,
            String.format("%.2f:1 test ratio (%d tests)", quality.testRatio(), quality.testFiles())
        ));

        // Architecture health
        String archStatus = arch.circularDependencies() == 0 &&
                           arch.averageRefsPerFile() < 10 ? "green" :
                           arch.circularDependencies() < 5 ? "yellow" : "red";
        indicators.add(new HealthIndicator(
            "Architecture",
            archStatus,
            arch.circularDependencies() == 0 ? "No circular dependencies" :
                String.format("%d circular dependencies detected", arch.circularDependencies())
        ));

        // Scale indicator
        String scaleStatus = scale.totalFiles() < 10000 ? "green" :
                            scale.totalFiles() < 50000 ? "yellow" : "red";
        indicators.add(new HealthIndicator(
            "Scale",
            scaleStatus,
            String.format("%,d files across %d directories", scale.totalFiles(), scale.directoryCount())
        ));

        return indicators;
    }

    private Profile emptyProfile() {
        return new Profile(
            new ScaleMetrics(0, 0, Map.of(), Map.of(), List.of(), 0),
            new QualityMetrics(0, 0, 0, 0, 0, List.of()),
            new ArchitectureMetrics(0, 0, 0, Map.of(), 0),
            List.of(new HealthIndicator("Status", "red", "No files indexed")),
            List.of("Workspace not scanned"),
            List.of("Run 'synthesis scan' to build the index"),
            Instant.now()
        );
    }
}
