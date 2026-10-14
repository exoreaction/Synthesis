package io.exoreaction.synthesis.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.ai.Grounder;
import io.exoreaction.synthesis.kcp.KcpVerifier;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroundingFreshness} — deterministic re-verification of
 * grounded-answer claim pins {@code (unitId, sha256)} against the current
 * filesystem at recall time. Zero model calls, same spirit as the
 * V002/V003 checks in {@code KcpVerifier}.
 */
class GroundingFreshnessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path writeFile(String relPath, String content) throws IOException {
        Path file = tempDir.resolve(relPath);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /** Pin a unit the way {@code handleAsk}/{@code AskCommand} do: hash of the text preview. */
    private String pin(String relPath) throws IOException {
        String preview = FileUtils.readPreview(tempDir.resolve(relPath), Grounder.PIN_PREVIEW_BYTES);
        return Grounder.FileUnit.of(relPath, preview).sha256();
    }

    private ObjectNode claimNode(String claim, String unitId, String sha256) {
        ObjectNode node = mapper.createObjectNode();
        node.put("claim", claim);
        node.put("grounded", true);
        node.put("unitId", unitId);
        node.put("sha256", sha256);
        node.putNull("reason");
        return node;
    }

    /** Artifact in the shape of a serialized {@link Grounder.GroundedAnswer}. */
    private ObjectNode groundedAnswerArtifact(ObjectNode... claims) {
        ObjectNode artifact = mapper.createObjectNode();
        artifact.put("status", "grounded");
        ArrayNode claimsArray = mapper.createArrayNode();
        ArrayNode groundedArray = mapper.createArrayNode();
        for (ObjectNode c : claims) {
            claimsArray.add(c);
            groundedArray.add(c.deepCopy());
        }
        artifact.set("claims", claimsArray);
        artifact.set("grounded", groundedArray);
        artifact.set("gaps", mapper.createArrayNode());
        return artifact;
    }

    // -----------------------------------------------------------------------
    // (a) backing files unchanged → all claims fresh
    // -----------------------------------------------------------------------

