package io.exoreaction.synthesis.kcp;

import java.util.List;
import java.util.Map;

/**
 * Represents a single unit entry within a KCP manifest ({@code knowledge.yaml}).
 *
 * <p>Units are the primary addressable entries in the Knowledge Context Protocol.
 * Each unit maps an intent question to a specific document path, with optional
 * scope, audience, trigger keywords, and hints for AI agents.
 *
 * <p>Extended in v0.21 with temporal validity, content integrity, negative space,
 * content structure, and discovery provenance fields.
 */
public record KcpUnit(
        /** Unit identifier, unique within the manifest. */
        String unitId,

        /** File path this unit refers to (relative to repo root, may be null). */
        String path,

        /** Intent question this unit answers (e.g. "What endpoints does the API expose?"). */
        String intent,

        /** Scope of the unit: global, module, focused, comprehensive, etc. */
        String scope,

        /** Intended audience values, e.g. ["developer", "agent"]. */
        List<String> audience,

        /** Trigger keywords for AI routing, e.g. ["api", "rest", "endpoints"]. */
        List<String> triggers,

        /** Arbitrary hints map, e.g. {summary_of: "agents"}. May be null or empty. */
        Map<String, Object> hints,

        // --- Temporal validity (KCP v0.19+, RFC-0020/RFC-0010) ---

        /** ISO 8601 date: when this knowledge became valid in the real world. May be null. */
        String validFrom,

        /** ISO 8601 date: when this knowledge ceases to be valid. May be null (no expiry). */
        String validUntil,

        /** ISO 8601 date: when this version was recorded/authored. May be null. */
        String recordedAt,

        /** ID of the unit that supersedes this one. May be null. */
        String supersededBy,

        // --- Content integrity (KCP v0.18+, RFC-0019) ---

        /** Hash algorithm for content verification, e.g. "sha256". May be null. */
        String contentHashAlgorithm,

        /** Hex digest of the unit's content file. May be null. */
        String contentHashValue,

        // --- Negative space (KCP v0.17+, RFC-0015) ---

        /** What this unit does NOT address. May be null or empty. */
        List<String> notFor,

        /** If true, queries matching not_for MUST be excluded (not just demoted). */
        boolean notForStrict,

        // --- Content structure (KCP v0.17+, RFC-0016) ---

        /** Dominant modality: prose, table, code, list, diagram, reference, mixed. May be null. */
        String contentStructurePrimary,

        /** Content density: sparse, normal, dense. May be null. */
        String contentStructureDensity,

        // --- Discovery provenance (KCP v0.12+, RFC-0012/RFC-0020) ---

        /** Epistemic status: rumored, declared, observed, verified. May be null. */
        String verificationStatus,

        /** Confidence score 0.0-1.0. -1 means not declared. */
        double confidence,

        /** Key ID or agent identity of the verifier. May be null. */
        String verifiedBy,

        /** URL or path to the verification artifact. May be null. */
        String evidence
) {
    /**
     * Backward-compatible constructor with only the original 7 fields.
     * All v0.21 fields default to null/empty/false/-1.
     */
    public KcpUnit(String unitId, String path, String intent, String scope,
                   List<String> audience, List<String> triggers, Map<String, Object> hints) {
        this(unitId, path, intent, scope, audience, triggers, hints,
                null, null, null, null,
                null, null,
                null, false,
                null, null,
                null, -1.0, null, null);
    }
}
