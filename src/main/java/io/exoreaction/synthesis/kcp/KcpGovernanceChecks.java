package io.exoreaction.synthesis.kcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Governance cross-checks: does a KCP unit's declared governance contradict what
 * Synthesis actually observes in the referenced files (issue #360, Phase 7)?
 *
 * <p>This is the differentiator of the epic — Synthesis is the only tool in the
 * stack that holds both the manifest <em>and</em> a CKG-5 security scan over the
 * same files, so it can catch declarations that reality contradicts.
 *
 * <p>G-series findings (folded into {@code synthesis kcp verify}):
 * <ul>
 *   <li><b>G001</b> SENSITIVITY_CONTRADICTS_SECURITY (HIGH) — a unit declares
 *       {@code sensitivity: public} but its path has a HIGH-severity security
 *       finding (e.g. a hardcoded secret or injection sink)</li>
 *   <li><b>G002</b> SHARE_EXTERNAL_ON_RESTRICTED (HIGH) — a unit grants
 *       {@code authority.share_externally: initiative} while it (or the manifest)
 *       declares a {@code compliance.data_residency} restriction</li>
 *   <li><b>G003</b> UNKNOWN_VISIBILITY_SCOPE (LOW) — a {@code visibility} rule
 *       references an environment/agent_role the org registry doesn't know</li>
 * </ul>
 */
public final class KcpGovernanceChecks {

    private static final ObjectMapper JSON = new ObjectMapper();

    private KcpGovernanceChecks() {
    }

    /** One governance finding. */
    public record Finding(String checkId, String severity, String unitId, String detail) {}

    /**
     * Runs the G-series over one manifest's units.
     *
     * @param units                the manifest's persisted unit rows
     * @param rootExtensionsJson   the manifest's root extensions (for manifest-level compliance)
     * @param highFindingsByPath   repo-relative path → HIGH security signal ids on that file
     * @param knownEnvironments    environments/roles the org registry recognises (may be empty)
     */
    public static List<Finding> check(List<KcpRepository.KcpUnitRow> units,
                                      String rootExtensionsJson,
                                      Map<String, List<String>> highFindingsByPath,
                                      Set<String> knownEnvironments) {
        List<Finding> findings = new ArrayList<>();
        JsonNode rootExt = parse(rootExtensionsJson);
        boolean manifestResidencyRestricted = hasDataResidency(rootExt);

        for (KcpRepository.KcpUnitRow unit : units) {
            JsonNode ext = parse(unit.extensionsJson());
            if (ext == null) continue;

            // G001 — public sensitivity over a file with HIGH security findings
            String sensitivity = text(ext, "sensitivity");
            if ("public".equalsIgnoreCase(sensitivity) && unit.path() != null) {
                List<String> hits = highFindingsByPath.get(unit.path().replace('\\', '/'));
                if (hits != null && !hits.isEmpty()) {
                    findings.add(new Finding("G001", "HIGH", unit.unitId(),
                            "declares sensitivity: public but '" + unit.path()
                                    + "' has HIGH security finding(s): " + String.join(", ", hits)));
                }
            }

            // G002 — share_externally on data-residency-restricted content
            JsonNode authority = ext.get("authority");
            String shareExternally = authority != null ? text(authority, "share_externally") : null;
            boolean unitResidencyRestricted = hasDataResidency(ext);
            if ("initiative".equalsIgnoreCase(shareExternally)
                    && (unitResidencyRestricted || manifestResidencyRestricted)) {
                findings.add(new Finding("G002", "HIGH", unit.unitId(),
                        "grants authority.share_externally: initiative while compliance.data_residency "
                                + "is restricted — external sharing contradicts the residency rule"));
            }

            // G003 — visibility scopes unknown to the org registry
            if (!knownEnvironments.isEmpty()) {
                JsonNode visibility = ext.get("visibility");
                if (visibility != null && visibility.isArray()) {
                    for (JsonNode rule : visibility) {
                        String env = text(rule, "environment");
                        if (env != null && !knownEnvironments.contains(env.toLowerCase())) {
                            findings.add(new Finding("G003", "LOW", unit.unitId(),
                                    "visibility rule references unknown environment '" + env + "'"));
                        }
                    }
                }
            }
        }
        return findings;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean hasDataResidency(JsonNode node) {
        if (node == null) return false;
        JsonNode compliance = node.get("compliance");
        if (compliance == null) return false;
        JsonNode residency = compliance.get("data_residency");
        return residency != null && !residency.isNull()
                && !residency.asText().isBlank()
                && !"any".equalsIgnoreCase(residency.asText());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
