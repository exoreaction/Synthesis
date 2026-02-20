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
        // .md maps to "documentation" type -> +0.3, format "md" matches -> +0.2
        assertEquals(0.5, result.contentScore(), 0.001);
        assertEquals(0.5, result.totalScore(), 0.001);
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
        // contentScore: type-match(0.3) + format-match(0.2) = 0.5
        assertEquals(0.5, result.contentScore(), 0.001);
        // scopeBonus: org match = 0.24
        assertEquals(0.24, result.scopeBonus(), 0.001);
        // total: 0.5 + 0.24 = 0.74
        assertEquals(0.74, result.totalScore(), 0.001);
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
        assertTrue(results.get(0).contentScore() >= 0.5,
                "csv should match data type, score=" + results.get(0).contentScore());
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
        assertTrue(results.get(0).contentScore() >= 0.5,
                "docx should match document type, score=" + results.get(0).contentScore());
    }
}
