package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillGenerator}.
 */
class SkillGeneratorTest {

    @TempDir
    Path tempDir;

    private OrganizationRegistry registry;
    private SkillGenerator generator;
    private static final Instant TIMESTAMP = Instant.parse("2026-02-14T12:00:00Z");

    @BeforeEach
    void setUp() throws IOException {
        // Create .synthesis directory
        Files.createDirectories(tempDir.resolve(".synthesis"));

        registry = new OrganizationRegistry(tempDir);
        generator = new SkillGenerator(tempDir, registry);
    }

    // --- generateAll ---

    @Test
    void generateAll_emptyRegistry_noFiles() throws IOException {
        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertEquals(0, result.totalFiles());
        assertTrue(result.skills().isEmpty());
    }

    @Test
    void generateAll_singleOrg_generatesWorkspaceAndOrgSkills() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        // Should generate: workspace-context, organization-testco
        // No navigate-clients, pipeline-tracker, proof-points (no clients/products)
        assertTrue(result.totalFiles() >= 2);
        assertTrue(result.skills().containsKey("workspace-context.yaml"));
        assertTrue(result.skills().containsKey("organization-testco.yaml"));
    }

    @Test
    void generateAll_orgWithClients_generatesNavigateAndPipeline() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addClient(new Client("Acme", "TestCo",
                tempDir.resolve("acme"), ClientStatus.ACTIVE, "Acme"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertTrue(result.skills().containsKey("navigate-clients.yaml"));
        assertTrue(result.skills().containsKey("pipeline-tracker.yaml"));
    }

    @Test
    void generateAll_orgWithProducts_generatesProofPoints() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addProduct(new Product("lib-pcb", "TestCo", tempDir.resolve("pcb")));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertTrue(result.skills().containsKey("proof-points.yaml"));
    }

    @Test
    void generateAll_multipleOrgs_generatesMultipleOrgSkills() throws IOException {
        Organization org1 = new Organization("OrgA", OrganizationType.COMPANY,
                tempDir.resolve("OrgA"));
        Organization org2 = new Organization("OrgB", OrganizationType.FOUNDATION,
                tempDir.resolve("OrgB"));
        registry.addOrganization(org1);
        registry.addOrganization(org2);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertTrue(result.skills().containsKey("organization-orga.yaml"));
        assertTrue(result.skills().containsKey("organization-orgb.yaml"));
    }

    @Test
    void generateAll_createsSkillsDirectory() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);

        generator.generateAll(TIMESTAMP);

        assertTrue(Files.isDirectory(generator.getSkillsDir()));
    }

    @Test
    void generateAll_filesExistOnDisk() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addClient(new Client("ClientA", "TestCo",
                tempDir.resolve("a"), ClientStatus.ACTIVE, "ClientA"));
        org.addProduct(new Product("ProductX", "TestCo", tempDir.resolve("p")));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        for (String filename : result.skills().keySet()) {
            Path skillFile = result.skillsDir().resolve(filename);
            assertTrue(Files.exists(skillFile),
                    "Skill file should exist: " + filename);
            String content = Files.readString(skillFile);
            assertTrue(content.contains("name:"),
                    "Skill file should contain 'name:': " + filename);
            assertTrue(content.contains("instructions: |"),
                    "Skill file should contain instructions: " + filename);
        }
    }

    @Test
    void generateAll_lineCountsAreAccurate() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        for (var entry : result.skills().entrySet()) {
            Path skillFile = result.skillsDir().resolve(entry.getKey());
            int actualLines = Files.readString(skillFile).split("\n", -1).length;
            assertEquals(actualLines, entry.getValue(),
                    "Line count mismatch for " + entry.getKey());
        }
    }

    @Test
    void generateAll_totalLinesMatchSum() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addClient(new Client("A", "TestCo", tempDir.resolve("a"),
                ClientStatus.ACTIVE, "A"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        int expectedTotal = result.skills().values().stream()
                .mapToInt(Integer::intValue).sum();
        assertEquals(expectedTotal, result.totalLines());
    }

    // --- Individual generators ---

    @Test
    void generateWorkspaceContext_returnsLineCount() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);

        int lines = generator.generateWorkspaceContext(TIMESTAMP);

        assertTrue(lines > 0);
        assertTrue(Files.exists(generator.getSkillsDir().resolve("workspace-context.yaml")));
    }

    @Test
    void generateOrganizationSkill_returnsLineCount() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));

        int lines = generator.generateOrganizationSkill(org, TIMESTAMP);

        assertTrue(lines > 0);
        assertTrue(Files.exists(generator.getSkillsDir().resolve("organization-testco.yaml")));
    }

    // --- SkillsDir ---

    @Test
    void getSkillsDir_isWithinSynthesisDir() {
        Path skillsDir = generator.getSkillsDir();

        assertTrue(skillsDir.toString().contains(".synthesis"));
        assertTrue(skillsDir.toString().endsWith("skills"));
    }

    // --- New skill types ---

    @Test
    void generateAll_orgWithCodebases_generatesArchitectureOverview() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.getCodebasePaths().add("/src/testco/repo/");
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertTrue(result.skills().containsKey("architecture-overview.yaml"));
    }

    @Test
    void generateAll_alwaysGeneratesTechStack() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertTrue(result.skills().containsKey("tech-stack.yaml"));
    }

    @Test
    void generateAll_alwaysGeneratesKeyDecisions() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        assertTrue(result.skills().containsKey("key-decisions.yaml"));
    }

    @Test
    void generateAll_allSkillTypes_fullOrg() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addClient(new Client("Acme", "TestCo",
                tempDir.resolve("acme"), ClientStatus.ACTIVE, "Acme"));
        org.addProduct(new Product("lib-pcb", "TestCo", tempDir.resolve("pcb")));
        org.getCodebasePaths().add("/src/testco/repo/");
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        // Should now generate all 8 skill types
        assertTrue(result.skills().containsKey("workspace-context.yaml"));
        assertTrue(result.skills().containsKey("organization-testco.yaml"));
        assertTrue(result.skills().containsKey("navigate-clients.yaml"));
        assertTrue(result.skills().containsKey("pipeline-tracker.yaml"));
        assertTrue(result.skills().containsKey("proof-points.yaml"));
        assertTrue(result.skills().containsKey("architecture-overview.yaml"));
        assertTrue(result.skills().containsKey("tech-stack.yaml"));
        assertTrue(result.skills().containsKey("key-decisions.yaml"));
        assertEquals(8, result.totalFiles());
    }

    // --- Past-only clients should NOT generate pipeline tracker ---

    @Test
    void generateAll_onlyPastClients_noPipelineTracker() throws IOException {
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addClient(new Client("OldClient", "TestCo",
                tempDir.resolve("o"), ClientStatus.PAST, "OldClient-past"));
        registry.addOrganization(org);

        SkillGenerator.GenerationResult result = generator.generateAll(TIMESTAMP);

        // navigate-clients still generated (past clients are still clients)
        assertTrue(result.skills().containsKey("navigate-clients.yaml"));
        // pipeline-tracker should NOT be generated (only past clients)
        assertFalse(result.skills().containsKey("pipeline-tracker.yaml"));
    }
}
