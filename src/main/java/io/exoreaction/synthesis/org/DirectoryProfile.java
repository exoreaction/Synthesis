package io.exoreaction.synthesis.org;

/**
 * Composite wrapper for directory metadata: identity + centroid + wants + health.
 *
 * <p>Introduced in Phase 2 to avoid expanding the 14-field {@link DirectoryIdentity}
 * record. Keeps concerns separated and makes centroid/wants/health optional without
 * polluting every identity construction site.
 *
 * @param identity the rule-based directory identity (Phase 1)
 * @param centroid the semantic centroid -- what the directory IS (Phase 2)
 * @param wants    what the directory is TRYING TO BECOME (Phase 2)
 * @param health   computed health signals (Phase 3)
 */
public record DirectoryProfile(
        DirectoryIdentity identity,
        DirectoryCentroid centroid,
        DirectoryWants wants,
        DirectoryHealth health
) {

    /**
     * Canonical constructor -- ensures centroid, wants, and health are never null.
     */
    public DirectoryProfile {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        centroid = centroid != null ? centroid : DirectoryCentroid.empty();
        wants = wants != null ? wants : DirectoryWants.empty();
        health = health != null ? health : DirectoryHealth.empty();
    }

    /**
     * Backward-compatible constructor with 3 fields (no health).
     * Health defaults to empty.
     *
     * @param identity the directory identity
     * @param centroid the semantic centroid
     * @param wants    the directory wants
     */
    public DirectoryProfile(DirectoryIdentity identity, DirectoryCentroid centroid,
                             DirectoryWants wants) {
        this(identity, centroid, wants, DirectoryHealth.empty());
    }

    /**
     * Creates a profile from an identity only, with empty centroid, wants, and health.
     *
     * @param identity the directory identity
     * @return a profile wrapping the identity with empty centroid, wants, and health
     */
    public static DirectoryProfile fromIdentity(DirectoryIdentity identity) {
        return new DirectoryProfile(identity, DirectoryCentroid.empty(), DirectoryWants.empty(),
                DirectoryHealth.empty());
    }
}
