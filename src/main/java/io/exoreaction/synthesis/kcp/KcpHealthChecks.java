package io.exoreaction.synthesis.kcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K-series health signals for KCP manifests (issue #355).
 *
 * <ul>
 *   <li><b>K001</b> — an expired unit ({@code valid_until} in the past) is still
 *       referenced by a relationship</li>
 *   <li><b>K002</b> — supersession cycle ({@code superseded_by} chains loop; a
 *       manifest error per spec §4.22)</li>
 *   <li><b>K003</b> — the manifest file is gitignored and never reaches the team
 *       (delegates to {@link KcpManifestChecks}, issue #309)</li>
 *   <li><b>K004</b> — root {@code freshness_policy} violated: a unit's
 *       {@code recorded_at} is older than {@code max_age_days}; severity maps
 *       from {@code on_stale} (warn→LOW, degrade→MEDIUM, block→HIGH)</li>
 * </ul>
 *
 * <p>All checks are pure functions over persisted rows so they are trivially
 * testable; only K003 touches git.
 */
public final class KcpHealthChecks {

    private static final ObjectMapper JSON = new ObjectMapper();

    private KcpHealthChecks() {
    }

    /** One detected health signal. */
    public record Signal(String code, String severity, String manifestFile, String detail) {}

    /**
     * Runs K001, K002, and K004 for one manifest's persisted rows.
     *
     * @param today ISO date (YYYY-MM-DD) used for temporal comparisons
     */
    public static List<Signal> checkManifest(KcpRepository.KcpManifestRow manifest,
                                             List<KcpRepository.KcpUnitRow> units,
                                             List<KcpRelationship> relationships,
                                             String today) {
        List<Signal> signals = new ArrayList<>();
        String file = manifest.filePath();

        // K001 — expired units still referenced
        Set<String> expired = new HashSet<>();
        for (KcpRepository.KcpUnitRow u : units) {
            if (u.validUntil() != null && u.validUntil().compareTo(today) < 0) {
                expired.add(u.unitId());
            }
        }
        for (KcpRelationship rel : relationships) {
            if (expired.contains(rel.toUnit())) {
                signals.add(new Signal("K001", "MEDIUM", file,
                        "expired unit '" + rel.toUnit() + "' is still referenced by '"
                                + rel.fromUnit() + "'"));
            }
        }

        // K002 — supersession cycles (walk superseded_by chains)
        Map<String, String> successor = new HashMap<>();
        for (KcpRepository.KcpUnitRow u : units) {
            if (u.supersededBy() != null && !u.supersededBy().isBlank()) {
                successor.put(u.unitId(), u.supersededBy());
            }
        }
        Set<String> reported = new HashSet<>();
        for (String start : successor.keySet()) {
            Set<String> seen = new HashSet<>();
            String current = start;
            while (current != null && seen.add(current)) {
                current = successor.get(current);
            }
            if (current == null) continue;
            // `current` re-entered `seen` → it lies on a cycle. Collect the full
            // cycle membership so each cycle is reported exactly once.
            List<String> members = new ArrayList<>();
            String walker = current;
            do {
                members.add(walker);
                walker = successor.get(walker);
            } while (walker != null && !walker.equals(current)
                    && members.size() <= successor.size());
            if (members.stream().anyMatch(reported::contains)) continue;
            reported.addAll(members);
            signals.add(new Signal("K002", "HIGH", file,
                    "supersession cycle: " + String.join(" → ", members) + " → " + current
                            + " (manifest error per spec §4.22)"));
        }

        // K004 — freshness_policy violations (root extensions block)
        FreshnessPolicy policy = parseFreshnessPolicy(manifest.rootExtensionsJson());
        if (policy != null) {
            LocalDate todayDate = LocalDate.parse(today);
            for (KcpRepository.KcpUnitRow u : units) {
                String recordedAt = u.recordedAt();
                if (recordedAt == null || recordedAt.length() < 10) continue;
                LocalDate recorded;
                try {
                    recorded = LocalDate.parse(recordedAt.substring(0, 10));
                } catch (Exception e) {
                    continue;
                }
                long age = ChronoUnit.DAYS.between(recorded, todayDate);
                if (age > policy.maxAgeDays()) {
                    signals.add(new Signal("K004", policy.severity(), file,
                            "unit '" + u.unitId() + "' recorded " + age + " days ago exceeds "
                                    + "freshness_policy.max_age_days=" + policy.maxAgeDays()
                                    + " (on_stale: " + policy.onStale() + ")"));
                }
            }
        }

        return signals;
    }

    /**
     * K003 — manifest gitignored. Separated from {@link #checkManifest} because it
     * shells out to git.
     */
    public static List<Signal> checkGitignored(Path workspaceRoot, String manifestAbsolutePath) {
        try {
            Path manifest = Path.of(manifestAbsolutePath);
            String relative = workspaceRoot.relativize(manifest).toString().replace('\\', '/');
            if (KcpManifestChecks.isManifestGitIgnored(workspaceRoot, relative)) {
                return List.of(new Signal("K003", "HIGH", manifestAbsolutePath,
                        "manifest is gitignored and never reaches the team — "
                                + "run: git add -f " + relative));
            }
        } catch (Exception e) {
            // relativize can fail for paths outside the workspace — no signal
        }
        return List.of();
    }

    record FreshnessPolicy(int maxAgeDays, String onStale) {
        String severity() {
            return switch (onStale == null ? "warn" : onStale) {
                case "block" -> "HIGH";
                case "degrade" -> "MEDIUM";
                default -> "LOW";
            };
        }
    }

    /** Parses {@code freshness_policy} out of the manifest's root extensions JSON. */
    static FreshnessPolicy parseFreshnessPolicy(String rootExtensionsJson) {
        if (rootExtensionsJson == null || rootExtensionsJson.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(rootExtensionsJson);
            JsonNode policy = root.get("freshness_policy");
            if (policy == null || !policy.has("max_age_days")) return null;
            int maxAge = policy.get("max_age_days").asInt(-1);
            if (maxAge < 0) return null;
            JsonNode onStale = policy.get("on_stale");
            return new FreshnessPolicy(maxAge, onStale != null ? onStale.asText() : null);
        } catch (Exception e) {
            return null;
        }
    }
}
