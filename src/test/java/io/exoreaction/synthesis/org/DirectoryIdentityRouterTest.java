package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.DirectoryScorer.ScoredCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryIdentityRouterTest {

    @TempDir
    Path tempDir;

    /**
     * Helper: creates a subdirectory with a .synthesis.md identity file.
     *
     * @param dirName  name of the directory to create under tempDir
     * @param types    content types (e.g. "meeting-notes", "documentation")
     * @param formats  file extensions (e.g. "md", "pdf")
     * @return the created directory path
     */
    private Path createIdentityDir(String dirName, List<String> types, List<String> formats)
            throws IOException {
        return createIdentityDir(dirName, types, formats, ScopeLevel.WORKSPACE, null, null);
    }

    private Path createIdentityDir(String dirName, List<String> types, List<String> formats,
                                   ScopeLevel scopeLevel, String scopeOrg, String scopeEntity)
            throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                types, formats, List.of(),
                scopeLevel, scopeOrg, scopeEntity,
                0.8, null, "test", "Test directory: " + dirName
        );

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        parser.write(dir.resolve(".synthesis.md"), identity);
        return dir;
    }

    @Test
    void route_noCandidates_returnsEmpty() {
        // No .synthesis.md files anywhere in workspace
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path file = tempDir.resolve("test.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.5);

        assertTrue(result.isEmpty(), "Should return empty when no candidates exist");
    }

    @Test
    void route_matchingType_routesFile() throws IOException {
        // Create a meetings directory that accepts meeting-notes type and md format
        createIdentityDir("meetings", List.of("meeting-notes"), List.of("md"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path file = tempDir.resolve("weekly-standup.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.3);

        assertTrue(result.isPresent(), "Should route .md file to meetings dir");
        assertFalse(result.get().ambiguous(), "Should not be ambiguous with single candidate");
        assertEquals("meetings", result.get().directory().getFileName().toString());
        assertTrue(result.get().score() >= 0.3, "Score should be at least 0.3");
    }

    @Test
    void route_belowThreshold_returnsEmpty() throws IOException {
        // Create a scripts directory that only accepts scripts — no match for .md files
        createIdentityDir("scripts", List.of("scripts"), List.of("sh"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path file = tempDir.resolve("readme.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.5);

        assertTrue(result.isEmpty(), "Should return empty when score is below threshold");
    }

    @Test
    void route_ambiguous_returnsAmbiguous() throws IOException {
        // Two directories that both accept .md documentation with identical scoring
        createIdentityDir("docs", List.of("documentation"), List.of("md"));
        createIdentityDir("reports", List.of("report"), List.of("md"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        // "report.md" maps to documentation and report types; both dirs accept md format
        Path file = tempDir.resolve("report.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.3);

        assertTrue(result.isPresent(), "Should return a result for ambiguous match");
        assertTrue(result.get().ambiguous(), "Should be flagged as ambiguous");
    }

    @Test
    void route_scopeBlocked_returnsEmpty() throws IOException {
        // Create a directory scoped to a different organization
        createIdentityDir("globex-docs", List.of("documentation"), List.of("md"),
                ScopeLevel.ORGANIZATION, "Globex", null);

        // Simulate a file in an Acme-scoped context
        // Since we pass null orgRegistry, file scope resolves to WORKSPACE (no org).
        // For a true scope block, we need both file and dir to have different orgs.
        // We'll create a second dir with Acme scope and use an org registry.
        Path acmeDir = tempDir.resolve("acme");
        Files.createDirectories(acmeDir);

        // Build a minimal org registry with Acme org
        OrganizationRegistry orgRegistry = new OrganizationRegistry(tempDir);
        Organization acmeOrg = new Organization("Acme", OrganizationType.COMPANY, acmeDir);
        orgRegistry.addOrganization(acmeOrg);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, orgRegistry);
        // File inside acme dir -> scope resolves to ORGANIZATION/Acme
        Path file = acmeDir.resolve("report.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.3);

        // globex-docs is the only candidate, but it's scoped to Globex while file is Acme -> blocked
        assertTrue(result.isEmpty(),
                "Should return empty when file scope org differs from dir scope org (blocked)");
    }

    @Test
    void scoreAll_returnsSortedCandidates() throws IOException {
        // High-scoring: accepts media and png
        createIdentityDir("media", List.of("media"), List.of("png"));
        // Low-scoring: accepts scripts, not images
        createIdentityDir("scripts", List.of("scripts"), List.of("sh"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path file = tempDir.resolve("diagram.png");

        List<ScoredCandidate> scored = router.scoreAll(file);

        assertFalse(scored.isEmpty(), "Should return scored candidates");
        assertEquals(2, scored.size(), "Should have two candidates");
        // Media dir should score higher
        assertTrue(scored.get(0).totalScore() >= scored.get(1).totalScore(),
                "Candidates should be sorted by score descending");
        assertEquals("media", scored.get(0).directory().getFileName().toString(),
                "Media dir should be first (higher score)");
    }

    @Test
    void route_routeResult_scoreLabel_formatsCorrectly() throws IOException {
        createIdentityDir("docs", List.of("documentation"), List.of("md"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path file = tempDir.resolve("notes.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.3);

        assertTrue(result.isPresent());
        String label = result.get().scoreLabel();
        assertTrue(label.startsWith("dir-identity: docs @"),
                "Score label should contain directory name. Got: " + label);
    }

    @Test
    void route_candidatesCachedAcrossCalls() throws IOException {
        createIdentityDir("docs", List.of("documentation"), List.of("md"));

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);

        // First call — triggers discovery
        Path file1 = tempDir.resolve("a.md");
        router.route(file1, 0.3);

        // Second call — should use cached candidates (no exception even if FS changed)
        Path file2 = tempDir.resolve("b.md");
        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file2, 0.3);

        assertTrue(result.isPresent(), "Second call should still find cached candidates");
    }

    @Test
    void route_directoryWithEmptyIdentity_ignored() throws IOException {
        // Create directory with .synthesis.md but no accepts types/formats
        Path dir = tempDir.resolve("empty-identity");
        Files.createDirectories(dir);

        DirectoryIdentity emptyIdentity = new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.5, null, "test", "Empty"
        );
        new DirectoryIdentityParser().write(dir.resolve(".synthesis.md"), emptyIdentity);

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Path file = tempDir.resolve("test.md");

        Optional<DirectoryIdentityRouter.RouteResult> result = router.route(file, 0.3);

        assertTrue(result.isEmpty(),
                "Directories with empty acceptsTypes and acceptsFormats should be ignored");
    }
}
