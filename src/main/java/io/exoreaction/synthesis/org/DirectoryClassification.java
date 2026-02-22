package io.exoreaction.synthesis.org;

/**
 * Classification of a directory's role within a workspace.
 *
 * <p>Used by {@link DirectoryClassifier} to determine whether centroid/wants/health
 * computation and routing should be applied to a directory. The centroid/wants/health
 * system was designed for document workspaces and produces nonsensical output when
 * applied to source code trees or generated artifact directories.
 *
 * @since v1.16.0
 */
public enum DirectoryClassification {

    /**
     * Directory primarily contains documents (markdown, PDFs, text, presentations).
     * Full centroid/wants/health/routing processing is appropriate.
     */
    DOCUMENT(false, false, false, false),

    /**
     * Directory is part of a source code tree (Java packages, Python modules, etc.).
     * Centroid/wants/health computation would produce nonsensical output.
     * Routing into code directories is never appropriate.
     */
    CODE(true, true, true, true),

    /**
     * Directory primarily contains media files (images, video, audio).
     * Centroid computation may be useful but wants/health checks are not meaningful.
     */
    MEDIA(false, true, true, false),

    /**
     * Directory contains generated or build artifacts (target/, node_modules/, dist/).
     * All semantic processing should be skipped.
     */
    GENERATED(true, true, true, true),

    /**
     * Classification could not be determined. Falls back to full processing
     * (same as {@link #DOCUMENT}).
     */
    UNKNOWN(false, false, false, false);

    private final boolean skipCentroid;
    private final boolean skipWants;
    private final boolean skipHealth;
    private final boolean skipRouting;

    DirectoryClassification(boolean skipCentroid, boolean skipWants,
                            boolean skipHealth, boolean skipRouting) {
        this.skipCentroid = skipCentroid;
        this.skipWants = skipWants;
        this.skipHealth = skipHealth;
        this.skipRouting = skipRouting;
    }

    /** Whether centroid computation should be skipped for this classification. */
    public boolean skipCentroid() {
        return skipCentroid;
    }

    /** Whether wants bootstrapping should be skipped for this classification. */
    public boolean skipWants() {
        return skipWants;
    }

    /** Whether health computation should be skipped for this classification. */
    public boolean skipHealth() {
        return skipHealth;
    }

    /** Whether this directory should be excluded from routing candidates. */
    public boolean skipRouting() {
        return skipRouting;
    }
}
