package io.exoreaction.synthesis.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Grounder} — claim extraction, fail-closed adjudication,
 * and grounding verdict assembly (#371 item 2).
 *
 * <p>Mirrors kcp-agent's grounding discipline: each claim must be attributed
 * to a loaded, hash-pinned unit or surfaced as an explicit gap.
 */
class GrounderTest {

    // --- Claim splitting ---

    @Test
    void splitClaims_sentenceBoundaries() {
        var claims = Grounder.splitClaims(
                "First sentence. Second sentence! Third sentence?");
        assertEquals(3, claims.size());
        assertEquals("First sentence.", claims.get(0));
        assertEquals("Second sentence!", claims.get(1));
        assertEquals("Third sentence?", claims.get(2));
    }

    @Test
    void splitClaims_newlineSeparated() {
        var claims = Grounder.splitClaims("Line one.\nLine two.\nLine three.");
        assertEquals(3, claims.size());
    }

    @Test
    void splitClaims_emptyReturnsEmpty() {
        assertTrue(Grounder.splitClaims("").isEmpty());
        assertTrue(Grounder.splitClaims("   ").isEmpty());
    }

    @Test
    void splitClaims_nullReturnsEmpty() {
        assertTrue(Grounder.splitClaims(null).isEmpty());
    }

    @Test
    void splitClaims_singleSentence() {
        var claims = Grounder.splitClaims("Just one sentence.");
        assertEquals(1, claims.size());
        assertEquals("Just one sentence.", claims.get(0));
    }

    @Test
    void splitClaims_codeBlocksPreserved() {
        // Code blocks with dots should not be split mid-block
        var claims = Grounder.splitClaims(
                "The method calls `foo.bar()`. It returns a result.");
        assertEquals(2, claims.size());
    }

    // --- FileUnit ---

    @Test
    void fileUnit_computesSha256() {
        var unit = Grounder.FileUnit.of("docs/auth.md", "Hello world content");
        assertNotNull(unit.sha256());
        assertEquals(64, unit.sha256().length(), "SHA-256 hex should be 64 chars");
    }

    @Test
    void fileUnit_sameContentSameSha() {
        var u1 = Grounder.FileUnit.of("a.md", "same content");
        var u2 = Grounder.FileUnit.of("b.md", "same content");
        assertEquals(u1.sha256(), u2.sha256());
    }

    @Test
    void fileUnit_differentContentDifferentSha() {
        var u1 = Grounder.FileUnit.of("a.md", "content A");
        var u2 = Grounder.FileUnit.of("a.md", "content B");
        assertNotEquals(u1.sha256(), u2.sha256());
    }

    // --- Fail-closed adjudication ---

    @Test
    void adjudicate_supportedByLoadedUnit() {
        var units = Map.of("docs/auth.md",
                Grounder.FileUnit.of("docs/auth.md", "auth content"));

        var verdict = Grounder.adjudicate("The auth system uses OAuth.",
                "docs/auth.md", null, units);

        assertTrue(verdict.grounded());
        assertEquals("docs/auth.md", verdict.unitId());
        assertNotNull(verdict.sha256());
    }

    @Test
    void adjudicate_supportedByUnloadedUnit_failsClosed() {
        // Verifier says "docs/secret.md" but that unit was never loaded
        var units = Map.of("docs/auth.md",
                Grounder.FileUnit.of("docs/auth.md", "auth content"));

        var verdict = Grounder.adjudicate("Some claim.",
                "docs/secret.md", null, units);

        assertFalse(verdict.grounded(), "Should fail-closed when cited unit not loaded");
        assertTrue(verdict.reason().contains("not loaded"),
                "Should mention the unit was not loaded");
    }

    @Test
    void adjudicate_noSupportReturnsGap() {
        var units = Map.of("docs/auth.md",
                Grounder.FileUnit.of("docs/auth.md", "auth content"));

        var verdict = Grounder.adjudicate("Some claim.", null, "no match found", units);

        assertFalse(verdict.grounded());
        assertEquals("no match found", verdict.reason());
    }

    @Test
    void adjudicate_nullCitedAndNullNote() {
        var verdict = Grounder.adjudicate("Claim.", null, null, Map.of());
        assertFalse(verdict.grounded());
        assertNotNull(verdict.reason());
    }

    // --- GroundedAnswer assembly ---

    @Test
    void groundedAnswer_allGrounded() {
        var verdicts = List.of(
                new Grounder.ClaimVerdict("Claim 1.", true, "a.md", "sha1", null),
                new Grounder.ClaimVerdict("Claim 2.", true, "b.md", "sha2", null));

        var answer = Grounder.assembleAnswer(verdicts);

        assertEquals("grounded", answer.status());
        assertEquals(2, answer.grounded().size());
        assertTrue(answer.gaps().isEmpty());
    }

    @Test
    void groundedAnswer_partialUnsupported() {
        var verdicts = List.of(
                new Grounder.ClaimVerdict("Claim 1.", true, "a.md", "sha1", null),
                new Grounder.ClaimVerdict("Claim 2.", false, null, null, "no match"));

        var answer = Grounder.assembleAnswer(verdicts);

        assertEquals("partial-unsupported", answer.status());
        assertEquals(1, answer.grounded().size());
        assertEquals(1, answer.gaps().size());
    }

    @Test
    void groundedAnswer_allUngrounded() {
        var verdicts = List.of(
                new Grounder.ClaimVerdict("C1.", false, null, null, "gap 1"),
                new Grounder.ClaimVerdict("C2.", false, null, null, "gap 2"));

        var answer = Grounder.assembleAnswer(verdicts);

        assertEquals("partial-unsupported", answer.status());
        assertTrue(answer.grounded().isEmpty());
        assertEquals(2, answer.gaps().size());
    }

    @Test
    void groundedAnswer_emptyVerdicts() {
        var answer = Grounder.assembleAnswer(List.of());
        assertEquals("grounded", answer.status());
        assertTrue(answer.grounded().isEmpty());
        assertTrue(answer.gaps().isEmpty());
    }

    // --- Verifier prompt building ---

    @Test
    void buildVerifierPrompt_includesClaimsAndUnits() {
        var claims = List.of("Auth uses OAuth.", "API uses REST.");
        var units = Map.of(
                "docs/auth.md", Grounder.FileUnit.of("docs/auth.md", "OAuth 2.0 flow"),
                "docs/api.md", Grounder.FileUnit.of("docs/api.md", "REST endpoints"));

        String prompt = Grounder.buildVerifierPrompt(claims, units);

        assertTrue(prompt.contains("Auth uses OAuth."));
        assertTrue(prompt.contains("API uses REST."));
        assertTrue(prompt.contains("docs/auth.md"));
        assertTrue(prompt.contains("OAuth 2.0 flow"));
        assertTrue(prompt.contains("REST endpoints"));
        assertTrue(prompt.contains("JSON array"));
    }

    // --- JSON extraction ---

    @Test
    void extractJsonArray_plainArray() {
        String input = "[{\"claim\":\"x\",\"supportedBy\":null,\"note\":\"n\"}]";
        assertEquals(input, Grounder.extractJsonArray(input));
    }

    @Test
    void extractJsonArray_markdownFences() {
        String input = "```json\n[{\"claim\":\"x\"}]\n```";
        assertEquals("[{\"claim\":\"x\"}]", Grounder.extractJsonArray(input));
    }

    @Test
    void extractJsonArray_preambleText() {
        String input = "Here is the result:\n[{\"claim\":\"x\",\"supportedBy\":\"a.md\"}]";
        assertEquals("[{\"claim\":\"x\",\"supportedBy\":\"a.md\"}]",
                Grounder.extractJsonArray(input));
    }

    @Test
    void extractJsonArray_nullReturnsEmptyArray() {
        assertEquals("[]", Grounder.extractJsonArray(null));
    }

    // --- Verifier response adjudication ---

    @Test
    void adjudicateVerifierResponse_allGrounded() {
        var claims = List.of("Auth uses OAuth.", "API uses REST.");
        var units = Map.of(
                "docs/auth.md", Grounder.FileUnit.of("docs/auth.md", "OAuth content"),
                "docs/api.md", Grounder.FileUnit.of("docs/api.md", "REST content"));

        String response = """
                [
                  {"claim": "Auth uses OAuth.", "supportedBy": "docs/auth.md", "note": null},
                  {"claim": "API uses REST.", "supportedBy": "docs/api.md", "note": null}
                ]
                """;

        var verdicts = Grounder.adjudicateVerifierResponse(claims, response, units);

        assertEquals(2, verdicts.size());
        assertTrue(verdicts.get(0).grounded());
        assertEquals("docs/auth.md", verdicts.get(0).unitId());
        assertTrue(verdicts.get(1).grounded());
        assertEquals("docs/api.md", verdicts.get(1).unitId());
    }

    @Test
    void adjudicateVerifierResponse_partialGap() {
        var claims = List.of("Auth uses OAuth.", "The sky is blue.");
        var units = Map.of(
                "docs/auth.md", Grounder.FileUnit.of("docs/auth.md", "OAuth content"));

        String response = """
                [
                  {"claim": "Auth uses OAuth.", "supportedBy": "docs/auth.md", "note": null},
                  {"claim": "The sky is blue.", "supportedBy": null, "note": "not in loaded units"}
                ]
                """;

        var verdicts = Grounder.adjudicateVerifierResponse(claims, response, units);

        assertEquals(2, verdicts.size());
        assertTrue(verdicts.get(0).grounded());
        assertFalse(verdicts.get(1).grounded());
        assertEquals("not in loaded units", verdicts.get(1).reason());
    }

    @Test
    void adjudicateVerifierResponse_citesUnloadedUnit_failsClosed() {
        var claims = List.of("Some claim.");
        var units = Map.of(
                "docs/auth.md", Grounder.FileUnit.of("docs/auth.md", "auth content"));

        // Verifier cites a unit that wasn't loaded
        String response = """
                [{"claim": "Some claim.", "supportedBy": "docs/secret.md", "note": null}]
                """;

        var verdicts = Grounder.adjudicateVerifierResponse(claims, response, units);

        assertEquals(1, verdicts.size());
        assertFalse(verdicts.get(0).grounded(), "Should fail-closed");
        assertTrue(verdicts.get(0).reason().contains("not loaded"));
    }

    @Test
    void adjudicateVerifierResponse_malformedJson_failsClosed() {
        var claims = List.of("Claim one.", "Claim two.");

        var verdicts = Grounder.adjudicateVerifierResponse(
                claims, "this is not json at all", Map.of());

        assertEquals(2, verdicts.size());
        assertFalse(verdicts.get(0).grounded());
        assertFalse(verdicts.get(1).grounded());
        assertTrue(verdicts.get(0).reason().contains("not parseable"));
    }

    @Test
    void adjudicateVerifierResponse_missingClaim_failsClosed() {
        var claims = List.of("Claim A.", "Claim B.");
        var units = Map.of("a.md", Grounder.FileUnit.of("a.md", "content"));

        // Verifier only returns verdict for Claim A, omits Claim B
        String response = """
                [{"claim": "Claim A.", "supportedBy": "a.md", "note": null}]
                """;

        var verdicts = Grounder.adjudicateVerifierResponse(claims, response, units);

        assertEquals(2, verdicts.size());
        assertTrue(verdicts.get(0).grounded());
        assertFalse(verdicts.get(1).grounded());
        assertTrue(verdicts.get(1).reason().contains("omitted"));
    }

    // --- Full groundAnswer flow (with mock AiClient) ---

    @Test
    void groundAnswer_fullFlow_allGrounded() {
        var units = Map.of(
                "docs/auth.md", Grounder.FileUnit.of("docs/auth.md", "OAuth 2.0 based auth"));

        // Mock client returns well-formed verifier response
        AiClient mockClient = stubClient("""
                [{"claim": "Auth uses OAuth.", "supportedBy": "docs/auth.md", "note": null}]
                """);

        var result = Grounder.groundAnswer("Auth uses OAuth.", units, mockClient);

        assertEquals("grounded", result.status());
        assertEquals(1, result.grounded().size());
        assertTrue(result.gaps().isEmpty());
        assertNotNull(result.grounded().get(0).sha256());
    }

    @Test
    void groundAnswer_fullFlow_withGaps() {
        var units = Map.of(
                "docs/auth.md", Grounder.FileUnit.of("docs/auth.md", "OAuth content"));

        AiClient mockClient = stubClient("""
                [
                  {"claim": "Auth uses OAuth.", "supportedBy": "docs/auth.md", "note": null},
                  {"claim": "The sky is blue.", "supportedBy": null, "note": "not in sources"}
                ]
                """);

        var result = Grounder.groundAnswer(
                "Auth uses OAuth. The sky is blue.", units, mockClient);

        assertEquals("partial-unsupported", result.status());
        assertEquals(1, result.grounded().size());
        assertEquals(1, result.gaps().size());
    }

    @Test
    void groundAnswer_emptyAnswer() {
        var result = Grounder.groundAnswer("", Map.of(), stubClient("[]"));
        assertEquals("grounded", result.status());
        assertTrue(result.claims().isEmpty());
    }

    @Test
    void groundAnswer_llmFailure_failsClosed() {
        var units = Map.of(
                "a.md", Grounder.FileUnit.of("a.md", "content"));

        // Client that throws
        AiClient failingClient = new AiClient() {
            @Override public String generate(String prompt, int maxTokens) {
                throw new RuntimeException("API down");
            }
            @Override public AiClient.GenerationResult generateWithMeta(
                    String prompt, int maxTokens, double temperature) {
                throw new RuntimeException("API down");
            }
            @Override public String generateFromImage(
                    Path imagePath, String prompt, int maxTokens) {
                throw new RuntimeException("API down");
            }
            @Override public String getModel() { return "test"; }
        };

        var result = Grounder.groundAnswer("Some claim here.", units, failingClient);

        assertEquals("partial-unsupported", result.status());
        assertEquals(1, result.gaps().size());
        assertTrue(result.gaps().get(0).reason().contains("unavailable"));
    }

    // --- Test helper ---

    /** Create a stub AiClient that returns a fixed response. */
    private static AiClient stubClient(String fixedResponse) {
        return new AiClient() {
            @Override public String generate(String prompt, int maxTokens) {
                return fixedResponse;
            }
            @Override public GenerationResult generateWithMeta(
                    String prompt, int maxTokens, double temperature) {
                return new GenerationResult(fixedResponse, false);
            }
            @Override public String generateFromImage(
                    Path imagePath, String prompt, int maxTokens) {
                return fixedResponse;
            }
            @Override public String getModel() { return "test-stub"; }
        };
    }
}
