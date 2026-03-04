package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.ClientRepoConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves declared cross-workspace links between docs directories and source
 * code workspaces, and enforces company-boundary policies.
 *
 * <p>Resolution is driven entirely by {@link SynthesisConfig}: no filesystem
 * access, no heuristic entity matching — all links are explicit declarations.
 *
 * <p>Resolution priority (first match wins):
 * <ol>
 *   <li>{@code clientRepos} entries (most specific, confidence 0.9)</li>
 *   <li>{@code subWorkspaces[].srcPath} (top-level, confidence 0.8)</li>
 * </ol>
 *
 * <p>Boundary policy is set via {@code workspace.crossCompanyLinks}:
 * <ul>
 *   <li>{@code never} — cross-company auto-linking always blocked</li>
 *   <li>{@code explicit_only} (default) — same-company always allowed; cross-company blocked</li>
 *   <li>{@code allow_all} — cross-company auto-linking allowed</li>
 * </ul>
 *
 * @since v1.22 (Issue #281)
 */
public class CrossWorkspaceResolver {

    /**
     * A resolved link between a docs directory and its corresponding source workspace.
     *
     * @param docsRelPath  relative docs path within workspace (e.g. {@code "eXOReaction"})
     * @param srcAbsPath   absolute path to the source workspace (e.g. {@code "/src/exoreaction"})
     * @param companyId    company / sub-workspace name (e.g. {@code "eXOReaction"})
     * @param confidence   0.9 for clientRepo match, 0.8 for subWorkspace srcPath match
     */
    public record CrossWorkspaceLink(
            String docsRelPath,
            String srcAbsPath,
            String companyId,
            double confidence
    ) {}

    /**
     * Returns the declared src links for a given docs node relative path.
     *
     * <p>Checks clientRepo entries first (most specific), then falls back to
     * the sub-workspace's {@code srcPath}.
     *
     * @param config      the workspace configuration
     * @param nodeRelPath relative path of the docs node (e.g. {@code "eXOReaction/marketing"})
     * @return list of resolved links (empty if none declared)
     */
    public List<CrossWorkspaceLink> resolve(SynthesisConfig config, String nodeRelPath) {
        List<CrossWorkspaceLink> results = new ArrayList<>();
        if (config == null || nodeRelPath == null || nodeRelPath.isBlank()) {
            return results;
        }

        String normalizedPath = normalizeTrailingSlash(nodeRelPath);

        for (SubWorkspaceConfig sub : config.getSubWorkspaces()) {
            // 1. Check clientRepos first (most specific)
            for (ClientRepoConfig cr : sub.getClientRepos()) {
                String docsPrefix = normalizeTrailingSlash(cr.getDocs());
                if (docsPrefix.isBlank()) continue;
                if (normalizedPath.equals(docsPrefix)
                        || normalizedPath.startsWith(docsPrefix + "/")) {
                    String src = stripTrailingSlash(cr.getSrc());
                    if (!src.isBlank()) {
                        results.add(new CrossWorkspaceLink(
                                nodeRelPath, src,
                                sub.getName().isBlank() ? docsPrefix : sub.getName(),
                                0.9));
                        return results; // clientRepo match wins immediately
                    }
                }
            }

            // 2. Fall back to subWorkspace srcPath
            String subPath = normalizeTrailingSlash(sub.getPath());
            if (!subPath.isBlank()
                    && (normalizedPath.equals(subPath)
                        || normalizedPath.startsWith(subPath + "/"))) {
                String src = stripTrailingSlash(sub.getSrcPath());
                if (!src.isBlank()) {
                    results.add(new CrossWorkspaceLink(
                            nodeRelPath, src, sub.getName(), 0.8));
                    return results; // first matching subWorkspace wins
                }
            }
        }

        return results;
    }

    /**
     * Returns whether two docs paths can be auto-linked based on the
     * {@code workspace.crossCompanyLinks} policy.
     *
     * <ul>
     *   <li>Same-company paths → always {@code true}</li>
     *   <li>Cross-company + policy {@code allow_all} → {@code true}</li>
     *   <li>Cross-company + any other policy → {@code false}</li>
     * </ul>
     *
     * @param config    the workspace configuration
     * @param docsPath1 first docs path
     * @param docsPath2 second docs path
     * @return {@code true} if these paths may be linked
     */
    public boolean canLink(SynthesisConfig config, String docsPath1, String docsPath2) {
        if (config == null || docsPath1 == null || docsPath2 == null) return false;

        String company1 = resolveCompany(config, docsPath1);
        String company2 = resolveCompany(config, docsPath2);

        // Same company → always allowed
        if (company1 != null && company1.equals(company2)) return true;

        // Both under the same unknown company (no sub-workspace matched) → allow
        if (company1 == null && company2 == null) return true;

        // Cross-company: check policy
        String policy = config.getWorkspace().getCrossCompanyLinks();
        return "allow_all".equals(policy);
    }

    // ---- Helpers ----

    /** Returns the company id (sub-workspace name) for a given docs path, or null. */
    private String resolveCompany(SynthesisConfig config, String docsPath) {
        String normalized = normalizeTrailingSlash(docsPath);
        for (SubWorkspaceConfig sub : config.getSubWorkspaces()) {
            String subPath = normalizeTrailingSlash(sub.getPath());
            if (!subPath.isBlank()
                    && (normalized.equals(subPath)
                        || normalized.startsWith(subPath + "/"))) {
                return sub.getName();
            }
        }
        return null;
    }

    private static String normalizeTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String stripTrailingSlash(String s) {
        return normalizeTrailingSlash(s);
    }
}
