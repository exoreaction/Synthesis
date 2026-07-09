package io.exoreaction.synthesis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answer grounding — the plan's fail-closed discipline extended to the output.
 * Each claim in an AI answer must be attributed to a loaded, hash-pinned unit
 * or surfaced as an explicit gap (#371 item 2).
 *
 * <p>Mirrors kcp-agent's grounding architecture:
 * <ol>
 *   <li>Split answer into sentence-level claims (deterministic)</li>
 *   <li>Verify each claim against loaded units (separate LLM call)</li>
 *   <li>Adjudicate: confirm cited unit was actually loaded (fail-closed)</li>
 * </ol>
 *
 * <p>"Attribution is a proposal; grounding is adjudicated."
 */
public final class Grounder {

    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max tokens for verifier response — small since it's structured JSON. */
    private static final int VERIFIER_MAX_TOKENS = 2048;

    /** Pattern to extract JSON array from verifier response (may have preamble). */
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[\\s*\\{.*}\\s*]", Pattern.DOTALL);

    private Grounder() {
    }

    // -----------------------------------------------------------------------
    // Data structures
    // -----------------------------------------------------------------------

    /** A loaded file with content and its sha256 hash. */
    public record FileUnit(String path, String content, String sha256) {
        /** Create a FileUnit, computing sha256 from content. */
        public static FileUnit of(String path, String content) {
            return new FileUnit(path, content, computeSha256(content));
        }
    }

    /** Verdict for a single claim. */
    public record ClaimVerdict(String claim, boolean grounded,
                                String unitId, String sha256, String reason) {}

    /** Full grounding result for an answer. */
    public record GroundedAnswer(String status, List<ClaimVerdict> claims,
                                  List<ClaimVerdict> grounded, List<ClaimVerdict> gaps) {}

    // -----------------------------------------------------------------------
    // Claim splitting
    // -----------------------------------------------------------------------

    /**
     * Split an answer into sentence-level claims. Deterministic: splits on
     * sentence-ending punctuation followed by whitespace, or on newlines.
     */
    public static List<String> splitClaims(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        String[] parts = SENTENCE_BOUNDARY.split(answer);
        List<String> claims = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                claims.add(trimmed);
            }
        }
        return claims;
    }

    // -----------------------------------------------------------------------
    // Fail-closed adjudication
    // -----------------------------------------------------------------------

    /**
     * Adjudicate a verifier's verdict for one claim. The verifier proposes
     * which unit supports the claim; this method confirms the cited unit
     * was actually loaded (fail-closed).
     *
     * @param claim      the claim text
     * @param citedUnit  unit ID the verifier says supports the claim (may be null)
     * @param note       verifier's note when unsupported (may be null)
     * @param loadedUnits map of path→FileUnit for all loaded units
     * @return adjudicated verdict
     */
    public static ClaimVerdict adjudicate(String claim, String citedUnit,
                                           String note,
                                           Map<String, FileUnit> loadedUnits) {
        if (citedUnit == null) {
            // Verifier found no support
            return new ClaimVerdict(claim, false, null, null,
                    note != null ? note : "unsupported: verifier found no match");
        }

        FileUnit unit = loadedUnits.get(citedUnit);
        if (unit == null) {
            // Fail-closed: verifier cited a unit that was never loaded
            return new ClaimVerdict(claim, false, null, null,
                    "verifier cited unit '" + citedUnit + "' that was not loaded — fail-closed");
        }

        // Grounded: unit was loaded, pin to its sha256
        return new ClaimVerdict(claim, true, citedUnit, unit.sha256(), null);
    }

    // -----------------------------------------------------------------------
    // Answer assembly
    // -----------------------------------------------------------------------

    /**
     * Assemble a {@link GroundedAnswer} from individual claim verdicts.
     */
    public static GroundedAnswer assembleAnswer(List<ClaimVerdict> verdicts) {
        List<ClaimVerdict> grounded = new ArrayList<>();
        List<ClaimVerdict> gaps = new ArrayList<>();

        for (ClaimVerdict v : verdicts) {
            if (v.grounded()) {
                grounded.add(v);
            } else {
                gaps.add(v);
            }
        }

        String status = gaps.isEmpty() ? "grounded" : "partial-unsupported";
        return new GroundedAnswer(status, List.copyOf(verdicts),
                List.copyOf(grounded), List.copyOf(gaps));
    }

    // -----------------------------------------------------------------------
    // LLM-powered grounding (full flow)
    // -----------------------------------------------------------------------

    /**
     * Ground an AI answer against loaded file units. Full flow:
     * split claims → verify via LLM → adjudicate (fail-closed) → assemble.
     *
     * @param answer      the AI-generated answer to ground
     * @param loadedUnits map of path→FileUnit for all loaded context files
     * @param client      AI client for the verifier call
     * @return grounding result with per-claim verdicts
     */
    public static GroundedAnswer groundAnswer(String answer,
                                                Map<String, FileUnit> loadedUnits,
                                                AiClient client) {
        List<String> claims = splitClaims(answer);
        if (claims.isEmpty()) {
            return assembleAnswer(List.of());
        }

        // Build verifier prompt and call LLM
        String prompt = buildVerifierPrompt(claims, loadedUnits);
        String verifierResponse;
        try {
            verifierResponse = client.generate(prompt, VERIFIER_MAX_TOKENS);
        } catch (Exception e) {
            // LLM call failed — fail-closed: all claims become gaps
            List<ClaimVerdict> verdicts = new ArrayList<>();
            for (String claim : claims) {
                verdicts.add(new ClaimVerdict(claim, false, null, null,
                        "verifier unavailable: " + e.getMessage()));
            }
            return assembleAnswer(verdicts);
        }

        // Parse verifier response and adjudicate each claim
        List<ClaimVerdict> verdicts = adjudicateVerifierResponse(
                claims, verifierResponse, loadedUnits);
        return assembleAnswer(verdicts);
    }

    /**
     * Build the verifier prompt: lists claims and unit contents,
     * asks the LLM to return JSON mapping each claim to its supporting unit.
     */
    static String buildVerifierPrompt(List<String> claims,
                                        Map<String, FileUnit> loadedUnits) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a grounding verifier. For each claim, determine which loaded \
                knowledge unit (if any) supports it. Return ONLY a JSON array — no \
                prose, no markdown fences.

                Each element must have:
                - "claim": the exact claim text
                - "supportedBy": the unit path that supports this claim, or null
                - "note": brief reason when unsupported, or null when supported

                Claims:
                """);
        for (int i = 0; i < claims.size(); i++) {
            sb.append(i + 1).append(". \"").append(claims.get(i)).append("\"\n");
        }
        sb.append("\nLoaded Units:\n");
        for (Map.Entry<String, FileUnit> e : loadedUnits.entrySet()) {
            sb.append("--- ").append(e.getKey()).append(" ---\n");
            String content = e.getValue().content();
            // Truncate large units to keep prompt reasonable
            if (content.length() > 2000) {
                sb.append(content, 0, 2000).append("\n[truncated]\n");
            } else {
                sb.append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Parse verifier LLM response and adjudicate each claim.
     * Tolerates preamble text before the JSON array.
     */
    static List<ClaimVerdict> adjudicateVerifierResponse(
            List<String> claims, String verifierResponse,
            Map<String, FileUnit> loadedUnits) {
        List<ClaimVerdict> verdicts = new ArrayList<>();

        try {
            // Extract JSON array from response (may have preamble/fences)
            String json = extractJsonArray(verifierResponse);
            JsonNode array = MAPPER.readTree(json);

            if (!array.isArray()) {
                // Not an array — fail-closed on all claims
                return failAllClaims(claims, "verifier returned non-array JSON");
            }

            // Build lookup from claim text → verifier verdict
            Map<String, JsonNode> verdictMap = new java.util.LinkedHashMap<>();
            for (JsonNode node : array) {
                String claimText = node.path("claim").asText(null);
                if (claimText != null) {
                    verdictMap.put(claimText, node);
                }
            }

            // Adjudicate each original claim
            for (String claim : claims) {
                JsonNode v = verdictMap.get(claim);
                if (v == null) {
                    // Verifier didn't return a verdict for this claim — gap
                    verdicts.add(new ClaimVerdict(claim, false, null, null,
                            "verifier omitted this claim — fail-closed"));
                } else {
                    String citedUnit = v.path("supportedBy").isNull()
                            ? null : v.path("supportedBy").asText(null);
                    String note = v.path("note").isNull()
                            ? null : v.path("note").asText(null);
                    verdicts.add(adjudicate(claim, citedUnit, note, loadedUnits));
                }
            }
        } catch (Exception e) {
            // JSON parse failed — fail-closed on all claims
            return failAllClaims(claims, "verifier response not parseable: " + e.getMessage());
        }

        return verdicts;
    }

    /**
     * Extract a JSON array from text that may contain preamble or markdown fences.
     */
    static String extractJsonArray(String text) {
        if (text == null) return "[]";
        String trimmed = text.trim();

        // Strip markdown code fences if present
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }

        // If it starts with '[', try direct parse
        if (trimmed.startsWith("[")) {
            return trimmed;
        }

        // Search for JSON array in the text
        Matcher m = JSON_ARRAY.matcher(trimmed);
        if (m.find()) {
            return m.group();
        }

        return trimmed; // let caller handle parse failure
    }

    private static List<ClaimVerdict> failAllClaims(List<String> claims, String reason) {
        List<ClaimVerdict> verdicts = new ArrayList<>();
        for (String claim : claims) {
            verdicts.add(new ClaimVerdict(claim, false, null, null, reason));
        }
        return verdicts;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String computeSha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
