package io.exoreaction.synthesis.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.exoreaction.synthesis.ai.Grounder;
import io.exoreaction.synthesis.kcp.KcpVerifier;
import io.exoreaction.synthesis.util.FileUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic re-verification of grounded-answer claim pins against the
 * current filesystem — the recall-side counterpart of {@link Grounder}'s
 * grounding-time pinning, in the same spirit as {@link KcpVerifier}'s
 * V002/V003 evidence checks. No model is involved: every verdict is a pure
 * function of the stored artifact and the files on disk.
 *
 * <p>A grounded-answer artifact pins each supported claim to
 * {@code (unitId, sha256)} — the unit's workspace-relative path and the hash
 * of its content at grounding time. At recall, each pin is re-checked:
 * <ul>
 *   <li><b>fresh</b> — the current content still matches the pin</li>
 *   <li><b>stale</b> — the file changed since grounding</li>
 *   <li><b>unit-missing</b> — the file no longer exists (or escapes the
 *       workspace root, or cannot be read)</li>
 * </ul>
 *
 * <p>Two pin provenances are accepted as fresh: the hash of the text preview
 * window {@link Grounder#PIN_PREVIEW_BYTES} (how {@code ask --ground} pins
 * units) and the hash of the full file bytes (KcpVerifier-style pins from
 * other producers).
 *
 * <p>Supported artifact shapes: a serialized {@link Grounder.GroundedAnswer}
 * ({@code claims[]} with {@code grounded}/{@code unitId}/{@code sha256}), the
 * {@code grounding} node of an {@code ask} response ({@code grounded[]} with
 * {@code claim}/{@code unitId}/{@code sha256}), or an ask response embedding
 * that node. Claims without both pins (gaps) are skipped.
 */
public final class GroundingFreshness {

    /** Per-claim verdict: current content matches the pin. */
    public static final String FRESH = "fresh";
    /** Per-claim verdict: the file changed since grounding. */
    public static final String STALE = "stale";
    /** Per-claim verdict: the pinned unit no longer resolves to a readable file. */
    public static final String UNIT_MISSING = "unit-missing";

    /** Report status: every pinned claim is fresh. */
    public static final String STATUS_FRESH = "fresh";
    /** Report status: at least one claim is stale or unit-missing. */
    public static final String STATUS_STALE = "stale";
    /** Report status: the artifact carries no hash-pinned claims to check. */
    public static final String STATUS_NO_PINNED_CLAIMS = "no-pinned-claims";
    /** Report status: no workspace root to resolve unit paths against. */
    public static final String STATUS_UNVERIFIABLE = "unverifiable";

    private GroundingFreshness() {
    }

    /** Freshness verdict for a single hash-pinned claim. */
    public record ClaimFreshness(String claim, String unitId,
                                 String pinnedSha256, String verdict) {}

    /** Freshness report for one grounded-answer artifact. */
    public record Report(String status, int freshCount, int staleCount,
                         int missingCount, List<ClaimFreshness> claims) {}

    /**
     * Re-checks every hash-pinned claim in a grounded-answer artifact against
     * the current content of its backing file.
     *
     * @param artifact      the stored artifact JSON (see class javadoc for shapes)
     * @param workspaceRoot root to resolve workspace-relative unit paths against
     * @return per-claim verdicts plus an aggregate status
     */
    public static Report check(JsonNode artifact, Path workspaceRoot) {
        List<JsonNode> pinned = pinnedClaims(artifact);
        if (pinned.isEmpty()) {
            return new Report(STATUS_NO_PINNED_CLAIMS, 0, 0, 0, List.of());
        }
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return new Report(STATUS_UNVERIFIABLE, 0, 0, 0, List.of());
        }

        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<ClaimFreshness> verdicts = new ArrayList<>();
        int fresh = 0, stale = 0, missing = 0;

        for (JsonNode node : pinned) {
            String claim = node.path("claim").asText("");
            String unitId = node.get("unitId").asText();
            String pin = node.get("sha256").asText();

            String verdict = verdictFor(root, unitId, pin);
            switch (verdict) {
                case FRESH -> fresh++;
                case STALE -> stale++;
                default -> missing++;
            }
            verdicts.add(new ClaimFreshness(claim, unitId, pin, verdict));
        }

        String status = (stale == 0 && missing == 0) ? STATUS_FRESH : STATUS_STALE;
        return new Report(status, fresh, stale, missing, List.copyOf(verdicts));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Verdict for one pin against the file currently at {@code root/unitId}. */
    private static String verdictFor(Path root, String unitId, String pin) {
        Path resolved = root.resolve(unitId).normalize();
        // Claims only pin units inside the grounded workspace — a unitId that
        // escapes the root is never resolved, however it got into the artifact.
        if (!resolved.startsWith(root)) {
            return UNIT_MISSING;
        }
        if (!Files.isRegularFile(resolved)) {
            return UNIT_MISSING;
        }

        String previewHash = null;
        try {
            String preview = FileUtils.readPreview(resolved, Grounder.PIN_PREVIEW_BYTES);
            previewHash = Grounder.FileUnit.of(unitId, preview).sha256();
        } catch (Exception e) {
            // fall through — full-byte hash may still verify
        }
        String fullHash = KcpVerifier.sha256(resolved);

        if (previewHash == null && fullHash == null) {
            // Exists but unreadable — the pinned evidence is not accessible
            return UNIT_MISSING;
        }
        if (pin.equalsIgnoreCase(previewHash) || pin.equalsIgnoreCase(fullHash)) {
            return FRESH;
        }
        return STALE;
    }

    /**
     * Collects claim nodes carrying both {@code unitId} and {@code sha256}
     * pins, from any of the supported artifact shapes.
     */
    private static List<JsonNode> pinnedClaims(JsonNode artifact) {
        if (artifact == null || !artifact.isObject()) return List.of();

        JsonNode source = artifact.has("grounding") && artifact.get("grounding").isObject()
                ? artifact.get("grounding")
                : artifact;

        JsonNode claims = source.get("claims");
        if (claims == null || !claims.isArray() || claims.isEmpty()) {
            claims = source.get("grounded");
        }
        if (claims == null || !claims.isArray()) return List.of();

        List<JsonNode> pinned = new ArrayList<>();
        for (JsonNode node : claims) {
            if (isNonBlankText(node.get("unitId")) && isNonBlankText(node.get("sha256"))) {
                pinned.add(node);
            }
        }
        return pinned;
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }
}
