package io.exoreaction.synthesis.org;

/**
 * Composite wrapper for directory metadata: identity + centroid + wants.
 *
 * <p>Introduced in Phase 2 to avoid expanding the 14-field {@link DirectoryIdentity}
 * record. Keeps concerns separated and makes centroid/wants optional without
 * polluting every identity construction site.
 *
 * @param identity the rule-based directory identity (Phase 1)
 * @param centroid the semantic centroid -- what the directory IS (Phase 2)
 * @param wants    what the directory is TRYING TO BECOME (Phase 2)
 */
public record DirectoryProfile(
        DirectoryIdentity identity,
        DirectoryCentroid centroid,
        DirectoryWants wants
) {

    /**
     * Canonical constructor -- ensures centroid and wants are never null.
     */
    public DirectoryProfile {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        centroid = centroid != null ? centroid : DirectoryCentroid.empty();
        wants = wants != null ? wants : DirectoryWants.empty();
    }

    /**
     * Creates a profile from an identity only, with empty centroid and wants.
     *
     * @param identity the directory identity
     * @return a profile wrapping the identity with empty centroid and wants
     */
    public static DirectoryProfile fromIdentity(DirectoryIdentity identity) {
        return new DirectoryProfile(identity, DirectoryCentroid.empty(), DirectoryWants.empty());
    }
}
