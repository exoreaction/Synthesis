package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.DirectoryScorer.DirectoryCandidate;
import io.exoreaction.synthesis.org.DirectoryScorer.ScoredCandidate;
import io.exoreaction.synthesis.org.ScopeResolver.ResolvedScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryScorerTest {

    @TempDir
    Path tempDir;

    private DirectoryScorer scorer;
    private ScopeChecker scopeChecker;

    @BeforeEach
    void setUp() {
        scopeChecker = new ScopeChecker();
        scorer = new DirectoryScorer(scopeChecker);
    }

    @Test
    void score_emptyList_returnsEmpty() {
        Path file = tempDir.resolve("test.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        List<ScoredCandidate> results = scorer.score(file, fileScope, List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void score_singleMatch_returnsOne() {
        Path file = tempDir.resolve("notes.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        Path dir = tempDir.resolve("docs");
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("documentation"),
                List.of("md"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.8,
                null,
                "test",
                "A docs directory"
        );

        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        assertFalse(result.blocked());
        // .md maps to "documentation" type (generic) -> +0.15, format "md" matches -> +0.2
        assertEquals(0.35, result.contentScore(), 0.001);
        assertEquals(0.35, result.totalScore(), 0.001);
    }

    @Test
    void score_blockedCandidate_isMarkedBlocked() {
        Path file = tempDir.resolve("report.pdf");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);

        Path dir = tempDir.resolve("globex-docs");
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("documentation"),
                List.of("pdf"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Globex",
                null,
                0.9,
                null,
                "test",
                "Globex docs"
        );

        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertTrue(results.get(0).blocked());
        assertTrue(results.get(0).reasons().stream().anyMatch(r -> r.contains("BLOCKED")));
    }

    @Test
    void score_scopeBonus_addsToTotal() {
        Path file = tempDir.resolve("spec.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);

        Path dir = tempDir.resolve("acme-docs");
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("documentation"),
                List.of("md"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Acme",
                null,
                0.9,
                null,
                "test",
                "Acme docs"
        );

        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        assertFalse(result.blocked());
        // contentScore: type-match-generic(0.15) + format-match(0.2) = 0.35
        assertEquals(0.35, result.contentScore(), 0.001);
        // scopeBonus: org match = 0.24
        assertEquals(0.24, result.scopeBonus(), 0.001);
        // total: 0.35 + 0.24 = 0.59
        assertEquals(0.59, result.totalScore(), 0.001);
    }

    @Test
    void score_ambiguityDetected_whenTopTwoClose() {
        Path file = tempDir.resolve("report.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // Two directories that both accept .md documentation with similar scores
        Path dir1 = tempDir.resolve("docs");
        DirectoryIdentity identity1 = new DirectoryIdentity(
                List.of("documentation"),
                List.of("md"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.8,
                null,
                "test",
                "General docs"
        );

        Path dir2 = tempDir.resolve("reports");
        DirectoryIdentity identity2 = new DirectoryIdentity(
                List.of("report"),
                List.of("md"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.8,
                null,
                "test",
                "Reports"
        );

        List<DirectoryCandidate> candidates = List.of(
                new DirectoryCandidate(dir1, identity1),
                new DirectoryCandidate(dir2, identity2)
        );
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(2, results.size());
        // Both should have contentScore of 0.5 (type-match 0.3 + format-match 0.2)
        // So the top candidate should be marked AMBIGUOUS
        ScoredCandidate top = results.get(0);
        assertTrue(top.reasons().contains("AMBIGUOUS"),
                "Top candidate should be marked AMBIGUOUS, reasons: " + top.reasons());
    }

    @Test
    void score_sortedByTotalScoreDescending() {
        Path file = tempDir.resolve("diagram.png");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // Low-scoring candidate: accepts scripts, not media
        Path dir1 = tempDir.resolve("scripts");
        DirectoryIdentity identity1 = new DirectoryIdentity(
                List.of("scripts"),
                List.of("sh"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.8,
                null,
                "test",
                "Scripts"
        );

        // High-scoring candidate: accepts media and png
        Path dir2 = tempDir.resolve("media");
        DirectoryIdentity identity2 = new DirectoryIdentity(
                List.of("media"),
                List.of("png"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.8,
                null,
                "test",
                "Media files"
        );

        List<DirectoryCandidate> candidates = List.of(
                new DirectoryCandidate(dir1, identity1),
                new DirectoryCandidate(dir2, identity2)
        );
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(2, results.size());
        // media dir should be first (type-match + format-match = 0.5)
        // scripts dir should be second (no match = 0.0)
        assertTrue(results.get(0).totalScore() >= results.get(1).totalScore());
        assertEquals(dir2, results.get(0).directory());
    }

    @Test
    void score_blockedCandidateLast() {
        Path file = tempDir.resolve("notes.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);

        // Blocked candidate (different org) but high content match
        Path dir1 = tempDir.resolve("globex-docs");
        DirectoryIdentity identity1 = new DirectoryIdentity(
                List.of("documentation"),
                List.of("md"),
                List.of("*notes*"),
                ScopeLevel.ORGANIZATION,
                "Globex",
                null,
                0.9,
                null,
                "test",
                "Globex docs"
        );

        // Non-blocked candidate with no content match
        Path dir2 = tempDir.resolve("acme-scripts");
        DirectoryIdentity identity2 = new DirectoryIdentity(
                List.of("scripts"),
                List.of("sh"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Acme",
                null,
                0.9,
                null,
                "test",
                "Acme scripts"
        );

        List<DirectoryCandidate> candidates = List.of(
                new DirectoryCandidate(dir1, identity1),
                new DirectoryCandidate(dir2, identity2)
        );
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(2, results.size());
        // Non-blocked should come first regardless of score
        assertFalse(results.get(0).blocked(), "First candidate should not be blocked");
        assertTrue(results.get(1).blocked(), "Second candidate should be blocked");
    }

    // ---- #177: EXTENSION_TYPE_MAP additions ----

    private DirectoryCandidate candidate(Path dir, List<String> types, List<String> formats) {
        DirectoryIdentity identity = new DirectoryIdentity(
                types, formats, List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        return new DirectoryCandidate(dir, identity);
    }

    @Test
    void score_zipFile_matchesArtifactDirectory() {
        Path dir = tempDir.resolve("artifacts");
        Path file = tempDir.resolve("exports.zip");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        List<DirectoryCandidate> candidates = List.of(
                candidate(dir, List.of("artifact"), List.of("zip")));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertTrue(results.get(0).contentScore() > 0,
                "zip should match artifact type, score=" + results.get(0).contentScore());
    }

    @Test
    void score_xlsxFile_matchesDataDirectory() {
        Path dir = tempDir.resolve("data");
        Path file = tempDir.resolve("report.xlsx");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        List<DirectoryCandidate> candidates = List.of(
                candidate(dir, List.of("spreadsheet", "data"), List.of("xlsx")));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertTrue(results.get(0).contentScore() >= 0.5,
                "xlsx should match spreadsheet/data, score=" + results.get(0).contentScore());
    }

    @Test
    void score_yamlFile_matchesAutomationDirectory() {
        Path dir = tempDir.resolve("automation");
        Path file = tempDir.resolve("deploy.yaml");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        List<DirectoryCandidate> candidates = List.of(
                candidate(dir, List.of("automation", "config"), List.of("yaml", "yml")));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertTrue(results.get(0).contentScore() >= 0.5,
                "yaml should match automation/config, score=" + results.get(0).contentScore());
    }

    @Test
    void score_csvFile_matchesDataDirectory() {
        Path dir = tempDir.resolve("data");
        Path file = tempDir.resolve("metrics.csv");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        List<DirectoryCandidate> candidates = List.of(
                candidate(dir, List.of("data"), List.of("csv")));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        // "data" is a generic type -> 0.15 + format 0.2 = 0.35
        assertTrue(results.get(0).contentScore() >= 0.35,
                "csv should match data type (generic), score=" + results.get(0).contentScore());
    }

    @Test
    void score_sqlFile_matchesDatabaseDirectory() {
        Path dir = tempDir.resolve("database");
        Path file = tempDir.resolve("schema.sql");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        List<DirectoryCandidate> candidates = List.of(
                candidate(dir, List.of("data", "database"), List.of("sql")));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertTrue(results.get(0).contentScore() >= 0.5,
                "sql should match database type, score=" + results.get(0).contentScore());
    }

    @Test
    void score_docxFile_matchesDocumentDirectory() {
        Path dir = tempDir.resolve("documents");
        Path file = tempDir.resolve("proposal.docx");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        List<DirectoryCandidate> candidates = List.of(
                candidate(dir, List.of("document"), List.of("docx")));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        // "document" is a generic type -> 0.15 + format 0.2 = 0.35
        assertTrue(results.get(0).contentScore() >= 0.35,
                "docx should match document type (generic), score=" + results.get(0).contentScore());
    }

    // ---- #209: Filename token matching ----

    @Test
    void score_filenameTokenMatch_synthesisDemo_toSynthesisMediaDir() {
        // Create workspace-relative paths: products/Synthesis/media
        Path wsRoot = tempDir;
        DirectoryScorer wsScorer = new DirectoryScorer(scopeChecker, wsRoot);

        Path dir = tempDir.resolve("products").resolve("Synthesis").resolve("media");
        Path file = tempDir.resolve("synthesis-demo.mp4");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));

        List<ScoredCandidate> results = wsScorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        // "synthesis" and "demo" are filename tokens; "products", "synthesis", "media" are dir tokens
        // "synthesis" matches -> 1/2 overlap -> 0.125 token bonus
        // Base: generic type "media"(0.15) + format "mp4"(0.2) = 0.35
        // Total content: 0.35 + 0.125 = 0.475
        assertTrue(result.contentScore() > 0.4,
                "synthesis-demo.mp4 should get a token-match bonus for Synthesis/media dir, "
                + "contentScore=" + result.contentScore()
                + ", reasons=" + result.reasons());
        // Verify the token-match reason is present
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("filename-token-match")),
                "Should have filename-token-match reason, reasons=" + result.reasons());
    }

    @Test
    void score_filenameTokenMatch_noOverlap_zeroBonus() {
        Path wsRoot = tempDir;
        DirectoryScorer wsScorer = new DirectoryScorer(scopeChecker, wsRoot);

        Path dir = tempDir.resolve("automation");
        Path file = tempDir.resolve("quarterly-report.pdf");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("automation", "scripts"), List.of("sh"),
                List.of(), ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));

        List<ScoredCandidate> results = wsScorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        // "quarterly" and "report" are filename tokens; "automation" is dir token
        // No overlap -> 0 token bonus
        assertFalse(result.reasons().stream().anyMatch(r -> r.contains("filename-token-match")),
                "Should NOT have filename-token-match reason for unrelated file, reasons=" + result.reasons());
    }

    @Test
    void score_filenameTokenMatch_multipleTokensOverlap() {
        Path wsRoot = tempDir;
        DirectoryScorer wsScorer = new DirectoryScorer(scopeChecker, wsRoot);

        // Dir path: products/aurora/analytics
        Path dir = tempDir.resolve("products").resolve("aurora").resolve("analytics");
        Path file = tempDir.resolve("aurora-analytics-demo.mp4");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));

        List<ScoredCandidate> results = wsScorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        // "aurora", "analytics", "demo" are filename tokens (3)
        // "products", "aurora", "analytics" are dir tokens
        // 2 matches (aurora, analytics) out of 3 -> 0.667 ratio -> 0.167 bonus
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("filename-token-match")),
                "Should have filename-token-match for aurora-analytics-demo.mp4 -> aurora/analytics, "
                + "reasons=" + result.reasons());
        // Base: generic type "media"(0.15) + format "mp4"(0.2) = 0.35
        // The token match bonus should be significant (> 0.1)
        double tokenBonus = result.contentScore() - 0.35; // subtract generic type + format base
        assertTrue(tokenBonus > 0.1,
                "Token match bonus should be > 0.1, got " + tokenBonus);
    }

    // ---- #209: Generic vs specific type matching ----

    @Test
    void score_genericTypeMatch_givesReducedScore() {
        // "documentation" is a generic type
        Path dir = tempDir.resolve("docs");
        Path file = tempDir.resolve("notes.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("documentation"), // generic type only
                List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        // generic type (0.15) + format (0.2) = 0.35
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("type-match-generic")),
                "Should have type-match-generic reason, reasons=" + result.reasons());
        assertEquals(0.35, result.contentScore(), 0.001,
                "Generic type (0.15) + format (0.2) should equal 0.35");
    }

    @Test
    void score_specificTypeMatch_givesFullScore() {
        // "automation" is a specific type
        Path dir = tempDir.resolve("automation");
        Path file = tempDir.resolve("deploy.sh");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("automation"), // specific type
                List.of("sh"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        // specific type (0.3) + format (0.2) = 0.5
        assertTrue(result.reasons().stream().anyMatch(r -> r.startsWith("type-match(+")),
                "Should have full type-match reason (not generic), reasons=" + result.reasons());
        assertEquals(0.5, result.contentScore(), 0.001,
                "Specific type (0.3) + format (0.2) should equal 0.5");
    }

    @Test
    void score_mixedSpecificAndGenericTypes_usesSpecificScore() {
        // Directory accepts both specific "meeting-notes" and generic "documentation"
        Path dir = tempDir.resolve("meetings");
        Path file = tempDir.resolve("standup.md");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("meeting-notes", "documentation"), // mixed
                List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "");
        List<DirectoryCandidate> candidates = List.of(new DirectoryCandidate(dir, identity));

        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        ScoredCandidate result = results.get(0);
        // "meeting-notes" is specific -> should get full type score
        // .md maps to Set.of("documentation", "meeting-notes", ...) so both match
        // hasSpecific should be true because "meeting-notes" is not in GENERIC_TYPES
        assertTrue(result.reasons().stream().anyMatch(r -> r.startsWith("type-match(+")),
                "When both specific and generic types match, should use specific score, reasons=" + result.reasons());
    }

    // ---- #209: tokenize() utility tests ----

    @Test
    void tokenize_splitsOnDashesUnderscoresAndDots() {
        java.util.Set<String> tokens = DirectoryScorer.tokenize("synthesis-demo_file.test");
        assertTrue(tokens.contains("synthesis"));
        assertTrue(tokens.contains("demo"));
        assertTrue(tokens.contains("file"));
        assertTrue(tokens.contains("test"));
    }

    @Test
    void tokenize_filtersShortTokens() {
        java.util.Set<String> tokens = DirectoryScorer.tokenize("my-a-synthesis-bb-demo");
        assertFalse(tokens.contains("my"), "2-char token should be filtered");
        assertFalse(tokens.contains("a"), "1-char token should be filtered");
        assertFalse(tokens.contains("bb"), "2-char token should be filtered");
        assertTrue(tokens.contains("synthesis"));
        assertTrue(tokens.contains("demo"));
    }

    @Test
    void tokenize_lowercases() {
        java.util.Set<String> tokens = DirectoryScorer.tokenize("Synthesis-DEMO");
        assertTrue(tokens.contains("synthesis"));
        assertTrue(tokens.contains("demo"));
    }

    @Test
    void tokenize_emptyInput_returnsEmptySet() {
        assertTrue(DirectoryScorer.tokenize("").isEmpty());
        assertTrue(DirectoryScorer.tokenize(null).isEmpty());
    }
}
