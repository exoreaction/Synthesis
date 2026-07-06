package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpVerifier} — deterministic verification of manifest
 * declarations against filesystem, content, and git evidence (issue #356).
 */
class KcpVerifierTest {

    private static final String TODAY = "2026-07-06";

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // V001 — missing path
    // -----------------------------------------------------------------------

    @Test
    void v001FiresWhenPathMissing() {
        KcpVerifier.Result result = verify(List.of(unit("ghost", "does-not-exist.md")), List.of(), Map.of());
        assertHas(result, "V001", "HIGH");
        assertEquals("contradicted", result.unitVerdicts().get("ghost"));
    }

    @Test
    void v001SilentWhenPathExists() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Readme\n");
        KcpVerifier.Result result = verify(List.of(unit("readme", "README.md")), List.of(), Map.of());
        assertTrue(result.findings().isEmpty(), "Clean unit must have no findings: " + result.findings());
        assertEquals("observed", result.unitVerdicts().get("readme"));
    }

    // -----------------------------------------------------------------------
    // V002 — content hash
    // -----------------------------------------------------------------------

    @Test
    void v002FiresOnHashMismatch() throws Exception {
        Files.writeString(tempDir.resolve("doc.md"), "# Changed since declaration\n");
        KcpRepository.KcpUnitRow u = unitWithHash("doc", "doc.md", "sha256",
                "0000000000000000000000000000000000000000000000000000000000000000");
        KcpVerifier.Result result = verify(List.of(u), List.of(), Map.of());
        assertHas(result, "V002", "HIGH");
        assertEquals("contradicted", result.unitVerdicts().get("doc"));
    }

    @Test
    void v002SilentWhenHashMatches() throws Exception {
        Path doc = tempDir.resolve("doc.md");
        Files.writeString(doc, "# Stable\n");
        KcpRepository.KcpUnitRow u = unitWithHash("doc", "doc.md", "sha256", KcpVerifier.sha256(doc));
        KcpVerifier.Result result = verify(List.of(u), List.of(), Map.of());
        assertTrue(result.findings().isEmpty(), "Matching hash must be silent: " + result.findings());
    }

    @Test
    void v002LowForUnsupportedAlgorithm() throws Exception {
        Files.writeString(tempDir.resolve("doc.md"), "# Doc\n");
        KcpRepository.KcpUnitRow u = unitWithHash("doc", "doc.md", "md5", "abc123");
        KcpVerifier.Result result = verify(List.of(u), List.of(), Map.of());
        assertHas(result, "V002", "LOW");
        assertEquals("observed", result.unitVerdicts().get("doc"),
                "LOW findings alone keep the unit observed");
    }

    // -----------------------------------------------------------------------
    // V003 — stale declaration vs git
    // -----------------------------------------------------------------------

    @Test
    void v003FiresWhenSourceCommittedAfterDeclaration() throws Exception {
        Files.writeString(tempDir.resolve("api.md"), "# API\n");
        KcpRepository.KcpUnitRow u = unitWithExtensions("api", "api.md",
                "{\"updated\":\"2026-01-01\"}");
        KcpVerifier.Result result = verify(List.of(u), List.of(),
                Map.of("api.md", "2026-06-15T10:00:00+00:00"));
        assertHas(result, "V003", "MEDIUM");
        assertEquals("stale", result.unitVerdicts().get("api"));
    }

    @Test
    void v003SilentWhenDeclarationCurrent() throws Exception {
        Files.writeString(tempDir.resolve("api.md"), "# API\n");
        KcpRepository.KcpUnitRow u = unitWithExtensions("api", "api.md",
                "{\"updated\":\"2026-07-01\"}");
        KcpVerifier.Result result = verify(List.of(u), List.of(),
                Map.of("api.md", "2026-06-15T10:00:00+00:00"));
        assertTrue(result.findings().isEmpty(), "Current declaration is silent: " + result.findings());
    }

    // -----------------------------------------------------------------------
    // V004 — dead triggers
    // -----------------------------------------------------------------------

    @Test
    void v004FiresForDeadTriggerAndSkipsStructureCounters() throws Exception {
        Files.writeString(tempDir.resolve("guide.md"), "# Getting Started\n\nInstall things.\n");
        KcpRepository.KcpUnitRow u = unitWithTriggers("guide", "guide.md",
                "[\"getting-started\", \"kubernetes-operator\", \"3-headings\"]");
        KcpVerifier.Result result = verify(List.of(u), List.of(), Map.of());

        List<KcpVerifier.Finding> v004 = result.findings().stream()
                .filter(f -> "V004".equals(f.checkId())).toList();
        assertEquals(1, v004.size(), "Only the dead trigger fires: " + result.findings());
        assertTrue(v004.get(0).detail().contains("kubernetes-operator"));
    }

    // -----------------------------------------------------------------------
    // V005 — dangling references
    // -----------------------------------------------------------------------

    @Test
    void v005FiresForDanglingRelationshipAndSupersession() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "# A\n");
        KcpRepository.KcpUnitRow superseded = new KcpRepository.KcpUnitRow(
                "a", "a.md", "intent?", "global", null, null, null,
                null, null, null, "no-such-unit",
                null, null, null, false, null, null,
                null, -1.0, null, null, null);
        KcpVerifier.Result result = verify(List.of(superseded),
                List.of(new KcpRelationship("a", "missing-target", "context")), Map.of());

        long v005 = result.findings().stream().filter(f -> "V005".equals(f.checkId())).count();
        assertEquals(2, v005, "Dangling superseded_by + dangling relationship target: " + result.findings());
    }

    @Test
    void v005FiresForDanglingDependsOn() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "# A\n");
        KcpRepository.KcpUnitRow u = unitWithExtensions("a", "a.md",
                "{\"depends_on\":[\"nowhere\"]}");
        KcpVerifier.Result result = verify(List.of(u), List.of(), Map.of());
        assertHas(result, "V005", "HIGH");
    }

    // -----------------------------------------------------------------------
    // V006 — temporal sanity
    // -----------------------------------------------------------------------

    @Test
    void v006FiresWhenValidFromAfterValidUntil() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "# A\n");
        KcpRepository.KcpUnitRow u = new KcpRepository.KcpUnitRow(
                "a", "a.md", "intent?", "global", null, null, null,
                "2026-12-31", "2026-01-01", null, null,
                null, null, null, false, null, null,
                null, -1.0, null, null, null);
        KcpVerifier.Result result = verify(List.of(u), List.of(), Map.of());
        assertHas(result, "V006", "HIGH");
    }

    // -----------------------------------------------------------------------
    // K-series folding + hasContradictions
    // -----------------------------------------------------------------------

    @Test
    void kSeriesSignalsAreFoldedIntoFindings() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "# A\n");
        Files.writeString(tempDir.resolve("b.md"), "# B\n");
        // a ⇄ b supersession cycle → K002
        KcpRepository.KcpUnitRow a = new KcpRepository.KcpUnitRow(
                "a", "a.md", "i?", "global", null, null, null,
                null, null, null, "b", null, null, null, false, null, null,
                null, -1.0, null, null, null);
        KcpRepository.KcpUnitRow b = new KcpRepository.KcpUnitRow(
                "b", "b.md", "i?", "global", null, null, null,
                null, null, null, "a", null, null, null, false, null, null,
                null, -1.0, null, null, null);
        KcpVerifier.Result result = verify(List.of(a, b), List.of(), Map.of());
        assertHas(result, "K002", "HIGH");
        assertTrue(result.hasContradictions());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private KcpVerifier.Result verify(List<KcpRepository.KcpUnitRow> units,
                                      List<KcpRelationship> rels,
                                      Map<String, String> gitDates) {
        KcpRepository.KcpManifestRow manifest = new KcpRepository.KcpManifestRow(
                tempDir.resolve("knowledge.yaml").toString(), "test", "0.25",
                units.size(), rels.size(), 0L);
        return KcpVerifier.verifyManifest(manifest, units, rels, gitDates, tempDir, TODAY);
    }

    private void assertHas(KcpVerifier.Result result, String checkId, String severity) {
        assertTrue(result.findings().stream()
                        .anyMatch(f -> checkId.equals(f.checkId()) && severity.equals(f.severity())),
                "Expected " + checkId + "/" + severity + " in: " + result.findings());
    }

    private KcpRepository.KcpUnitRow unit(String id, String path) {
        return new KcpRepository.KcpUnitRow(id, path, "intent?", "global", null, null, null);
    }

    private KcpRepository.KcpUnitRow unitWithHash(String id, String path,
                                                  String algorithm, String value) {
        return new KcpRepository.KcpUnitRow(
                id, path, "intent?", "global", null, null, null,
                null, null, null, null, algorithm, value,
                null, false, null, null, null, -1.0, null, null, null);
    }

    private KcpRepository.KcpUnitRow unitWithExtensions(String id, String path, String extensionsJson) {
        return new KcpRepository.KcpUnitRow(
                id, path, "intent?", "global", null, null, null,
                null, null, null, null, null, null,
                null, false, null, null, null, -1.0, null, null, extensionsJson);
    }

    private KcpRepository.KcpUnitRow unitWithTriggers(String id, String path, String triggersJson) {
        return new KcpRepository.KcpUnitRow(
                id, path, "intent?", "global", null, triggersJson, null,
                null, null, null, null, null, null,
                null, false, null, null, null, -1.0, null, null, null);
    }
}
