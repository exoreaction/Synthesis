package io.exoreaction.synthesis.enrichment;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.config.CredentialStore;

/**
 * Enrichment capability tiers for companion file generation.
 *
 * <p>Each tier builds on the previous:
 * <ul>
 *   <li><b>BASIC</b> -- Deterministic metadata only. Works everywhere, zero dependencies.</li>
 *   <li><b>LOCAL</b> -- Adds local tool output (Whisper transcripts, pdftoppm slides).
 *       Requires optional local binaries.</li>
 *   <li><b>AI</b> -- Adds cloud AI capabilities (Claude Vision, summaries).
 *       Requires API key and network.</li>
 * </ul>
 *
 * <p>The tier is determined by edition and available tools:
 * <ul>
 *   <li>Core/Enterprise (air-gapped): BASIC only</li>
 *   <li>Pro/Ultimate with API key: AI</li>
 *   <li>Pro/Ultimate without API key: LOCAL if tools available, else BASIC</li>
 * </ul>
 */
public enum EnrichmentLevel {

    /** Deterministic metadata only. No external tools, no AI. Works in Core edition. */
    BASIC,

    /** Deterministic + local tools (Whisper, pdftoppm, ffprobe). No cloud. */
    LOCAL,

    /** Full AI enrichment (Vision, summaries, semantic). Requires API key. */
    AI;

    /**
     * Returns the enrichment level appropriate for the current Synthesis edition.
     *
     * @param edition the Synthesis edition string
     * @return the maximum enrichment level for that edition
     */
    public static EnrichmentLevel forEdition(String edition) {
        return switch (edition) {
            case "core" -> BASIC;
            case "pro" -> AI;
            case "enterprise" -> BASIC;  // Air-gapped
            case "ultimate" -> AI;
            default -> LOCAL;
        };
    }

    /**
     * Detects the maximum available enrichment level based on runtime environment.
     *
     * <p>Checks in order:
     * <ol>
     *   <li>Air-gapped mode? -> BASIC</li>
     *   <li>Claude API available? -> AI</li>
     *   <li>Local tools available? -> LOCAL</li>
     *   <li>Otherwise -> BASIC</li>
     * </ol>
     *
     * @return the highest enrichment level available in the current environment
     */
    public static EnrichmentLevel maxAvailable() {
        if (SynthesisApp.isAirGapped()) {
            return BASIC;
        }

        // Check if Claude API is available (env var or credential store)
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = CredentialStore.retrieve("ANTHROPIC_API_KEY").orElse(null);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return AI;
        }

        // Phase 2: Check for local tools (Whisper, Tesseract, pdftoppm)
        if (io.exoreaction.synthesis.util.WhisperDetector.isAvailable() ||
            io.exoreaction.synthesis.util.TesseractDetector.isAvailable() ||
            io.exoreaction.synthesis.util.PdftoppmDetector.isAvailable()) {
            return LOCAL;
        }

        return BASIC;
    }

    /**
     * Returns true if this level includes local tool capabilities.
     */
    public boolean hasLocalTools() {
        return this == LOCAL || this == AI;
    }

    /**
     * Returns true if this level includes AI capabilities.
     */
    public boolean hasAI() {
        return this == AI;
    }
}
