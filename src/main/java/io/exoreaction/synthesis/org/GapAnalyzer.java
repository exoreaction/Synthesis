package io.exoreaction.synthesis.org;

import java.util.List;
import java.util.Optional;

/**
 * Aspirational gap detection: compares a directory's centroid against matching
 * archetypes to find missing document types.
 *
 * <p>When a directory's centroid matches a known archetype (e.g. "client-opportunity"),
 * the archetype's expected document types are compared against the centroid's actual
 * document types. Missing types become aspirational gaps that populate the
 * {@link DirectoryWants#alsoLookingFor()} field.
 *
 * <p>For example, if a client directory has proposals and meeting notes but no
 * invoice, the invoice becomes an aspirational gap.
 *
 * @since v2.0 (P4-02)
 */
public class GapAnalyzer {

    private final ArchetypeRegistry registry;

    /**
     * Creates a gap analyzer with the default archetype registry.
     */
    public GapAnalyzer() {
        this(new ArchetypeRegistry());
    }

    /**
     * Creates a gap analyzer with a custom archetype registry.
     *
     * @param registry the archetype registry to use
     */
    public GapAnalyzer(ArchetypeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Analyzes a directory's centroid against known archetypes to detect
     * aspirational gaps (missing document types).
     *
     * @param centroid the directory's centroid to analyze
     * @return the analysis result, or empty if no archetype matches
     */
    public Optional<GapAnalysisResult> analyze(DirectoryCentroid centroid) {
        if (centroid == null || centroid.isEmpty()) {
            return Optional.empty();
        }

        Optional<ArchetypeRegistry.ArchetypeMatch> bestMatch = registry.findBestMatch(centroid);
        if (bestMatch.isEmpty()) {
            return Optional.empty();
        }

        DirectoryArchetype archetype = bestMatch.get().archetype();
        double matchScore = bestMatch.get().score();

        List<String> missingDocTypes = archetype.findMissingDocTypes(centroid);

        return Optional.of(new GapAnalysisResult(
                archetype.name(),
                matchScore,
                missingDocTypes,
                archetype.expectedDocTypes(),
                centroid.documentTypes()
        ));
    }

    /**
     * Updates a directory's wants with aspirational gaps detected from
     * archetype matching.
     *
     * <p>If the centroid matches an archetype and there are missing document types,
     * they are added to the wants' {@code alsoLookingFor} list. The wants source
     * is updated to reflect the archetype match.
     *
     * @param centroid the directory's centroid
     * @param currentWants the directory's current wants
     * @return updated wants with aspirational gaps, or the original wants if no gaps found
     */
    public DirectoryWants enrichWantsWithGaps(DirectoryCentroid centroid,
                                                DirectoryWants currentWants) {
        Optional<GapAnalysisResult> analysis = analyze(centroid);
        if (analysis.isEmpty() || analysis.get().missingDocTypes().isEmpty()) {
            return currentWants;
        }

        GapAnalysisResult result = analysis.get();

        // Merge existing alsoLookingFor with newly detected gaps
        java.util.Set<String> allGaps = new java.util.LinkedHashSet<>();
        if (currentWants != null && !currentWants.alsoLookingFor().isEmpty()) {
            allGaps.addAll(currentWants.alsoLookingFor());
        }
        allGaps.addAll(result.missingDocTypes());

        // Build updated source string
        String source = currentWants != null && currentWants.source() != null
                ? currentWants.source()
                : "inferred";
        source += " + archetype match: " + result.archetypeName()
                + " (" + String.format("%.2f", result.matchScore()) + ")";

        return new DirectoryWants(
                currentWants != null ? currentWants.topics() : List.of(),
                currentWants != null ? currentWants.entities() : List.of(),
                List.copyOf(allGaps),
                source,
                currentWants != null ? currentWants.satisfaction() : 0.0
        );
    }

    /**
     * The result of a gap analysis.
     *
     * @param archetypeName   name of the matched archetype
     * @param matchScore      how well the centroid matched the archetype (0.0-1.0)
     * @param missingDocTypes document types expected by the archetype but not present in centroid
     * @param expectedDocTypes all document types expected by the archetype
     * @param presentDocTypes  document types actually present in the centroid
     */
    public record GapAnalysisResult(
            String archetypeName,
            double matchScore,
            List<String> missingDocTypes,
            List<String> expectedDocTypes,
            List<String> presentDocTypes
    ) {}
}
