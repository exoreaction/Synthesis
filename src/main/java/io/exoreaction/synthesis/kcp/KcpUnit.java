package io.exoreaction.synthesis.kcp;

import java.util.List;
import java.util.Map;

/**
 * Represents a single unit entry within a KCP manifest ({@code knowledge.yaml}).
 *
 * <p>Units are the primary addressable entries in the Knowledge Context Protocol.
 * Each unit maps an intent question to a specific document path, with optional
 * scope, audience, trigger keywords, and hints for AI agents.
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
        Map<String, Object> hints
) {}
