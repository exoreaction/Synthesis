package io.exoreaction.synthesis.graph;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enriches file relationship responses with documentation graph data from
 * {@code knowledge_edges}.
 *
 * <p>Given a source file path, returns which skill/doc files cover it, the
 * overall confidence in that coverage, and whether there is a documentation
 * gap. Both the CLI ({@code synthesis relate}) and the MCP {@code relate}
 * tool use this enrichment so agents receive code structure + documentation
 * state + trust signals in a single call.
 */
public class KnowledgeEnricher {

    /**
     * Aggregated documentation state for one source file.
     *
     * @param skills          edges (one per skill/entity pair) covering this source
     * @param bySkill         edges grouped by skill path — convenient for display
     * @param overallConfidence worst confidence across all edges (HIGH > MEDIUM > LOW > STALE)
     * @param hasGap          true when no edges exist (file has no skill coverage at all)
     */
    public record EnrichmentResult(
        List<KnowledgeEdge> skills,
        Map<String, List<KnowledgeEdge>> bySkill,
        String overallConfidence,
        boolean hasGap
    ) {
        public boolean hasDocumentation() { return !skills.isEmpty(); }
    }

    /**
     * Query {@code knowledge_edges} for the given source path and aggregate.
     *
     * @param sourcePath relative path of the source file
     * @param conn       open SQLite connection
     * @return enrichment result (empty/gap if no edges found)
     */
    public EnrichmentResult enrichForSource(String sourcePath, Connection conn)
            throws SQLException {
        String sql =
            "SELECT skill_path, source_path, entity_name, coverage_type, " +
            "skill_modified_at, source_modified_at, drift_days, confidence " +
            "FROM knowledge_edges WHERE source_path = ? ORDER BY confidence DESC";

        List<KnowledgeEdge> edges = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourcePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    edges.add(new KnowledgeEdge(
                        rs.getString("skill_path"),
                        rs.getString("source_path"),
                        rs.getString("entity_name"),
                        rs.getString("coverage_type"),
                        rs.getLong("skill_modified_at"),
                        rs.getLong("source_modified_at"),
                        rs.getInt("drift_days"),
                        rs.getString("confidence")
                    ));
                }
            }
        }

        if (edges.isEmpty()) {
            return new EnrichmentResult(List.of(), Map.of(), "NONE", true);
        }

        Map<String, List<KnowledgeEdge>> bySkill = edges.stream()
            .collect(Collectors.groupingBy(KnowledgeEdge::skillPath,
                LinkedHashMap::new, Collectors.toList()));

        String worstConfidence = worstOf(edges);

        return new EnrichmentResult(edges, bySkill, worstConfidence, false);
    }

    /**
     * Query enrichment for multiple source files at once (used by search enrichment).
     */
    public Map<String, EnrichmentResult> enrichForSources(List<String> sourcePaths,
                                                           Connection conn)
            throws SQLException {
        Map<String, EnrichmentResult> results = new LinkedHashMap<>();
        for (String path : sourcePaths) {
            results.put(path, enrichForSource(path, conn));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Formatting helpers for CLI
    // -----------------------------------------------------------------------

    /**
     * Format enrichment as a readable CLI section (plain text, ANSI-free).
     * Callers may wrap in ANSI coloring as needed.
     */
    public String formatForCli(EnrichmentResult result) {
        if (result.hasGap()) {
            return "  No skill/doc coverage found. Consider creating a skill for this file.";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<KnowledgeEdge>> e : result.bySkill().entrySet()) {
            String skillPath = e.getKey();
            List<KnowledgeEdge> skillEdges = e.getValue();
            String conf = worstOf(skillEdges);
            int drift = skillEdges.stream().mapToInt(KnowledgeEdge::driftDays).max().orElse(0);
            String driftNote = drift > 0 ? " — " + drift + " days stale" : "";
            sb.append("    ").append(skillPath)
              .append("  [").append(conf).append(driftNote).append("]\n");
            String entities = skillEdges.stream()
                .map(KnowledgeEdge::entityName)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(", "));
            if (!entities.isBlank()) {
                sb.append("      Covers: ").append(entities).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static final Map<String, Integer> CONFIDENCE_ORDER = Map.of(
        "HIGH", 3, "MEDIUM", 2, "LOW", 1, "STALE", 0, "NONE", -1
    );

    private String worstOf(List<KnowledgeEdge> edges) {
        return edges.stream()
            .map(KnowledgeEdge::confidence)
            .min(Comparator.comparingInt(c -> CONFIDENCE_ORDER.getOrDefault(c, -1)))
            .orElse("NONE");
    }
}
