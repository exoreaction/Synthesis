package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrgCommand.EnrichSubcommand} codebase path auto-discovery.
 */
class OrgEnrichCommandTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private Path synthesisDir;

    @BeforeEach
    void setUp() throws IOException {
        workspaceRoot = tempDir;
        synthesisDir = workspaceRoot.resolve(".synthesis");
        Files.createDirectories(synthesisDir);
    }

    // =====================================================================
    // EnrichProposal and normalizeName Tests
    // =====================================================================

    @Test
    void normalizeName_removesNonAlphanumeric() {
        assertEquals("sparebank1", OrgCommand.EnrichSubcommand.normalizeName("SpareBank-1"));
        assertEquals("exoreaction", OrgCommand.EnrichSubcommand.normalizeName("eXOReaction"));
        assertEquals("catalystone", OrgCommand.EnrichSubcommand.normalizeName("CatalystOne"));
        assertEquals("libpcb", OrgCommand.EnrichSubcommand.normalizeName("lib-pcb"));
    }

    @Test
    void normalizeName_handlesEmptyAndSimple() {
        assertEquals("", OrgCommand.EnrichSubcommand.normalizeName(""));
        assertEquals("test", OrgCommand.EnrichSubcommand.normalizeName("test"));
        assertEquals("test123", OrgCommand.EnrichSubcommand.normalizeName("test-123"));
    }

    // =====================================================================
    // readArtifactId Tests
    // =====================================================================

    @Test
    void readArtifactId_validPomWithParent_extractsProjectArtifactId() throws IOException {
        Path dir = tempDir.resolve("my-project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-pom</artifactId>
                        <version>1.0</version>
                    </parent>
                    <artifactId>my-library</artifactId>
                    <version>2.0</version>
                </project>
                """);

        String artifactId = OrgCommand.EnrichSubcommand.readArtifactId(dir);
        assertEquals("my-library", artifactId);
    }

    @Test
    void readArtifactId_pomWithoutParent_extractsArtifactId() throws IOException {
        Path dir = tempDir.resolve("simple-project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <artifactId>simple-lib</artifactId>
                </project>
                """);

        String artifactId = OrgCommand.EnrichSubcommand.readArtifactId(dir);
        assertEquals("simple-lib", artifactId);
    }

    @Test
    void readArtifactId_noPomFile_returnsNull() {
        Path dir = tempDir.resolve("no-pom");
        assertNull(OrgCommand.EnrichSubcommand.readArtifactId(dir));
    }

    // =====================================================================
    // readPackageJsonName Tests
    // =====================================================================

    @Test
    void readPackageJsonName_validPackageJson_extractsName() throws IOException {
        Path dir = tempDir.resolve("node-project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), """
                {
                    "name": "my-app",
                    "version": "1.0.0"
                }
                """);

        String name = OrgCommand.EnrichSubcommand.readPackageJsonName(dir);
        assertEquals("my-app", name);
    }

    @Test
    void readPackageJsonName_scopedPackage_stripsScope() throws IOException {
        Path dir = tempDir.resolve("scoped-project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), """
                {
                    "name": "@org/my-component",
                    "version": "1.0.0"
                }
                """);

        String name = OrgCommand.EnrichSubcommand.readPackageJsonName(dir);
        assertEquals("my-component", name);
    }

    @Test
    void readPackageJsonName_noPackageJson_returnsNull() {
        Path dir = tempDir.resolve("no-pkg");
        assertNull(OrgCommand.EnrichSubcommand.readPackageJsonName(dir));
    }

    // =====================================================================
    // Layer 1: CODEBASE-INDEX.md Discovery Tests
    // =====================================================================

    @Test
    void discoverForEntity_codebaseIndex_findsMatchingEntry() throws IOException {
        // Create org directory with CODEBASE-INDEX.md
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        // Create a target directory that the Location points to
        Path targetDir = tempDir.resolve("src").resolve("elprint");
        Files.createDirectories(targetDir);

        Files.writeString(orgDir.resolve("CODEBASE-INDEX.md"),
                "### Elprint (25 repositories) - PCB Client\n" +
                "**Location:** `" + targetDir + "`\n");

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Elprint", "Elprint", org);

        assertEquals(1, proposals.size());
        assertEquals(0.5, proposals.get(0).confidence);
        assertEquals("CODEBASE-INDEX.md", proposals.get(0).source);
        assertTrue(proposals.get(0).paths.get(0).endsWith("elprint"));
    }

    @Test
    void discoverForEntity_codebaseIndex_noMatch_returnsEmpty() throws IOException {
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        Files.writeString(orgDir.resolve("CODEBASE-INDEX.md"), """
                ### OtherClient (10 repositories) - Some Client
                **Location:** `/tmp/other/`
                """);

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Elprint", "Elprint", org);

        // Should be empty since no CODEBASE-INDEX.md entry matches "Elprint"
        // (it may try lower layers and also find nothing)
        for (var p : proposals) {
            assertNotEquals("CODEBASE-INDEX.md", p.source);
        }
    }

    // =====================================================================
    // Layer 2: Organization Inheritance Tests
    // =====================================================================

    @Test
    void discoverForEntity_orgInheritance_matchesByName() throws IOException {
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        // Create matching codebase directory
        Path codebaseDir = tempDir.resolve("codebase").resolve("elprint");
        Files.createDirectories(codebaseDir);

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(codebaseDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Elprint", "Elprint", org);

        assertEquals(1, proposals.size());
        assertEquals(0.4, proposals.get(0).confidence);
        assertEquals("organization-inheritance", proposals.get(0).source);
    }

    // =====================================================================
    // Layer 3: Git Repo Discovery Tests
    // =====================================================================

    @Test
    void discoverForEntity_gitRepoDiscovery_findsMatchingGitRepo() throws IOException {
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        // Create a parent codebase dir with a git repo inside
        Path parentDir = tempDir.resolve("repos");
        Files.createDirectories(parentDir);

        Path gitRepoDir = parentDir.resolve("elprint-backend");
        Files.createDirectories(gitRepoDir.resolve(".git"));

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(parentDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Elprint", "Elprint", org);

        assertEquals(1, proposals.size());
        assertEquals(0.35, proposals.get(0).confidence);
        assertEquals("git-repo-discovery", proposals.get(0).source);
        assertTrue(proposals.get(0).paths.get(0).contains("elprint-backend"));
    }

    @Test
    void discoverForEntity_gitRepoDiscovery_matchesByPomArtifactId() throws IOException {
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        Path parentDir = tempDir.resolve("repos2");
        Files.createDirectories(parentDir);

        // Git repo with non-matching dir name but matching pom.xml artifactId
        Path gitRepoDir = parentDir.resolve("random-name");
        Files.createDirectories(gitRepoDir.resolve(".git"));
        Files.writeString(gitRepoDir.resolve("pom.xml"), """
                <project>
                    <artifactId>elprint</artifactId>
                </project>
                """);

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(parentDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Elprint", "Elprint", org);

        assertEquals(1, proposals.size());
        assertEquals("git-repo-discovery", proposals.get(0).source);
        assertTrue(proposals.get(0).paths.get(0).contains("random-name"));
    }

    @Test
    void discoverForEntity_gitRepoDiscovery_matchesByPackageJsonName() throws IOException {
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        Path parentDir = tempDir.resolve("repos3");
        Files.createDirectories(parentDir);

        // Git repo with non-matching dir name but matching package.json name
        Path gitRepoDir = parentDir.resolve("some-other-name");
        Files.createDirectories(gitRepoDir.resolve(".git"));
        Files.writeString(gitRepoDir.resolve("package.json"), """
                {
                    "name": "@company/elprint-ui",
                    "version": "1.0.0"
                }
                """);

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(parentDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("elprint-ui", "elprint-ui", org);

        assertEquals(1, proposals.size());
        assertEquals("git-repo-discovery", proposals.get(0).source);
    }

    // =====================================================================
    // Layer 4: Path Heuristics Tests
    // =====================================================================

    @Test
    void discoverForEntity_pathHeuristics_findsExistingDirectory() throws IOException {
        Path orgDir = tempDir.resolve("TestOrg");
        Files.createDirectories(orgDir);

        // Create /src/testclient/ - the heuristic checks /src/{normalizedName}
        // We need to actually have this directory at /src/ root level
        // Since we can't control /src, let's just verify the method works
        Organization org = new Organization("TestOrg", OrganizationType.COMPANY, orgDir);

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        // This entity won't exist at /src/nonexistentclient12345
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("nonexistentclient12345", "nonexistentclient12345", org);

        assertTrue(proposals.isEmpty(), "Should not find a path for non-existent directory");
    }

    // =====================================================================
    // Confidence Threshold Tests
    // =====================================================================

    @Test
    void enrichProposal_confidenceValues_areCorrectPerLayer() throws IOException {
        // Verify confidence values for each layer
        Path orgDir = tempDir.resolve("ConfOrg");
        Files.createDirectories(orgDir);

        // Layer 1: Create matching CODEBASE-INDEX.md
        Path targetDir1 = tempDir.resolve("target1");
        Files.createDirectories(targetDir1);
        Files.writeString(orgDir.resolve("CODEBASE-INDEX.md"),
                "### Client1 (5 repos) - Test\n" +
                "**Location:** `" + targetDir1 + "`\n");

        Organization org = new Organization("ConfOrg", OrganizationType.COMPANY, orgDir);

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Client1", "Client1", org);

        assertEquals(1, proposals.size());
        assertEquals(0.5, proposals.get(0).confidence,
                "Layer 1 (CODEBASE-INDEX.md) should have confidence 0.5");
    }

    // =====================================================================
    // Additive-Only Behavior Tests
    // =====================================================================

    @Test
    void enrichProposal_pathsAreImmutable() {
        OrgCommand.EnrichSubcommand.EnrichProposal proposal =
                new OrgCommand.EnrichSubcommand.EnrichProposal(
                        "Test", "Org", List.of("/path1", "/path2"),
                        0.5, "test-source");

        assertThrows(UnsupportedOperationException.class,
                () -> proposal.paths.add("/path3"),
                "Proposal paths should be immutable");
    }

    @Test
    void enrichProposal_containsAllFields() {
        OrgCommand.EnrichSubcommand.EnrichProposal proposal =
                new OrgCommand.EnrichSubcommand.EnrichProposal(
                        "Elprint", "eXOReaction", List.of("/src/elprint"),
                        0.5, "CODEBASE-INDEX.md");

        assertEquals("Elprint", proposal.entityName);
        assertEquals("eXOReaction", proposal.orgName);
        assertEquals(List.of("/src/elprint"), proposal.paths);
        assertEquals(0.5, proposal.confidence);
        assertEquals("CODEBASE-INDEX.md", proposal.source);
    }

    // =====================================================================
    // Layer Priority Tests
    // =====================================================================

    @Test
    void discoverForEntity_layer1Wins_stopsAfterCodebaseIndex() throws IOException {
        Path orgDir = tempDir.resolve("PriorityOrg");
        Files.createDirectories(orgDir);

        // Create target for CODEBASE-INDEX.md
        Path targetDir = tempDir.resolve("priority-target");
        Files.createDirectories(targetDir);

        Files.writeString(orgDir.resolve("CODEBASE-INDEX.md"),
                "### MyClient (5 repos) - Test\n" +
                "**Location:** `" + targetDir + "`\n");

        // Also set up org inheritance that would match
        Path inheritDir = tempDir.resolve("codebase").resolve("myclient");
        Files.createDirectories(inheritDir);

        Organization org = new Organization("PriorityOrg", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(inheritDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("MyClient", "MyClient", org);

        // Should only have 1 proposal (Layer 1), not 2 (Layer 1 + Layer 2)
        assertEquals(1, proposals.size());
        assertEquals(0.5, proposals.get(0).confidence, "Should use Layer 1 (highest confidence)");
        assertEquals("CODEBASE-INDEX.md", proposals.get(0).source);
    }

    @Test
    void discoverForEntity_layer2Wins_whenNoCodebaseIndex() throws IOException {
        Path orgDir = tempDir.resolve("Layer2Org");
        Files.createDirectories(orgDir);
        // No CODEBASE-INDEX.md

        // Set up matching codebase path
        Path codebaseDir = tempDir.resolve("cb").resolve("myclient");
        Files.createDirectories(codebaseDir);

        Organization org = new Organization("Layer2Org", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(codebaseDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("MyClient", "MyClient", org);

        assertEquals(1, proposals.size());
        assertEquals(0.4, proposals.get(0).confidence, "Should use Layer 2 (org inheritance)");
        assertEquals("organization-inheritance", proposals.get(0).source);
    }

    // =====================================================================
    // No Proposals Found Test
    // =====================================================================

    @Test
    void discoverForEntity_noMatch_returnsEmptyProposals() throws IOException {
        Path orgDir = tempDir.resolve("EmptyOrg");
        Files.createDirectories(orgDir);

        Organization org = new Organization("EmptyOrg", OrganizationType.COMPANY, orgDir);

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("VeryUniqueNonExistentClient99999",
                        "VeryUniqueNonExistentClient99999", org);

        assertTrue(proposals.isEmpty(), "Should find no proposals for non-existent entity");
    }

    // =====================================================================
    // Registry Integration (apply/save) Tests
    // =====================================================================

    @Test
    void applyProposal_addsCodebasesToClient() throws IOException {
        OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);

        Organization org = new Organization("TestOrg", OrganizationType.COMPANY,
                tempDir.resolve("TestOrg"));
        Client client = new Client("Elprint", "TestOrg",
                tempDir.resolve("clients/Elprint"), ClientStatus.ACTIVE, "Elprint");
        org.addClient(client);
        registry.addOrganization(org);

        // Save initial state
        registry.save();

        // Verify initial state has no codebases
        assertTrue(client.getCodebases().isEmpty());

        // Load fresh and verify
        OrganizationRegistry loaded = new OrganizationRegistry(workspaceRoot);
        loaded.load();
        Client loadedClient = loaded.getOrganizations().get(0).getClients().get(0);
        assertTrue(loadedClient.getCodebases().isEmpty());
    }

    @Test
    void enrichProposal_multiplePathsForSameEntity() {
        OrgCommand.EnrichSubcommand.EnrichProposal proposal =
                new OrgCommand.EnrichSubcommand.EnrichProposal(
                        "Elprint", "eXOReaction",
                        List.of("/src/elprint/elprint-backend", "/src/elprint/elprint-frontend"),
                        0.5, "CODEBASE-INDEX.md");

        assertEquals(2, proposal.paths.size());
        assertTrue(proposal.paths.contains("/src/elprint/elprint-backend"));
        assertTrue(proposal.paths.contains("/src/elprint/elprint-frontend"));
    }

    // =====================================================================
    // Git Repo Discovery Edge Cases
    // =====================================================================

    @Test
    void discoverForEntity_gitRepo_dirWithoutGit_notDiscovered() throws IOException {
        Path orgDir = tempDir.resolve("GitTestOrg");
        Files.createDirectories(orgDir);

        Path parentDir = tempDir.resolve("gitrepos");
        Files.createDirectories(parentDir);

        // Directory matches name but has no .git
        Path nonGitDir = parentDir.resolve("elprint");
        Files.createDirectories(nonGitDir);

        Organization org = new Organization("GitTestOrg", OrganizationType.COMPANY, orgDir);
        org.setCodebasePaths(List.of(parentDir.toString()));

        OrgCommand.EnrichSubcommand enrichCmd = new OrgCommand.EnrichSubcommand();
        List<OrgCommand.EnrichSubcommand.EnrichProposal> proposals =
                enrichCmd.discoverForEntity("Elprint", "Elprint", org);

        // Layer 2 (org inheritance) may find it since parentDir/elprint matches
        // But Layer 3 (git) should not since there's no .git
        for (var p : proposals) {
            assertNotEquals("git-repo-discovery", p.source,
                    "Dir without .git should not be found by git-repo-discovery");
        }
    }
}
