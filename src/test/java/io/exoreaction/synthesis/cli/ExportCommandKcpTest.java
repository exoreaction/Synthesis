package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.YamlAnalyzer;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.kcp.KcpUnit;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code synthesis export --format kcp} — KCP v0.25 output conformance.
 *
 * <p>Uses the package-private helper methods on {@link ExportCommand} directly
 * rather than driving the full CLI, so no real workspace or database is needed.
 */
class ExportCommandKcpTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Header / root-level fields
    // -----------------------------------------------------------------------

    @Test
    void headerContainsKcpVersion025() {
        String output = export(List.of(md("README.md", "Project overview.")));
        assertTrue(output.contains("kcp_version: \"0.25\""),
                "Must emit kcp_version field: " + firstLines(output));
    }

    @Test
    void headerContainsV025InComment() {
        String output = export(List.of(md("README.md", "Project overview.")));
        assertTrue(output.contains("KCP) v0.25"),
                "Comment must say v0.25: " + firstLines(output));
    }

    @Test
    void headerContainsLanguageField() {
        String output = export(List.of(md("README.md", "Project overview.")));
        assertTrue(output.contains("language: en"), "Must emit language: en");
    }

    @Test
    void headerContainsIndexingField() {
        String output = export(List.of(md("README.md", "Project overview.")));
        assertTrue(output.contains("indexing: open"), "Must emit indexing: open");
    }

    @Test
    void headerContainsHintsUnitCount() {
        List<SearchResult> results = List.of(
                md("docs/a.md", "First doc."),
                md("docs/b.md", "Second doc."),
                md("docs/c.md", "Third doc.")
        );
        String output = export(results);
        assertTrue(output.contains("unit_count: 3"),
                "hints.unit_count should reflect non-skipped units: " + output);
    }

    @Test
    void hintsUnitCountExcludesSkippedFiles() {
        List<SearchResult> results = List.of(
                md("docs/a.md", "Has summary."),
                makeResult("docs/empty.md", "MARKDOWN", "", "")  // no summary, no headings → skipped
        );
        String output = export(results);
        assertTrue(output.contains("unit_count: 1"),
                "Skipped files must not count: " + output);
    }

    // -----------------------------------------------------------------------
    // Unit-level fields
    // -----------------------------------------------------------------------

    @Test
    void unitContainsFormatMarkdown() {
        String output = export(List.of(md("docs/guide.md", "A guide.")));
        assertTrue(output.contains("format: markdown"),
                "Markdown files should emit format: markdown");
    }

    @Test
    void unitDoesNotEmitKindForDefaultKnowledge() {
        // kind: knowledge is the default — omit it to keep the manifest concise
        String output = export(List.of(md("docs/guide.md", "A guide.")));
        assertFalse(output.contains("kind:"),
                "Default kind:knowledge should be omitted");
    }

    @Test
    void unitEmitsKindPolicyForSecurityMd() {
        String output = export(List.of(md("SECURITY.md", "Security policy.")));
        assertTrue(output.contains("kind: policy"),
                "SECURITY.md should get kind: policy: " + output);
    }

    @Test
    void unitEmitsKindPolicyForLicenseMd() {
        String output = export(List.of(md("LICENSE.md", "MIT License.")));
        assertTrue(output.contains("kind: policy"),
                "LICENSE.md should get kind: policy");
    }

    @Test
    void unitEmitsKindPolicyForContributingMd() {
        String output = export(List.of(md("CONTRIBUTING.md", "How to contribute.")));
        assertTrue(output.contains("kind: policy"),
                "CONTRIBUTING.md should get kind: policy");
    }

    @Test
    void validatedFieldIsQuoted() {
        // v0.5 validated dates should be quoted strings (YAML date ambiguity prevention)
        String output = export(List.of(md("README.md", "Overview.")));
        assertTrue(output.contains("validated: \""),
                "validated should be a quoted string: " + output);
    }

    @Test
    void triggersLimitIsEight() {
        // headings with many entries — should emit up to 8 triggers
        ExportCommand cmd = new ExportCommand();
        String headings = "Alpha\nBeta\nGamma\nDelta\nEpsilon\nZeta\nEta\nTheta\nIota";
        List<String> triggers = cmd.toKcpTriggers(headings, "");
        assertTrue(triggers.size() <= 8, "Trigger list should be capped at 8");
        assertTrue(triggers.size() >= 8, "Should emit up to 8 triggers when enough headings");
    }

    // -----------------------------------------------------------------------
    // toKcpFormat helper
    // -----------------------------------------------------------------------

    @Test
    void formatInferenceMarkdown() {
        ExportCommand cmd = new ExportCommand();
        assertEquals("markdown", cmd.toKcpFormat("docs/guide.md"));
        assertEquals("markdown", cmd.toKcpFormat("README.mdx"));
        assertEquals("markdown", cmd.toKcpFormat("notes.markdown"));
    }

    @Test
    void formatInferencePdf() {
        assertEquals("pdf", new ExportCommand().toKcpFormat("report.pdf"));
    }

    @Test
    void formatInferenceYaml() {
        assertEquals("yaml", new ExportCommand().toKcpFormat("config.yaml"));
        assertEquals("yaml", new ExportCommand().toKcpFormat("config.yml"));
    }

    @Test
    void formatInferenceOpenApi() {
        assertEquals("openapi", new ExportCommand().toKcpFormat("openapi.yaml"));
        assertEquals("openapi", new ExportCommand().toKcpFormat("swagger.yml"));
        assertEquals("openapi", new ExportCommand().toKcpFormat("asyncapi.yaml"));
    }

    @Test
    void formatInferenceJsonSchema() {
        assertEquals("json-schema", new ExportCommand().toKcpFormat("user-schema.json"));
        assertEquals("json", new ExportCommand().toKcpFormat("package.json"));
    }

    @Test
    void formatInferenceNullForCodeFiles() {
        assertNull(new ExportCommand().toKcpFormat("src/Main.java"));
        assertNull(new ExportCommand().toKcpFormat("app.py"));
        assertNull(new ExportCommand().toKcpFormat("index.ts"));
    }

    // -----------------------------------------------------------------------
    // toKcpKind helper
    // -----------------------------------------------------------------------

    @Test
    void kindInferencePolicyFiles() {
        ExportCommand cmd = new ExportCommand();
        assertEquals("policy", cmd.toKcpKind("SECURITY.md"));
        assertEquals("policy", cmd.toKcpKind("LICENSE.md"));
        assertEquals("policy", cmd.toKcpKind("CONTRIBUTING.md"));
        assertEquals("policy", cmd.toKcpKind("docs/POLICY.md"));
        assertEquals("policy", cmd.toKcpKind("PRIVACY.md"));
        assertEquals("policy", cmd.toKcpKind("TERMS.md"));
        assertEquals("policy", cmd.toKcpKind("NOTICE.md"));
    }

    @Test
    void kindInferenceSchemaFiles() {
        ExportCommand cmd = new ExportCommand();
        assertEquals("schema", cmd.toKcpKind("openapi.yaml"));
        assertEquals("schema", cmd.toKcpKind("swagger.yml"));
        assertEquals("schema", cmd.toKcpKind("user-schema.json"));
    }

    @Test
    void kindInferenceDefaultIsNull() {
        // null means "omit" (default knowledge)
        ExportCommand cmd = new ExportCommand();
        assertNull(cmd.toKcpKind("docs/overview.md"));
        assertNull(cmd.toKcpKind("README.md"));
        assertNull(cmd.toKcpKind("src/Main.java"));
    }

    // -----------------------------------------------------------------------
    // v0.25 fields: content_structure, content_hash, temporal, discovery
    // -----------------------------------------------------------------------

    @Test
    void unitEmitsContentStructureProseForMarkdown() {
        String output = export(List.of(md("docs/guide.md", "A guide.")));
        assertTrue(output.contains("content_structure:"), "Must emit content_structure block");
        assertTrue(output.contains("primary: prose"), "Markdown primary modality is prose: " + output);
        assertTrue(output.contains("contains: [prose]"), "contains must list the primary modality");
    }

    @Test
    void contentStructurePrimaryVocabulary() {
        // Default export is MARKDOWN-only (CODE flows through with --type CODE);
        // the CODE→code mapping is covered here at the helper level.
        ExportCommand cmd = new ExportCommand();
        assertEquals("code", cmd.toKcpContentStructurePrimary("CODE"));
        assertEquals("reference", cmd.toKcpContentStructurePrimary("YAML"));
        assertEquals("reference", cmd.toKcpContentStructurePrimary("JSON"));
        assertEquals("prose", cmd.toKcpContentStructurePrimary("MARKDOWN"));
        assertEquals("prose", cmd.toKcpContentStructurePrimary(null));
    }

    @Test
    void unitEmitsContentHashMatchingFileBytes() throws Exception {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/hashed.md"), "# Hashed\ncontent to digest\n");
        String output = export(List.of(md("docs/hashed.md", "Hashed doc.")));

        assertTrue(output.contains("content_hash:"), "Must emit content_hash block: " + output);
        assertTrue(output.contains("algorithm: sha256"), "Algorithm must be sha256");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] raw = digest.digest(Files.readAllBytes(tempDir.resolve("docs/hashed.md")));
        StringBuilder expected = new StringBuilder();
        for (byte b : raw) expected.append(String.format("%02x", b));
        assertTrue(output.contains("value: \"" + expected + "\""),
                "Hash must match recomputed sha256 of the file: " + output);
    }

    @Test
    void unitOmitsContentHashWhenFileUnreadable() {
        // makeResult() does not create the file on disk
        String output = export(List.of(md("docs/missing.md", "Not on disk.")));
        assertFalse(output.contains("content_hash:"),
                "Unreadable files must omit content_hash, not fail: " + output);
    }

    @Test
    void unitEmitsDiscoveryDeclared() {
        // v0.16 epistemic ordering: rumored < declared < observed < verified.
        // A generated manifest is first-party self-description → declared.
        String output = export(List.of(md("README.md", "Overview.")));
        assertTrue(output.contains("discovery:"), "Must emit discovery block");
        assertTrue(output.contains("verification_status: declared"),
                "Generated units are self-described → declared: " + output);
        assertTrue(output.contains("source: synthesis"), "Discovery source is synthesis");
    }

    @Test
    void noTemporalBlockWhenWorkspaceIsNotGitRepo() {
        String output = export(List.of(md("README.md", "Overview.")));
        assertFalse(output.contains("temporal:"),
                "recorded_at is git-derived; non-git workspaces omit temporal: " + output);
    }

    @Test
    void collectGitCommitDatesEmptyForNonGitDirectory() {
        assertTrue(ExportCommand.collectGitCommitDates(tempDir).isEmpty(),
                "Non-git directory must yield no commit dates");
        assertTrue(ExportCommand.collectGitCommitDates(null).isEmpty(),
                "Null workspace root must yield no commit dates");
    }

    @Test
    void interopNoAccessOrPaymentEmitted() {
        // v0.25.1 interop: `access` declares the authentication gate only and payment
        // uses the `payment` block. A local export has neither — both must be absent.
        String output = export(List.of(md("README.md", "Overview.")));
        assertFalse(output.contains("access:"), "Must not emit access without an auth gate");
        assertFalse(output.contains("payment:"), "Must not emit payment blocks");
        assertFalse(output.contains("rate_limits:"), "Must not emit rate_limits blocks");
    }

    // -----------------------------------------------------------------------
    // Round-trip: export → YamlAnalyzer detection → KcpUnit extraction
    // -----------------------------------------------------------------------

    @Test
    void exportRoundTripsThroughYamlAnalyzer() throws IOException {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("README.md"), "# Readme\n");
        Files.writeString(tempDir.resolve("docs/api.md"), "# API\n");
        String output = export(List.of(
                md("README.md", "Project overview."),
                makeResult("docs/api.md", "MARKDOWN", "API reference.", "Endpoints\nAuth")
        ));

        Path manifest = tempDir.resolve("knowledge.yaml");
        Files.writeString(manifest, output);
        FileMetadata metadata = new FileMetadata(
                manifest, "knowledge.yaml", "knowledge.yaml",
                ".yaml", FileUtils.FileType.YAML, null,
                Files.size(manifest), Instant.now(), null);

        YamlAnalyzer analyzer = new YamlAnalyzer();
        assertTrue(analyzer.canAnalyze(metadata));
        AnalysisResult result = analyzer.analyze(metadata);

        assertEquals("kcp-manifest", result.metrics().get("yamlType"),
                "Own export must be detected as a KCP manifest");
        assertEquals("0.25", result.metrics().get("kcpVersion"),
                "Parsed version must round-trip");
        assertEquals(2, result.metrics().get("unitCount"), "Both units must be extracted");

        @SuppressWarnings("unchecked")
        List<KcpUnit> units = (List<KcpUnit>) result.metrics().get("kcpUnits");
        KcpUnit readme = units.stream()
                .filter(u -> "README.md".equals(u.path()))
                .findFirst().orElseThrow();
        assertEquals("sha256", readme.contentHashAlgorithm(),
                "content_hash.algorithm must survive the round-trip");
        assertNotNull(readme.contentHashValue(), "content_hash.value must survive");
        assertEquals("declared", readme.verificationStatus(),
                "discovery.verification_status must survive");
        assertEquals("prose", readme.contentStructurePrimary(),
                "content_structure.primary must survive");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Runs exportAsKcp via the package-private accessor. */
    private String export(List<SearchResult> results) {
        ExportCommand cmd = new ExportCommand();
        return cmd.exportAsKcpForTest(results, tempDir);
    }

    private SearchResult md(String path, String summary) {
        return makeResult(path, "MARKDOWN", summary, "");
    }

    private SearchResult makeResult(String path, String type, String summary, String headings) {
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return new SearchResult(
                tempDir.resolve(path), path, 1.0f, fileName,
                type, null, summary, headings, "", 1000L);
    }

    private String firstLines(String s) {
        return s.lines().limit(12).reduce("", (a, b) -> a + b + "\n");
    }
}
