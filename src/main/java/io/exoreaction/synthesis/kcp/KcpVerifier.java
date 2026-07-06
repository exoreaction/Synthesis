package io.exoreaction.synthesis.kcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic verification of KCP manifest declarations against evidence
 * (issue #356, Phase 3 of the v0.25 alignment epic).
 *
 * <p>KCP v0.16 defines the epistemic ordering {@code rumored < declared <
 * observed < verified}. A manifest's fields are declarations — this class
 * checks them against the filesystem, the file contents, and git history.
 * No model is involved: every check is a pure function, mirroring
 * kcp-agent's auditable-planning philosophy.
 *
 * <p>V-series checks (K-series signals from {@link KcpHealthChecks} are
 * folded into the result as well):
 * <ul>
 *   <li><b>V001</b> MISSING_PATH (HIGH) — unit path does not exist on disk</li>
 *   <li><b>V002</b> HASH_MISMATCH (HIGH) — declared content_hash does not match
 *       the recomputed sha256 of the file bytes</li>
 *   <li><b>V003</b> STALE_DECLARATION (MEDIUM) — the source file has git commits
 *       after the unit's declared {@code updated}/{@code validated} date</li>
 *   <li><b>V004</b> DEAD_TRIGGER (LOW) — a trigger matches neither a heading
 *       slug nor the content of the referenced markdown file</li>
 *   <li><b>V005</b> DANGLING_REFERENCE (HIGH) — a relationship, supersession,
 *       or depends_on target does not resolve to a unit in the manifest</li>
 *   <li><b>V006</b> TEMPORAL_INVALID (HIGH) — {@code valid_from > valid_until}</li>
 * </ul>
 */
public final class KcpVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Exporter-generated structure counters ("3-headings", "17-words", ...) are not content triggers. */
    private static final Pattern STRUCTURE_COUNTER_TRIGGER =
            Pattern.compile("\\d+-(headings|words|code-blocks|links)");

    private KcpVerifier() {
    }

    /** One verification finding against a manifest declaration. */
    public record Finding(String checkId, String severity, String unitId,
                          String manifestFile, String detail) {}

    /**
     * Result for one manifest: all findings plus a per-unit verdict.
     * Verdicts: {@code observed} (declarations hold), {@code stale}
     * (MEDIUM findings), {@code contradicted} (HIGH findings).
     */
    public record Result(String manifestFile,
                         List<Finding> findings,
                         Map<String, String> unitVerdicts) {

        public boolean hasContradictions() {
            return findings.stream().anyMatch(f -> "HIGH".equals(f.severity()));
        }
    }

    /**
     * Verifies one manifest's persisted rows against the filesystem and git.
     *
     * @param gitDates relative path → ISO committer date of the most recent commit
     *                 (empty map outside git repos — V003 is skipped then)
     * @param today    ISO date (YYYY-MM-DD) for temporal comparisons
     */
    public static Result verifyManifest(KcpRepository.KcpManifestRow manifest,
                                        List<KcpRepository.KcpUnitRow> units,
                                        List<KcpRelationship> relationships,
                                        Map<String, String> gitDates,
                                        Path workspaceRoot,
                                        String today) {
        List<Finding> findings = new ArrayList<>();
        String manifestFile = manifest.filePath();
        Path manifestDir = Path.of(manifestFile).getParent();
        if (manifestDir == null) manifestDir = workspaceRoot;

        Set<String> unitIds = new HashSet<>();
        for (KcpRepository.KcpUnitRow u : units) {
            unitIds.add(u.unitId());
        }

        for (KcpRepository.KcpUnitRow unit : units) {
            Path resolved = resolveUnitPath(unit.path(), manifestDir, workspaceRoot);

            // V001 — path exists
            if (unit.path() != null && resolved == null) {
                findings.add(new Finding("V001", "HIGH", unit.unitId(), manifestFile,
                        "path '" + unit.path() + "' does not exist on disk"));
            }

            // V002 — content hash
            if (resolved != null && unit.contentHashValue() != null) {
                if (unit.contentHashAlgorithm() != null
                        && !"sha256".equalsIgnoreCase(unit.contentHashAlgorithm())) {
                    findings.add(new Finding("V002", "LOW", unit.unitId(), manifestFile,
                            "unsupported content_hash algorithm '" + unit.contentHashAlgorithm()
                                    + "' — cannot verify"));
                } else {
                    String actual = sha256(resolved);
                    if (actual != null && !actual.equalsIgnoreCase(unit.contentHashValue())) {
                        findings.add(new Finding("V002", "HIGH", unit.unitId(), manifestFile,
                                "content_hash mismatch: declared " + shorten(unit.contentHashValue())
                                        + ", actual " + shorten(actual)
                                        + " — the file changed after the declaration"));
                    }
                }
            }

            // V003 — declaration staleness vs git
            String declaredDate = extractDeclaredDate(unit.extensionsJson());
            if (declaredDate != null && unit.path() != null) {
                String lastCommit = gitDates.get(unit.path().replace('\\', '/'));
                if (lastCommit != null && lastCommit.length() >= 10) {
                    String commitDate = lastCommit.substring(0, 10);
                    if (commitDate.compareTo(declaredDate) > 0) {
                        findings.add(new Finding("V003", "MEDIUM", unit.unitId(), manifestFile,
                                "source last committed " + commitDate + " but declaration says "
                                        + declaredDate + " — declaration is behind the source"));
                    }
                }
            }

            // V004 — dead triggers (markdown only; structure counters excluded)
            if (resolved != null && unit.path() != null
                    && unit.path().toLowerCase().endsWith(".md")
                    && unit.triggersJson() != null) {
                String content = readLowercase(resolved);
                if (content != null) {
                    Set<String> headingSlugs = headingSlugs(content);
                    for (String trigger : parseStringArray(unit.triggersJson())) {
                        String t = trigger.toLowerCase();
                        if (STRUCTURE_COUNTER_TRIGGER.matcher(t).matches()) continue;
                        boolean inHeadings = headingSlugs.contains(t);
                        boolean inContent = content.contains(t.replace('-', ' ')) || content.contains(t);
                        if (!inHeadings && !inContent) {
                            findings.add(new Finding("V004", "LOW", unit.unitId(), manifestFile,
                                    "trigger '" + trigger + "' matches neither a heading nor the content"));
                        }
                    }
                }
            }

            // V005 — supersession + depends_on targets resolve
            if (unit.supersededBy() != null && !unitIds.contains(unit.supersededBy())) {
                findings.add(new Finding("V005", "HIGH", unit.unitId(), manifestFile,
                        "superseded_by target '" + unit.supersededBy() + "' is not a unit in this manifest"));
            }
            for (String dep : extractDependsOn(unit.extensionsJson())) {
                if (!unitIds.contains(dep)) {
                    findings.add(new Finding("V005", "HIGH", unit.unitId(), manifestFile,
                            "depends_on target '" + dep + "' is not a unit in this manifest"));
                }
            }

            // V006 — temporal sanity
            if (unit.validFrom() != null && unit.validUntil() != null
                    && unit.validFrom().compareTo(unit.validUntil()) > 0) {
                findings.add(new Finding("V006", "HIGH", unit.unitId(), manifestFile,
                        "valid_from " + unit.validFrom() + " is after valid_until " + unit.validUntil()));
            }
        }

        // V005 — relationship endpoints resolve
        for (KcpRelationship rel : relationships) {
            if (!unitIds.contains(rel.fromUnit())) {
                findings.add(new Finding("V005", "HIGH", rel.fromUnit(), manifestFile,
                        "relationship source '" + rel.fromUnit() + "' is not a unit in this manifest"));
            }
            if (!unitIds.contains(rel.toUnit())) {
                findings.add(new Finding("V005", "HIGH", rel.toUnit(), manifestFile,
                        "relationship target '" + rel.toUnit() + "' is not a unit in this manifest"));
            }
        }

        // Fold in K-series health signals (K001/K002/K004)
        for (KcpHealthChecks.Signal signal : KcpHealthChecks.checkManifest(
                manifest, units, relationships, today)) {
            findings.add(new Finding(signal.code(), signal.severity(), null,
                    signal.manifestFile(), signal.detail()));
        }

        // Per-unit verdicts
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (KcpRepository.KcpUnitRow unit : units) {
            String worst = "observed";
            for (Finding f : findings) {
                if (!unit.unitId().equals(f.unitId())) continue;
                if ("HIGH".equals(f.severity())) { worst = "contradicted"; break; }
                if ("MEDIUM".equals(f.severity())) worst = "stale";
            }
            verdicts.put(unit.unitId(), worst);
        }

        return new Result(manifestFile, List.copyOf(findings), verdicts);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Resolves a unit path against the manifest directory, then the workspace root. */
    static Path resolveUnitPath(String unitPath, Path manifestDir, Path workspaceRoot) {
        if (unitPath == null) return null;
        Path viaManifest = manifestDir.resolve(unitPath);
        if (Files.exists(viaManifest)) return viaManifest;
        Path viaRoot = workspaceRoot.resolve(unitPath);
        if (Files.exists(viaRoot)) return viaRoot;
        return null;
    }

    static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(Files.readAllBytes(file))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Declared freshness date: {@code updated} preferred, else {@code validated} (extensions JSON). */
    static String extractDeclaredDate(String extensionsJson) {
        if (extensionsJson == null) return null;
        try {
            JsonNode root = JSON.readTree(extensionsJson);
            for (String key : new String[]{"updated", "validated"}) {
                JsonNode n = root.get(key);
                if (n != null && n.asText().length() >= 10) {
                    return n.asText().substring(0, 10);
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    /** depends_on unit ids from the unit's extensions JSON (plain string entries only). */
    static List<String> extractDependsOn(String extensionsJson) {
        if (extensionsJson == null) return List.of();
        try {
            JsonNode deps = JSON.readTree(extensionsJson).get("depends_on");
            if (deps == null || !deps.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode d : deps) {
                if (d.isTextual()) result.add(d.asText());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String readLowercase(Path file) {
        try {
            return Files.readString(file).toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    /** Slugified markdown heading lines ("## API Reference" → "api-reference"). */
    private static Set<String> headingSlugs(String lowercaseContent) {
        Set<String> slugs = new HashSet<>();
        for (String line : lowercaseContent.split("\n")) {
            if (!line.startsWith("#")) continue;
            String slug = line.replaceAll("^#+\\s*", "")
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("-{2,}", "-")
                    .replaceAll("(^-+|-+$)", "");
            if (!slug.isBlank()) slugs.add(slug);
        }
        return slugs;
    }

    /** Parses a JSON string array (["a","b"]) leniently. */
    static List<String> parseStringArray(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode n : node) {
                if (n.isTextual() && !n.asText().isBlank()) result.add(n.asText());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String shorten(String hash) {
        return hash != null && hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }
}
