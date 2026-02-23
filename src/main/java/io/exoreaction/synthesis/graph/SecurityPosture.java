package io.exoreaction.synthesis.graph;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of security posture data queried from the
 * {@code security_findings} table. Used by summary, report, and maintain
 * commands to present security information.
 *
 * <p>Classifies findings into two groups:
 * <ul>
 *   <li><b>Agentic AI risks</b> (S016-S021): prompt injection, RAG poisoning,
 *       unconfirmed actions, missing boundaries</li>
 *   <li><b>Traditional risks</b> (S001-S014): SQL injection, XXE,
 *       deserialization, hardcoded secrets, weak crypto</li>
 * </ul>
 *
 * @param highCount     number of HIGH severity findings
 * @param mediumCount   number of MEDIUM severity findings
 * @param lowCount      number of LOW severity findings
 * @param infoCount     number of INFO severity findings
 * @param agenticCount  findings with agentic flow type
 * @param traditionalCount findings with non-agentic flow type
 * @param topSignals    top signals by finding count
 * @param fileCount     distinct files with findings
 * @param noData        true if no findings exist in the DB
 * @since v1.14.0 (Security)
 */
public record SecurityPosture(
        int highCount,
        int mediumCount,
        int lowCount,
        int infoCount,
        int agenticCount,
        int traditionalCount,
        List<SecurityRepository.SignalSummary> topSignals,
        int fileCount,
        boolean noData
) {

    /**
     * Queries the security_findings table and builds a SecurityPosture snapshot.
     *
     * @param conn          database connection
     * @param workspacePath workspace path string
     * @return posture snapshot, or an empty posture if the table does not exist
     */
    public static SecurityPosture query(Connection conn, String workspacePath) {
        try {
            SecurityRepository repo = new SecurityRepository();

            Map<String, Integer> severityCounts = repo.countFindingsBySeverity(conn, workspacePath);
            if (severityCounts.isEmpty()) {
                return empty();
            }

            int high = severityCounts.getOrDefault("HIGH", 0);
            int medium = severityCounts.getOrDefault("MEDIUM", 0);
            int low = severityCounts.getOrDefault("LOW", 0);
            int info = severityCounts.getOrDefault("INFO", 0);

            // Flow type counts
            Map<String, Integer> flowCounts = repo.countFindingsByFlowType(conn, workspacePath);
            int agentic = flowCounts.getOrDefault("agentic", 0);
            int traditional = 0;
            for (Map.Entry<String, Integer> entry : flowCounts.entrySet()) {
                if (!"agentic".equals(entry.getKey())) {
                    traditional += entry.getValue();
                }
            }

            // Top signals
            List<SecurityRepository.SignalSummary> topSignals = repo.getTopSignals(conn, workspacePath, 10);

            // Distinct file count from findings
            List<SecuritySignal> allFindings = repo.getFindings(conn, workspacePath);
            int fileCount = (int) allFindings.stream()
                    .map(SecuritySignal::filePath)
                    .distinct()
                    .count();

            return new SecurityPosture(high, medium, low, info, agentic, traditional,
                    topSignals, fileCount, false);

        } catch (Exception e) {
            // Table may not exist (old workspace without migration) -- graceful fallback
            return empty();
        }
    }

    /**
     * Returns an empty posture indicating no findings data is available.
     */
    public static SecurityPosture empty() {
        return new SecurityPosture(0, 0, 0, 0, 0, 0, List.of(), 0, true);
    }

    /**
     * Total number of findings across all severities.
     */
    public int totalCount() {
        return highCount + mediumCount + lowCount + infoCount;
    }

    /**
     * Formats a one-line executive summary: "47 HIGH, 12 MEDIUM, 116 LOW"
     */
    public String oneLiner() {
        if (noData || totalCount() == 0) return "No security findings";
        return highCount + " HIGH, " + mediumCount + " MEDIUM, " + lowCount + " LOW";
    }

    /**
     * Formats a detailed security posture section suitable for terminal or markdown.
     *
     * @param level "executive" for one-liner, "manager" for full breakdown, "developer" for details
     */
    public String format(String level) {
        if (noData) {
            return "Security data unavailable. Run `synthesis maintain` to populate findings.";
        }
        if (totalCount() == 0) {
            return "No security findings detected.";
        }

        StringBuilder sb = new StringBuilder();

        if ("executive".equalsIgnoreCase(level)) {
            sb.append("Security: ").append(oneLiner());
            if (agenticCount > 0) {
                sb.append(" (").append(agenticCount).append(" agentic AI risks)");
            }
            return sb.toString();
        }

        // Manager / Developer level
        sb.append("Security Posture\n");
        sb.append("\u2500".repeat(16)).append("\n");
        sb.append(String.format("HIGH:    %d findings across %d files%n", highCount, fileCount));
        sb.append(String.format("MEDIUM:  %d findings%n", mediumCount));
        sb.append(String.format("LOW:     %d findings%n", lowCount));

        if (!topSignals.isEmpty()) {
            sb.append("\nTop signals:\n");
            for (SecurityRepository.SignalSummary sig : topSignals.stream().limit(5).toList()) {
                String flowLabel = sig.flowType() != null ? " [" + sig.flowType() + "]" : "";
                sb.append(String.format("  %-35s %3d findings%s%n",
                        sig.signalId(), sig.count(), flowLabel));
            }
        }

        sb.append(String.format("%nAgentic AI risks: %d findings (unique to AI-native codebases)%n",
                agenticCount));
        sb.append(String.format("Traditional risks: %d findings%n", traditionalCount));

        if ("developer".equalsIgnoreCase(level)) {
            sb.append("\nRun: synthesis code-graph security --severity HIGH  for details\n");
        }

        return sb.toString();
    }

    /**
     * Formats the posture as a markdown section for executive reports.
     */
    public String formatMarkdown() {
        if (noData) {
            return "No security scan data available. Run `synthesis maintain` to populate findings.\n";
        }
        if (totalCount() == 0) {
            return "No security issues detected. Run `synthesis code-graph security --refresh` to rescan.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Security Posture\n\n");
        sb.append("**Automated scan via Synthesis CKG-5 \u2014 updated on every `maintain` run**\n\n");
        sb.append("| Severity | Count | Trend |\n");
        sb.append("|----------|-------|-------|\n");
        sb.append(String.format("| HIGH     | %d    | \u2014     |%n", highCount));
        sb.append(String.format("| MEDIUM   | %d    | \u2014     |%n", mediumCount));
        sb.append(String.format("| LOW      | %d    | \u2014     |%n", lowCount));
        sb.append("\n");
        sb.append(String.format("**Agentic AI risks (prompt injection, RAG poisoning, missing boundaries):** %d findings%n",
                agenticCount));
        sb.append(String.format("**Traditional risks (SQL injection, XXE, deserialization, secrets):** %d findings%n",
                traditionalCount));
        sb.append("\n*No external security consultant needed \u2014 Synthesis scans continuously.*\n");
        return sb.toString();
    }
}
