package io.exoreaction.synthesis.summary;

import java.time.Instant;

/**
 * Result of a summary generation, containing the profile, optional AI summary,
 * and metadata about generation.
 */
public record SummaryResult(
    CodebaseProfile.Profile profile,
    String aiSummary,           // null if --no-ai
    SummaryLevel level,
    SummaryPerspective perspective,
    String temporalContext,     // null if no --since
    Instant generatedAt,
    long generationTimeMs,
    boolean fromCache,
    String cacheFingerprint
) {
    /**
     * Creates a result from just a profile (no AI, Phase 1).
     */
    public static SummaryResult fromProfile(CodebaseProfile.Profile profile,
                                             SummaryLevel level,
                                             SummaryPerspective perspective,
                                             long generationTimeMs) {
        return new SummaryResult(profile, null, level, perspective, null,
            Instant.now(), generationTimeMs, false, null);
    }

    /**
     * Creates a result with AI-enhanced summary (Phase 2).
     */
    public static SummaryResult withAiSummary(CodebaseProfile.Profile profile,
                                               String aiSummary,
                                               SummaryLevel level,
                                               SummaryPerspective perspective,
                                               long generationTimeMs) {
        return new SummaryResult(profile, aiSummary, level, perspective, null,
            Instant.now(), generationTimeMs, false, null);
    }

    /**
     * Creates a result with temporal context (Phase 5).
     */
    public static SummaryResult withTemporal(CodebaseProfile.Profile profile,
                                              String aiSummary,
                                              SummaryLevel level,
                                              SummaryPerspective perspective,
                                              String temporalContext,
                                              long generationTimeMs) {
        return new SummaryResult(profile, aiSummary, level, perspective, temporalContext,
            Instant.now(), generationTimeMs, false, null);
    }
}
