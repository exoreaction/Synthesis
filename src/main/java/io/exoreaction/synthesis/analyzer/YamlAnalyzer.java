package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.kcp.KcpRelationship;
import io.exoreaction.synthesis.kcp.KcpUnit;
import io.exoreaction.synthesis.util.FileUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Analyzes YAML files to extract structure and key metadata.
 *
 * <p>Detects special YAML formats:
 * <ul>
 *   <li>KCP manifests (knowledge.yaml — project/id + units)</li>
 *   <li>Claude Code skill files (name, description, steps)</li>
 *   <li>Docker Compose files (services)</li>
 *   <li>GitHub Actions workflows (jobs)</li>
 *   <li>Kubernetes manifests (kind, apiVersion)</li>
 *   <li>Generic YAML configuration</li>
 * </ul>
 */
public class YamlAnalyzer implements FileAnalyzer {

    private static final int CONTENT_PREVIEW_LIMIT = 10240;

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.YAML;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String content = Files.readString(metadata.path());
        if (content.isBlank()) {
            return AnalysisResult.empty();
        }

        // Parse YAML safely
        Map<String, Object> data;
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(content);
            if (parsed instanceof Map<?, ?> map) {
                // SnakeYAML may parse some keys as non-String types
                // (e.g., bare "on" becomes Boolean.TRUE, "3.8" becomes Double).
                // Normalize all keys to strings for consistent handling.
                data = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    data.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else {
                // YAML is a list or scalar at top level
                return AnalysisResult.minimal("YAML data file", truncate(content));
            }
        } catch (Exception e) {
            // Invalid YAML -- still index the raw content
            return AnalysisResult.minimal("YAML file (parse error: " + e.getMessage() + ")", truncate(content));
        }

        // Detect YAML type and extract accordingly
        String yamlType = detectYamlType(data, metadata.fileName());
        List<String> topLevelKeys = new ArrayList<>(data.keySet());
        List<String> keywords = new ArrayList<>();
        String summary;

        switch (yamlType) {
            case "kcp-manifest" -> {
                KcpExtraction kcp = extractKcpManifestInfo(data);
                summary = kcp.summary();
                keywords.addAll(kcp.keywords());
                // Replace top-level YAML keys with unit IDs as headings
                return AnalysisResult.builder()
                        .summary(summary)
                        .headings(kcp.unitIds())
                        .keywords(keywords)
                        .structure(String.format("KCP manifest (v%s), %d units", kcp.kcpVersion(), kcp.unitIds().size()))
                        .metrics(kcp.metrics())
                        .contentPreview(truncate(content))
                        .build();
            }
            case "claude-skill" -> {
                summary = extractClaudeSkillInfo(data);
                keywords.add("claude-code");
                keywords.add("skill");
                addIfPresent(keywords, data, "name");
            }
            case "docker-compose" -> {
                summary = extractDockerComposeInfo(data);
                keywords.add("docker");
                keywords.add("compose");
            }
            case "github-actions" -> {
                summary = extractGitHubActionsInfo(data);
                keywords.add("github-actions");
                keywords.add("ci-cd");
            }
            case "kubernetes" -> {
                summary = extractKubernetesInfo(data);
                keywords.add("kubernetes");
                keywords.add("k8s");
            }
            default -> {
                summary = "YAML configuration with keys: " +
                        String.join(", ", topLevelKeys.subList(0, Math.min(5, topLevelKeys.size())));
            }
        }

        keywords.add("yaml");

        // Structure description
        String structure = String.format("YAML (%s), %d top-level keys", yamlType, topLevelKeys.size());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("topLevelKeys", topLevelKeys.size());
        metrics.put("yamlType", yamlType);