    @Test
    void allClaimsFresh_whenBackingFilesUnchanged() throws IOException {
        writeFile("docs/auth.md", "# Auth\nTokens expire after 15 minutes.\n");
        writeFile("docs/db.md", "# DB\nSQLite with WAL mode.\n");

        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Tokens expire after 15 minutes.", "docs/auth.md", pin("docs/auth.md")),
                claimNode("SQLite runs in WAL mode.", "docs/db.md", pin("docs/db.md")));

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);

        assertEquals(GroundingFreshness.STATUS_FRESH, report.status());
        assertEquals(2, report.freshCount());
        assertEquals(0, report.staleCount());
        assertEquals(0, report.missingCount());
        assertEquals(2, report.claims().size());
        for (GroundingFreshness.ClaimFreshness c : report.claims()) {
            assertEquals(GroundingFreshness.FRESH, c.verdict());
        }
    }

    // -----------------------------------------------------------------------
    // (b) a backing file modified since grounding → that claim stale, others unaffected
    // -----------------------------------------------------------------------

    @Test
    void modifiedFileMarksOnlyItsClaimStale() throws IOException {
        writeFile("docs/auth.md", "# Auth\nTokens expire after 15 minutes.\n");
        writeFile("docs/db.md", "# DB\nSQLite with WAL mode.\n");

        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Tokens expire after 15 minutes.", "docs/auth.md", pin("docs/auth.md")),
                claimNode("SQLite runs in WAL mode.", "docs/db.md", pin("docs/db.md")));

        // The world moves after grounding
        writeFile("docs/auth.md", "# Auth\nTokens expire after 60 minutes.\n");

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);

        assertEquals(GroundingFreshness.STATUS_STALE, report.status());
        assertEquals(1, report.freshCount());
        assertEquals(1, report.staleCount());
        assertEquals(0, report.missingCount());
        assertEquals(GroundingFreshness.STALE, report.claims().get(0).verdict());
        assertEquals("docs/auth.md", report.claims().get(0).unitId());
        assertEquals(GroundingFreshness.FRESH, report.claims().get(1).verdict());
    }

    // -----------------------------------------------------------------------
    // (c) backing file deleted → unit-missing
    // -----------------------------------------------------------------------

    @Test
    void deletedFileMarksClaimUnitMissing() throws IOException {
        writeFile("docs/auth.md", "# Auth\nTokens expire after 15 minutes.\n");
        String pinned = pin("docs/auth.md");
        Files.delete(tempDir.resolve("docs/auth.md"));

        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Tokens expire after 15 minutes.", "docs/auth.md", pinned));

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);

        assertEquals(GroundingFreshness.STATUS_STALE, report.status());
        assertEquals(0, report.freshCount());
        assertEquals(0, report.staleCount());
        assertEquals(1, report.missingCount());
        assertEquals(GroundingFreshness.UNIT_MISSING, report.claims().get(0).verdict());
    }

    // -----------------------------------------------------------------------
    // Pin semantics: the pin covers the preview window the verifier saw
    // -----------------------------------------------------------------------

    @Test
    void changeBeyondPreviewWindowStaysFresh() throws IOException {
        // The grounding pin hashes only the first PIN_PREVIEW_BYTES of the file —
        // that is all the verifier ever saw. A change beyond that window does not
        // invalidate the evidence the claim was grounded on.
        String head = "x".repeat(Grounder.PIN_PREVIEW_BYTES);
        writeFile("big.md", head + "original tail");
        String pinned = pin("big.md");

        writeFile("big.md", head + "changed tail!!");

        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Claim about the head.", "big.md", pinned));

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);
        assertEquals(GroundingFreshness.STATUS_FRESH, report.status());
    }

    @Test
    void fullFileHashPinIsAlsoAcceptedAsFresh() throws IOException {
        // Pins recorded by other producers may hash the full file bytes
        // (KcpVerifier-style). An unchanged file must still verify fresh.
        String head = "y".repeat(Grounder.PIN_PREVIEW_BYTES);
        Path file = writeFile("big2.md", head + "tail beyond the preview window");
        String fullBytesPin = KcpVerifier.sha256(file);
        assertNotNull(fullBytesPin);

        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Claim pinned over full bytes.", "big2.md", fullBytesPin));

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);
        assertEquals(GroundingFreshness.STATUS_FRESH, report.status());
    }

    // -----------------------------------------------------------------------
    // Artifact shapes
    // -----------------------------------------------------------------------

    @Test
    void askResponseGroundingShapeIsSupported() throws IOException {
        // handleAsk nests verdicts under a "grounding" node whose "grounded"
        // entries carry only {claim, unitId, sha256}.
        writeFile("README.md", "Synthesis indexes everything.\n");

        ObjectNode grounded = mapper.createObjectNode();
        grounded.put("claim", "Synthesis indexes everything.");
        grounded.put("unitId", "README.md");
        grounded.put("sha256", pin("README.md"));

        ObjectNode grounding = mapper.createObjectNode();
        grounding.put("status", "grounded");
        ArrayNode groundedArray = mapper.createArrayNode();
        groundedArray.add(grounded);
        grounding.set("grounded", groundedArray);
        grounding.set("gaps", mapper.createArrayNode());

        ObjectNode artifact = mapper.createObjectNode();
        artifact.put("answer", "Synthesis indexes everything.");
        artifact.set("grounding", grounding);

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);
        assertEquals(GroundingFreshness.STATUS_FRESH, report.status());
        assertEquals(1, report.freshCount());
    }

    @Test
    void gapClaimsWithoutPinsAreIgnored() throws IOException {
        writeFile("docs/auth.md", "# Auth\nTokens expire.\n");

        ObjectNode gap = mapper.createObjectNode();
        gap.put("claim", "Unsupported speculation.");
        gap.put("grounded", false);
        gap.putNull("unitId");
        gap.putNull("sha256");
        gap.put("reason", "unsupported: verifier found no match");

        ObjectNode artifact = groundedAnswerArtifact(
                claimNode("Tokens expire.", "docs/auth.md", pin("docs/auth.md")));
        ((ArrayNode) artifact.get("claims")).add(gap);

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, tempDir);
        assertEquals(1, report.claims().size(), "Only hash-pinned claims are checked");
        assertEquals(GroundingFreshness.STATUS_FRESH, report.status());
    }

    @Test
    void artifactWithoutPinnedClaimsReportsNoPinnedClaims() {
        ObjectNode planArtifact = mapper.createObjectNode();
        planArtifact.set("units", mapper.createArrayNode());

        GroundingFreshness.Report report = GroundingFreshness.check(planArtifact, tempDir);
        assertEquals(GroundingFreshness.STATUS_NO_PINNED_CLAIMS, report.status());
        assertTrue(report.claims().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Fail-closed edges
    // -----------------------------------------------------------------------

    @Test
    void missingWorkspaceRootIsUnverifiable() throws IOException {
        writeFile("docs/auth.md", "# Auth\n");
        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Claim.", "docs/auth.md", pin("docs/auth.md")));

        assertEquals(GroundingFreshness.STATUS_UNVERIFIABLE,
                GroundingFreshness.check(artifact, null).status());
        assertEquals(GroundingFreshness.STATUS_UNVERIFIABLE,
                GroundingFreshness.check(artifact, tempDir.resolve("does-not-exist")).status());
    }

    @Test
    void unitIdEscapingTheWorkspaceIsUnitMissing() throws IOException {
        // A file that exists on disk but outside the workspace root must not
        // be resolved — claims only pin units inside the grounded workspace.
        Path root = Files.createDirectories(tempDir.resolve("ws"));
        Path outside = writeFile("outside.md", "outside the workspace\n");
        String outsidePin = Grounder.FileUnit.of("../outside.md",
                FileUtils.readPreview(outside, Grounder.PIN_PREVIEW_BYTES)).sha256();

        JsonNode artifact = groundedAnswerArtifact(
                claimNode("Claim.", "../outside.md", outsidePin));

        GroundingFreshness.Report report = GroundingFreshness.check(artifact, root);
        assertEquals(1, report.missingCount(),
                "unitId escaping the workspace root must not be resolved");
        assertEquals(GroundingFreshness.UNIT_MISSING, report.claims().get(0).verdict());
    }
}
