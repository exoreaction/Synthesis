package io.exoreaction.synthesis.graph;

/**
 * Options controlling the scope of a security analysis run.
 *
 * @param scanSecrets       if true, also scan non-Java files for hardcoded secrets
 * @param mapAttackSurface  if true, perform BFS attack surface mapping
 * @param includeTests      if true, include test files in the analysis
 * @since v1.14.0 (Security)
 */
public record SecurityAnalysisOptions(
        boolean scanSecrets,
        boolean mapAttackSurface,
        boolean includeTests
) {
    /**
     * Returns default options: no secret scanning, no attack surface mapping,
     * exclude test files.
     */
    public static SecurityAnalysisOptions defaults() {
        return new SecurityAnalysisOptions(false, false, false);
    }
}