        return AnalysisResult.builder()
                .summary(summary)
                .headings(topLevelKeys)
                .keywords(keywords)
                .structure(structure)
                .metrics(metrics)
                .contentPreview(truncate(content))
                .build();
    }

    private String detectYamlType(Map<String, Object> data, String fileName) {
        // KCP manifest: named knowledge.yaml with (project or id) + units list
        if ("knowledge.yaml".equals(fileName)
                && data.get("units") instanceof List<?>
                && (data.containsKey("project") || data.containsKey("id"))) {
            return "kcp-manifest";
        }

        // Claude Code skill detection
        if (data.containsKey("name") && (data.containsKey("steps") || data.containsKey("instructions"))) {
            return "claude-skill";
        }

        // Docker Compose
        if (data.containsKey("services") && (data.containsKey("version") || data.containsKey("networks"))) {
            return "docker-compose";
        }

        // GitHub Actions
        // Note: YAML parses bare "on" as Boolean.TRUE, so after key normalization
        // it becomes the string "true". Check both forms.
        boolean hasOnKey = data.containsKey("on") || data.containsKey("true");
        if (hasOnKey && data.containsKey("jobs")) {
            return "github-actions";
        }
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            if (data.containsKey("name") && hasOnKey) {
                return "github-actions";
            }
        }

        // Kubernetes
        if (data.containsKey("apiVersion") && data.containsKey("kind")) {
            return "kubernetes";
        }

        return "generic";
    }

    private record KcpExtraction(
            String summary, String kcpVersion,
            List<String> unitIds, List<String> keywords, Map<String, Object> metrics,
            List<KcpUnit> units, List<KcpRelationship> relationships) {}

    @SuppressWarnings("unchecked")
    private KcpExtraction extractKcpManifestInfo(Map<String, Object> data) {
        String project = data.containsKey("project")
                ? getString(data, "project") : getString(data, "id");
        String kcpVersion = getString(data, "kcp_version");
        if (kcpVersion.isEmpty()) kcpVersion = "unknown";

        List<String> unitIds = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        keywords.add("kcp");
        keywords.add("knowledge-context-protocol");
        keywords.add("knowledge-yaml");
        List<KcpUnit> units = new ArrayList<>();
        List<KcpRelationship> relationships = new ArrayList<>();

        // --- Root-level temporal defaults (applied to units that don't override) ---
        String rootValidFrom = null;
        String rootValidUntil = null;
        Object rootTemporalObj = data.get("temporal");
        if (rootTemporalObj instanceof Map<?, ?> rootTemporal) {
            rootValidFrom = getNestedString(rootTemporal, "valid_from");
            rootValidUntil = getNestedString(rootTemporal, "valid_until");
        }

        // --- Root-level not_for (§3.10) ---
        List<String> rootNotFor = extractStringList(data.get("not_for"));

        // --- Root-level content_structure ---
        String rootContentStructurePrimary = null;
        String rootContentStructureDensity = null;
        Object rootCsObj = data.get("content_structure");
        if (rootCsObj instanceof Map<?, ?> rootCs) {
            rootContentStructurePrimary = getNestedString(rootCs, "primary");
            rootContentStructureDensity = getNestedString(rootCs, "density");
        }

        // --- Root-level discovery ---
        String rootVerificationStatus = null;
        double rootConfidence = -1.0;
        String rootVerifiedBy = null;
        String rootEvidence = null;
        String rootVerifiedAt = null;
        Object discoveryObj = data.get("discovery");
        if (discoveryObj instanceof Map<?, ?> discovery) {
            rootVerificationStatus = getNestedString(discovery, "verification_status");
            Object confObj = discovery.get("confidence");
            if (confObj instanceof Number n) {
                rootConfidence = n.doubleValue();
            }
            rootVerifiedBy = getNestedString(discovery, "verified_by");
            rootEvidence = getNestedString(discovery, "evidence");
            rootVerifiedAt = getNestedString(discovery, "verified_at");
        }

        // --- Root-level trust.content_integrity (RFC-0018) ---
        String signingAlgorithm = null;
        String signingKeyId = null;
        String signatureFile = null;
        Object trustObj = data.get("trust");
        if (trustObj instanceof Map<?, ?> trust) {
            Object ciObj = trust.get("content_integrity");
            if (ciObj instanceof Map<?, ?> ci) {
                Object signingObj = ci.get("signing");
                if (signingObj instanceof Map<?, ?> signing) {
                    signingAlgorithm = getNestedString(signing, "algorithm");
                    signingKeyId = getNestedString(signing, "key_id");
                }
                signatureFile = getNestedString(ci, "signature_file");
            }
        }

        Object unitsObj = data.get("units");
        if (unitsObj instanceof List<?> unitsList) {
            for (Object u : unitsList) {
                if (!(u instanceof Map<?, ?> rawUnit)) continue;
                Map<String, Object> unit = (Map<String, Object>) rawUnit;

                // Unit ID as heading
                Object id = unit.get("id");
                if (id == null) continue;
                String unitId = id.toString();
                unitIds.add(unitId);

                // Triggers as searchable keywords
                List<String> triggerList = new ArrayList<>();
                Object triggers = unit.get("triggers");
                if (triggers instanceof List<?> tl) {
                    for (Object t : tl) {
                        String trigger = t.toString().toLowerCase().trim();
                        if (!trigger.isEmpty() && trigger.length() < 60) {
                            triggerList.add(trigger);
                            keywords.add(trigger);
                        }
                    }
                }

                // Audience values as keywords
                List<String> audienceList = new ArrayList<>();
                Object audience = unit.get("audience");
                if (audience instanceof List<?> al) {
                    for (Object a : al) {
                        String aud = a.toString().toLowerCase().trim();
                        audienceList.add(aud);
                        keywords.add(aud);
                    }
                }

                // Intent as a keyword-rich string (first 80 chars)
                String intentStr = null;
                Object intent = unit.get("intent");
                if (intent != null) {
                    intentStr = intent.toString();
                    String kwIntent = intentStr.length() > 80 ? intentStr.substring(0, 80) : intentStr;
                    keywords.add(kwIntent);
                }

                // Path
                Object pathObj = unit.get("path");
                String unitPath = pathObj != null ? pathObj.toString() : null;

                // Scope
                Object scopeObj = unit.get("scope");
                String scope = scopeObj != null ? scopeObj.toString() : null;

                // Hints (free-form map)
                Map<String, Object> hints = null;
                Object hintsObj = unit.get("hints");
                if (hintsObj instanceof Map<?, ?> hm) {
                    hints = new LinkedHashMap<>();
                    for (var e : hm.entrySet()) {
                        hints.put(e.getKey().toString(), e.getValue());
                    }
                }

                // --- Unit-level temporal (overrides root field-by-field) ---
                String unitValidFrom = rootValidFrom;
                String unitValidUntil = rootValidUntil;
                String unitRecordedAt = null;
                String unitSupersededBy = null;
                Object unitTemporalObj = unit.get("temporal");
                if (unitTemporalObj instanceof Map<?, ?> unitTemporal) {
                    String vf = getNestedString(unitTemporal, "valid_from");
                    if (vf != null) unitValidFrom = vf;
                    String vu = getNestedString(unitTemporal, "valid_until");
                    if (vu != null) unitValidUntil = vu;
                    unitRecordedAt = getNestedString(unitTemporal, "recorded_at");
                    unitSupersededBy = getNestedString(unitTemporal, "superseded_by");
                }

                // --- Unit-level content_hash (RFC-0019) ---
                String contentHashAlgorithm = null;
                String contentHashValue = null;
                Object chObj = unit.get("content_hash");
                if (chObj instanceof Map<?, ?> ch) {
                    contentHashAlgorithm = getNestedString(ch, "algorithm");
                    contentHashValue = getNestedString(ch, "value");
                }

                // --- Unit-level not_for (RFC-0015) ---
                List<String> unitNotFor = extractStringList(unit.get("not_for"));
                // Inherit root not_for if unit doesn't declare its own
                if (unitNotFor == null || unitNotFor.isEmpty()) {
                    unitNotFor = rootNotFor;
                }
                boolean unitNotForStrict = false;
                Object nfsObj = unit.get("not_for_strict");
                if (nfsObj instanceof Boolean b) {
                    unitNotForStrict = b;
                }

                // --- Unit-level content_structure (RFC-0016, inherit from root) ---
                String unitCsPrimary = rootContentStructurePrimary;
                String unitCsDensity = rootContentStructureDensity;
                Object unitCsObj = unit.get("content_structure");
                if (unitCsObj instanceof Map<?, ?> unitCs) {
                    String p = getNestedString(unitCs, "primary");
                    if (p != null) unitCsPrimary = p;
                    String d = getNestedString(unitCs, "density");
                    if (d != null) unitCsDensity = d;
                }

                // --- Unit-level discovery (RFC-0012, inherit from root) ---
                String unitVerificationStatus = rootVerificationStatus;
                double unitConfidence = rootConfidence;
                String unitVerifiedBy = rootVerifiedBy;
                String unitEvidence = rootEvidence;
                Object unitDiscObj = unit.get("discovery");
                if (unitDiscObj instanceof Map<?, ?> unitDisc) {
                    String vs = getNestedString(unitDisc, "verification_status");
                    if (vs != null) unitVerificationStatus = vs;
                    Object uc = unitDisc.get("confidence");
                    if (uc instanceof Number n) unitConfidence = n.doubleValue();
                    String vb = getNestedString(unitDisc, "verified_by");
                    if (vb != null) unitVerifiedBy = vb;
                    String ev = getNestedString(unitDisc, "evidence");
                    if (ev != null) unitEvidence = ev;
                }

                units.add(new KcpUnit(unitId, unitPath, intentStr, scope,
                        List.copyOf(audienceList), List.copyOf(triggerList),
                        hints != null ? Map.copyOf(hints) : Map.of(),
                        unitValidFrom, unitValidUntil, unitRecordedAt, unitSupersededBy,
                        contentHashAlgorithm, contentHashValue,
                        unitNotFor, unitNotForStrict,
                        unitCsPrimary, unitCsDensity,
                        unitVerificationStatus, unitConfidence, unitVerifiedBy, unitEvidence));
            }
        }

        Object relsObj = data.get("relationships");
        if (relsObj instanceof List<?> relList) {
            for (Object r : relList) {
                if (!(r instanceof Map<?, ?> rawRel)) continue;
                Map<String, Object> rel = (Map<String, Object>) rawRel;
                Object from = rel.get("from");
                Object to   = rel.get("to");
                if (from == null || to == null) continue;
                Object type = rel.get("type");
                relationships.add(new KcpRelationship(
                        from.toString(), to.toString(),
                        type != null ? type.toString() : null));
            }
        }

        // Deduplicate keywords, preserve insertion order
        List<String> dedupedKeywords = new ArrayList<>(new LinkedHashSet<>(keywords));

        String unitIdPreview = unitIds.size() <= 6
                ? String.join(", ", unitIds)
                : String.join(", ", unitIds.subList(0, 6)) + " … +" + (unitIds.size() - 6) + " more";

        String summary = String.format(
                "KCP manifest: %s (kcp v%s, %d units%s) — %s",
                project, kcpVersion, unitIds.size(),
                !relationships.isEmpty() ? ", " + relationships.size() + " relationships" : "",
                unitIdPreview);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("yamlType", "kcp-manifest");
        metrics.put("kcpVersion", kcpVersion);
        metrics.put("project", project);
        metrics.put("unitCount", unitIds.size());
        metrics.put("relationshipCount", relationships.size());
        // Full structured data for DB persistence (read by KcpRepository)
        metrics.put("kcpUnits", List.copyOf(units));
        metrics.put("kcpRelationships", List.copyOf(relationships));
        // Root-level metadata for manifest table (read by KcpRepository)
        // Only add non-null values — Map.copyOf() in AnalysisResult rejects nulls
        putIfNonNull(metrics, "signingAlgorithm", signingAlgorithm);
        putIfNonNull(metrics, "signingKeyId", signingKeyId);
        putIfNonNull(metrics, "signatureFile", signatureFile);
        putIfNonNull(metrics, "rootVerificationStatus", rootVerificationStatus);
        if (rootConfidence >= 0) metrics.put("rootConfidence", rootConfidence);
        putIfNonNull(metrics, "rootVerifiedBy", rootVerifiedBy);
        putIfNonNull(metrics, "rootVerifiedAt", rootVerifiedAt);
        putIfNonNull(metrics, "rootValidFrom", rootValidFrom);
        putIfNonNull(metrics, "rootValidUntil", rootValidUntil);
        putIfNonNull(metrics, "rootNotFor", rootNotFor);
        putIfNonNull(metrics, "rootContentStructurePrimary", rootContentStructurePrimary);
        putIfNonNull(metrics, "rootContentStructureDensity", rootContentStructureDensity);

        return new KcpExtraction(summary, kcpVersion, unitIds, dedupedKeywords, metrics,
                List.copyOf(units), List.copyOf(relationships));
    }

    /** Extract a list of strings from a YAML list value. Returns null if not a list. */
    private List<String> extractStringList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    /** Safe string extraction from a Map with non-String keys. */
    private String getNestedString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /** Only insert into metrics map if value is non-null (Map.copyOf rejects nulls). */
    private static void putIfNonNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String extractClaudeSkillInfo(Map<String, Object> data) {
        String name = getString(data, "name");
        String description = getString(data, "description");
        if (!description.isEmpty()) {
            return "Claude Code skill: " + name + " - " + truncateString(description, 100);
        }
        return "Claude Code skill: " + name;
    }

    private String extractDockerComposeInfo(Map<String, Object> data) {
        Object services = data.get("services");
        if (services instanceof Map<?, ?> map) {
            return "Docker Compose: " + map.size() + " services (" +
                    String.join(", ", map.keySet().stream().map(Object::toString).limit(5).toList()) + ")";
        }
        return "Docker Compose configuration";
    }

    private String extractGitHubActionsInfo(Map<String, Object> data) {
        String name = getString(data, "name");
        Object jobs = data.get("jobs");
        int jobCount = jobs instanceof Map<?, ?> map ? map.size() : 0;
        return "GitHub Actions: " + name + " (" + jobCount + " jobs)";
    }

    private String extractKubernetesInfo(Map<String, Object> data) {
        String kind = getString(data, "kind");
        String apiVersion = getString(data, "apiVersion");
        String name = "";
        Object metadata = data.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            Object n = meta.get("name");
            if (n != null) name = n.toString();
        }
        return "Kubernetes " + kind + " (" + apiVersion + ")" + (name.isEmpty() ? "" : ": " + name);
    }

    private String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : "";
    }

    private void addIfPresent(List<String> keywords, Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value != null) {
            String str = value.toString().toLowerCase().trim();
            if (!str.isEmpty() && str.length() < 50) {
                keywords.add(str);
            }
        }
    }

    private String truncate(String content) {
        return content.length() > CONTENT_PREVIEW_LIMIT
                ? content.substring(0, CONTENT_PREVIEW_LIMIT) : content;
    }

    private String truncateString(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
