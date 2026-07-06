package io.exoreaction.synthesis.kcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, auditable read planner over persisted KCP units (issue #359,
 * Phase 6 of the v0.25 alignment epic).
 *
 * <p>Implements RFC-0007 query scoring natively — no model, no Node dependency,
 * operating on the V17/V23 tables Synthesis already persists. Given a task, it
 * returns an <em>ordered read plan</em>: which units to load, why each matched,
 * an estimated token cost, and explicit skip reasons — the same shape kcp-agent's
 * planner produces, so a Synthesis MCP surface can answer "what should I read?"
 * instead of returning flat search hits.
 *
 * <p>Scoring per matched query term: trigger 5, intent 3, id/path 1. Expired and
 * superseded units are excluded with a recorded skip reason; when a token budget
 * is supplied, units are admitted greedily by score until the budget is spent and
 * the remainder are skipped as {@code over_budget}.
 */
public final class KcpPlanner {

    private static final int TRIGGER_WEIGHT = 5;
    private static final int INTENT_WEIGHT = 3;
    private static final int ID_PATH_WEIGHT = 1;

    /** Rough token estimate: ~4 bytes per token. */
    private static final int BYTES_PER_TOKEN = 4;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    private KcpPlanner() {
    }

    /** A unit admitted to the plan. */
    public record Planned(String unitId, String path, String manifestFile, int score,
                          String matchReason, int tokenEstimate, String intent) {}

    /** A unit considered but excluded, with the reason. */
    public record Skipped(String unitId, String path, String reason) {}

    /** The full plan: ordered units to read, skipped units, and the total token estimate. */
    public record Plan(String task, List<Planned> units, List<Skipped> skipped,
                       int totalTokenEstimate) {}

    /** A unit to be scored, paired with the manifest that declares it and its workspace root. */
    public record Candidate(KcpRepository.KcpUnitRow unit, String manifestFile, Path workspaceRoot) {}

    /**
     * Plans a read order for {@code task} over {@code candidates}.
     *
     * @param today      ISO date (YYYY-MM-DD) for expiry checks
     * @param tokenBudget max total tokens to admit, or &lt;= 0 for unlimited
     */
    public static Plan plan(String task, List<Candidate> candidates, String today, int tokenBudget) {
        Set<String> terms = tokenize(task);
        List<Scored> scored = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (Candidate c : candidates) {
            KcpRepository.KcpUnitRow u = c.unit();

            // Temporal exclusions first — never plan stale knowledge
            if (u.validUntil() != null && u.validUntil().compareTo(today) < 0) {
                skipped.add(new Skipped(u.unitId(), u.path(), "expired (valid_until " + u.validUntil() + ")"));
                continue;
            }
            if (u.supersededBy() != null && !u.supersededBy().isBlank()) {
                skipped.add(new Skipped(u.unitId(), u.path(), "superseded by " + u.supersededBy()));
                continue;
            }

            int triggerHits = countHits(terms, tokenize(u.triggersJson()));
            int intentHits = countHits(terms, tokenize(u.intent()));
            int idPathHits = countHits(terms, tokenize(
                    (u.unitId() == null ? "" : u.unitId()) + " " + (u.path() == null ? "" : u.path())));
            int score = triggerHits * TRIGGER_WEIGHT + intentHits * INTENT_WEIGHT
                    + idPathHits * ID_PATH_WEIGHT;

            if (score == 0) {
                skipped.add(new Skipped(u.unitId(), u.path(), "no query-term match"));
                continue;
            }
            scored.add(new Scored(c, score, matchReason(triggerHits, intentHits, idPathHits),
                    estimateTokens(c)));
        }

        // Highest score first; stable tie-break by unit id for determinism
        scored.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score)
                : nullSafe(a.candidate.unit().unitId()).compareTo(nullSafe(b.candidate.unit().unitId())));

        List<Planned> planned = new ArrayList<>();
        int spent = 0;
        for (Scored s : scored) {
            if (tokenBudget > 0 && spent + s.tokenEstimate > tokenBudget && !planned.isEmpty()) {
                skipped.add(new Skipped(s.candidate.unit().unitId(), s.candidate.unit().path(),
                        "over_budget (would exceed " + tokenBudget + " tokens)"));
                continue;
            }
            spent += s.tokenEstimate;
            planned.add(new Planned(s.candidate.unit().unitId(), s.candidate.unit().path(),
                    s.candidate.manifestFile(), s.score, s.matchReason, s.tokenEstimate,
                    s.candidate.unit().intent()));
        }

        return new Plan(task, planned, skipped, spent);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record Scored(Candidate candidate, int score, String matchReason, int tokenEstimate) {}

    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) return tokens;
        var m = TOKEN.matcher(text.toLowerCase());
        while (m.find()) {
            String t = m.group();
            if (t.length() > 1) tokens.add(t);   // drop single chars
        }
        return tokens;
    }

    private static int countHits(Set<String> queryTerms, Set<String> fieldTerms) {
        int hits = 0;
        for (String t : queryTerms) {
            if (fieldTerms.contains(t)) hits++;
        }
        return hits;
    }

    private static String matchReason(int triggerHits, int intentHits, int idPathHits) {
        List<String> parts = new ArrayList<>();
        if (triggerHits > 0) parts.add(triggerHits + " trigger");
        if (intentHits > 0) parts.add(intentHits + " intent");
        if (idPathHits > 0) parts.add(idPathHits + " id/path");
        return String.join(", ", parts) + " match" + (triggerHits + intentHits + idPathHits > 1 ? "es" : "");
    }

    /** Token estimate from the referenced file's size; 0 when unresolvable (e.g. dir units). */
    static int estimateTokens(Candidate c) {
        String path = c.unit().path();
        if (path == null || c.workspaceRoot() == null) return 0;
        Path manifestDir = Path.of(c.manifestFile()).getParent();
        for (Path base : new Path[]{manifestDir, c.workspaceRoot()}) {
            if (base == null) continue;
            Path resolved = base.resolve(path);
            try {
                if (Files.isRegularFile(resolved)) {
                    return (int) Math.max(1, Files.size(resolved) / BYTES_PER_TOKEN);
                }
            } catch (Exception ignored) {
                // try next base
            }
        }
        return 0;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
