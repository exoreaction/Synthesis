package io.exoreaction.synthesis.org;

import java.util.List;

/**
 * Computed health signals for a directory.
 *
 * <p>Aggregates structural and semantic quality indicators into a single
 * composite view. Written to the {@code health:} block in {@code .synthesis.md}
 * during sync.
 *
 * @param cohesion      how internally consistent the directory's content is (0.0-1.0)
 * @param drift         whether the centroid has drifted from stated wants
 * @param satisfaction  want satisfaction score (0.0-1.0), or 1.0 if no wants
 * @param status        summary status: "healthy", "bootstrapping", "starving", "drifting"
 * @param outliers      file paths that don't fit the directory's centroid (future use)
 * @since v1.15.0 (P3-09)
 */
public record DirectoryHealth(
        double cohesion,
        boolean drift,
        double satisfaction,
        String status,
        List<String> outliers
) {

    /** Canonical constructor -- ensures list fields are never null. */
    public DirectoryHealth {
        outliers = outliers != null ? outliers : List.of();
    }

    /**
     * Returns an empty health record representing a directory with no computed health data.
     */
    public static DirectoryHealth empty() {
        return new DirectoryHealth(0.0, false, 0.0, "unknown", List.of());
    }

    /**
     * Returns {@code true} if this health record has no meaningful data.
     */
    public boolean isEmpty() {
        return cohesion == 0.0 && !drift && satisfaction == 0.0
                && ("unknown".equals(status) || status == null)
                && outliers.isEmpty();
    }

    /**
     * Computes directory health from profile data.
     *
     * <p>Status logic:
     * <ul>
     *   <li><b>bootstrapping:</b> centroid is empty or has low confidence (< 0.3)</li>
     *   <li><b>starving:</b> has wants with satisfaction < 0.1</li>
     *   <li><b>drifting:</b> centroid confidence > 0.5 AND satisfaction < 0.4</li>
     *   <li><b>healthy:</b> everything else</li>
     * </ul>
     *
     * @param centroid the directory's centroid (may be empty)
     * @param wants    the directory's wants (may be empty)
     * @return computed health
     */
    public static DirectoryHealth compute(DirectoryCentroid centroid, DirectoryWants wants) {
        double satisfaction = (wants == null || wants.isEmpty()) ? 1.0 : wants.satisfaction();
        double cohesion = (centroid == null || centroid.isEmpty()) ? 0.0 : centroid.confidence();
        boolean drift = false;
        String status;

        // Determine status
        if (centroid == null || centroid.isEmpty() || centroid.confidence() < 0.3) {
            status = "bootstrapping";
        } else if (wants != null && !wants.isEmpty() && satisfaction < 0.1) {
            status = "starving";
        } else if (centroid.confidence() > 0.5
                && wants != null && !wants.isEmpty()
                && satisfaction < 0.4) {
            status = "drifting";
            drift = true;
        } else {
            status = "healthy";
        }

        return new DirectoryHealth(cohesion, drift, satisfaction, status, List.of());
    }
}
