package io.exoreaction.synthesis.org;

import java.util.*;

/**
 * Registry of known directory archetypes -- patterns describing what complete
 * directories of various types should contain.
 *
 * <p>Ships with 6 built-in archetypes:
 * <ul>
 *   <li>{@code client-opportunity} -- client engagement lifecycle</li>
 *   <li>{@code project} -- software project structure</li>
 *   <li>{@code methodology} -- methodology documentation</li>
 *   <li>{@code marketing-campaign} -- marketing campaign assets</li>
 *   <li>{@code product} -- product development and documentation</li>
 *   <li>{@code archive} -- historical/archived content</li>
 * </ul>
 *
 * <p>The registry is extensible: custom archetypes can be registered via
 * {@link #register(DirectoryArchetype)}. Built-in defaults are always available.
 *
 * @since v2.0 (P4-01)
 */
public class ArchetypeRegistry {

    private final Map<String, DirectoryArchetype> archetypes = new LinkedHashMap<>();

    /**
     * Creates a registry pre-loaded with built-in archetypes.
     */
    public ArchetypeRegistry() {
        registerDefaults();
    }

    /**
     * Registers a custom archetype. Overwrites any existing archetype with the same name.
     *
     * @param archetype the archetype to register
     */
    public void register(DirectoryArchetype archetype) {
        archetypes.put(archetype.name(), archetype);
    }

    /**
     * Returns the archetype with the given name, or empty if not found.
     *
     * @param name archetype name
     * @return the archetype, or empty
     */
    public Optional<DirectoryArchetype> get(String name) {
        return Optional.ofNullable(archetypes.get(name));
    }

    /**
     * Returns all registered archetypes.
     *
     * @return unmodifiable collection of all archetypes
     */
    public Collection<DirectoryArchetype> getAll() {
        return Collections.unmodifiableCollection(archetypes.values());
    }

    /**
     * Finds the best-matching archetype for a given centroid.
     *
     * <p>Iterates all registered archetypes, computes match scores, and returns
     * the archetype with the highest score -- but only if that score exceeds the
     * archetype's {@code matchThreshold}.
     *
     * @param centroid the centroid to match
     * @return the best match with its score, or empty if no archetype exceeds its threshold
     */
    public Optional<ArchetypeMatch> findBestMatch(DirectoryCentroid centroid) {
        if (centroid == null || centroid.isEmpty()) {
            return Optional.empty();
        }

        DirectoryArchetype bestArchetype = null;
        double bestScore = 0.0;

        for (DirectoryArchetype archetype : archetypes.values()) {
            double score = archetype.matchScore(centroid);
            if (score >= archetype.matchThreshold() && score > bestScore) {
                bestArchetype = archetype;
                bestScore = score;
            }
        }

        if (bestArchetype == null) {
            return Optional.empty();
        }

        return Optional.of(new ArchetypeMatch(bestArchetype, bestScore));
    }

    /**
     * Finds all archetypes that match a centroid above their threshold.
     *
     * @param centroid the centroid to match
     * @return list of matches sorted by score descending
     */
    public List<ArchetypeMatch> findAllMatches(DirectoryCentroid centroid) {
        if (centroid == null || centroid.isEmpty()) {
            return List.of();
        }

        List<ArchetypeMatch> matches = new ArrayList<>();
        for (DirectoryArchetype archetype : archetypes.values()) {
            double score = archetype.matchScore(centroid);
            if (score >= archetype.matchThreshold()) {
                matches.add(new ArchetypeMatch(archetype, score));
            }
        }

        matches.sort(Comparator.comparingDouble(ArchetypeMatch::score).reversed());
        return matches;
    }

    /**
     * Returns the number of registered archetypes.
     */
    public int size() {
        return archetypes.size();
    }

    /**
     * A match result: an archetype and the score of the match.
     *
     * @param archetype the matched archetype
     * @param score     the match score (0.0-1.0)
     */
    public record ArchetypeMatch(
            DirectoryArchetype archetype,
            double score
    ) {}

    // ---- Built-in archetypes ----

    private void registerDefaults() {
        register(new DirectoryArchetype(
                "client-opportunity",
                List.of("client", "opportunity", "partnership", "engagement",
                        "proposal", "contract"),
                List.of("proposal", "contract", "meeting-notes", "invoice",
                        "correspondence", "deliverable"),
                0.20
        ));

        register(new DirectoryArchetype(
                "project",
                List.of("project", "development", "implementation", "build",
                        "design", "testing"),
                List.of("readme", "design-doc", "implementation", "tests",
                        "documentation", "changelog"),
                0.20
        ));

        register(new DirectoryArchetype(
                "methodology",
                List.of("methodology", "framework", "process", "best-practice",
                        "standard", "guideline"),
                List.of("overview", "guide", "reference", "case-study",
                        "template", "checklist"),
                0.20
        ));

        register(new DirectoryArchetype(
                "marketing-campaign",
                List.of("marketing", "campaign", "brand", "content",
                        "social", "promotion"),
                List.of("strategy", "content-plan", "visual-assets", "copy",
                        "analytics-report", "schedule"),
                0.20
        ));

        register(new DirectoryArchetype(
                "product",
                List.of("product", "feature", "roadmap", "release",
                        "specification", "demo"),
                List.of("product-spec", "roadmap", "user-guide", "release-notes",
                        "demo-script", "architecture"),
                0.20
        ));

        register(new DirectoryArchetype(
                "archive",
                List.of("archive", "historical", "legacy", "deprecated",
                        "retired", "old"),
                List.of("index", "migration-notes"),
                0.25
        ));
    }
}
