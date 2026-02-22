package io.exoreaction.synthesis.org;

/**
 * Composite wrapper for directory metadata: identity + centroid + wants + health + classification.
 *
 * <p>Introduced in Phase 2 to avoid expanding the 14-field {@link DirectoryIdentity}
 * record. Keeps concerns separated and makes centroid/wants/health optional without
 * polluting every identity construction site.
 *
 * @param identity       the rule-based directory identity (Phase 1)
 * @param centroid       the semantic centroid -- what the directory IS (Phase 2)
 * @param wants          what the directory is TRYING TO BECOME (Phase 2)
 * @param health         computed health signals (Phase 3)
 * @param classification the directory classification (Phase 5: CODE, DOCUMENT, MEDIA, GENERATED, UNKNOWN)
 */
public record DirectoryProfile(
        DirectoryIdentity identity,
        DirectoryCentroid centroid,
        DirectoryWants wants,
        DirectoryHealth health,
        DirectoryClassification classification
) {

    /**
     * Canonical constructor -- ensures centroid, wants, health, and classification are never null.
     */
    public DirectoryProfile {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        centroid = centroid != null ? centroid : DirectoryCentroid.empty();
        wants = wants != null ? wants : DirectoryWants.empty();
        health = health != null ? health : DirectoryHealth.empty();
        classification = classification != null ? classification : DirectoryClassification.UNKNOWN;
    }

    /**
     * Backward-compatible constructor with 4 fields (no classification).
     * Classification defaults to UNKNOWN.
     *
     * @param identity the directory identity
     * @param centroid the semantic centroid
     * @param wants    the directory wants
     * @param health   computed health signals
     */
    public DirectoryProfile(DirectoryIdentity identity, DirectoryCentroid centroid,
                             DirectoryWants wants, DirectoryHealth health) {
        this(identity, centroid, wants, health, DirectoryClassification.UNKNOWN);
    }

    /**
     * Backward-compatible constructor with 3 fields (no health or classification).
     * Health defaults to empty, classification defaults to UNKNOWN.
     *
     * @param identity the directory identity
     * @param centroid the semantic centroid
     * @param wants    the directory wants
     */
    public DirectoryProfile(DirectoryIdentity identity, DirectoryCentroid centroid,
                             DirectoryWants wants) {
        this(identity, centroid, wants, DirectoryHealth.empty(), DirectoryClassification.UNKNOWN);
    }

    /**
     * Creates a profile from an identity only, with empty centroid, wants, health,
     * and UNKNOWN classification.
     *
     * @param identity the directory identity
     * @return a profile wrapping the identity with defaults
     */
    public static DirectoryProfile fromIdentity(DirectoryIdentity identity) {
        return new DirectoryProfile(identity, DirectoryCentroid.empty(), DirectoryWants.empty(),
                DirectoryHealth.empty(), DirectoryClassification.UNKNOWN);
    }
}
