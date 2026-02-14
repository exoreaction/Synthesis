package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.skills.SkillGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the skill generation integration (LearnCommand logic).
 * Tests the full pipeline: registry -> generator -> skill files.
 */
class LearnCommandTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create .synthesis directory (simulating initialized workspace)
        Files.createDirectories(tempDir.resolve(".synthesis/index"));
        Files.createDirectories(tempDir.resolve(".synthesis/reports"));

        // Create default config
        Files.writeString(tempDir.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: test-workspace\n  type: general\n");
    }

    @Test
    void fullPipeline_orgToSkills() throws IOException {
        // Set up organizations
        OrganizationRegistry registry = new OrganizationRegistry(tempDir);
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        org.addClient(new Client("Acme", "TestCo",
                tempDir.resolve("acme"), ClientStatus.ACTIVE, "Acme"));
        org.addProduct(new Product("product-x", "TestCo",
                tempDir.resolve("px")));
        registry.addOrganization(org);
        registry.setLastScanTime(Instant.now());
        registry.save();

        // Generate skills
        registry = new OrganizationRegistry(tempDir);
        registry.load();

        SkillGenerator generator = new SkillGenerator(tempDir, registry);
        SkillGenerator.GenerationResult result = generator.generateAll();

        // Verify results
        assertTrue(result.totalFiles() >= 4,
                "Should generate at least 4 skills (workspace, org, navigate, pipeline)");
        assertTrue(result.skills().containsKey("workspace-context.yaml"));
        assertTrue(result.skills().containsKey("organization-testco.yaml"));
        assertTrue(result.skills().containsKey("navigate-clients.yaml"));
        assertTrue(result.skills().containsKey("pipeline-tracker.yaml"));
        assertTrue(result.skills().containsKey("proof-points.yaml"));

        // Verify files exist and contain valid YAML
        for (String filename : result.skills().keySet()) {
            Path file = result.skillsDir().resolve(filename);
            assertTrue(Files.exists(file));

            String content = Files.readString(file);
            assertTrue(content.contains("name:"));
            assertTrue(content.contains("version: 1.0.0"));
            assertTrue(content.contains("instructions: |"));
        }
    }

    @Test
    void fullPipeline_multipleOrgs() throws IOException {
        OrganizationRegistry registry = new OrganizationRegistry(tempDir);

        Organization org1 = new Organization("OrgA", OrganizationType.COMPANY,
                tempDir.resolve("OrgA"));
        org1.addClient(new Client("Client1", "OrgA",
                tempDir.resolve("c1"), ClientStatus.ACTIVE, "Client1"));
        registry.addOrganization(org1);

        Organization org2 = new Organization("OrgB", OrganizationType.FOUNDATION,
                tempDir.resolve("OrgB"));
        org2.addProduct(new Product("framework-x", "OrgB",
                tempDir.resolve("fx")));
        registry.addOrganization(org2);

        registry.setLastScanTime(Instant.now());
        registry.save();

        // Reload and generate
        registry = new OrganizationRegistry(tempDir);
        registry.load();

        SkillGenerator generator = new SkillGenerator(tempDir, registry);
        SkillGenerator.GenerationResult result = generator.generateAll();

        assertTrue(result.skills().containsKey("organization-orga.yaml"));
        assertTrue(result.skills().containsKey("organization-orgb.yaml"));
        assertTrue(result.skills().containsKey("workspace-context.yaml"));

        // Workspace context should reference both orgs
        String wsContent = Files.readString(
                result.skillsDir().resolve("workspace-context.yaml"));
        assertTrue(wsContent.contains("OrgA"));
        assertTrue(wsContent.contains("OrgB"));
        assertTrue(wsContent.contains("Organizations (2)"));
    }

    @Test
    void fullPipeline_emptyRegistry_noSkills() throws IOException {
        OrganizationRegistry registry = new OrganizationRegistry(tempDir);
        registry.setLastScanTime(Instant.now());
        registry.save();

        // Reload
        registry = new OrganizationRegistry(tempDir);
        registry.load();

        SkillGenerator generator = new SkillGenerator(tempDir, registry);
        SkillGenerator.GenerationResult result = generator.generateAll();

        assertEquals(0, result.totalFiles());
    }

    @Test
    void fullPipeline_orgWithAllClientStatuses() throws IOException {
        OrganizationRegistry registry = new OrganizationRegistry(tempDir);
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));

        org.addClient(new Client("ActiveCo", "TestCo",
                tempDir.resolve("a"), ClientStatus.ACTIVE, "ActiveCo"));
        org.addClient(new Client("SignedCo", "TestCo",
                tempDir.resolve("s"), ClientStatus.SIGNED, "SignedCo"));
        org.addClient(new Client("OpportunityCo", "TestCo",
                tempDir.resolve("o"), ClientStatus.OPPORTUNITY, "opportunity-OpportunityCo"));
        org.addClient(new Client("PastCo", "TestCo",
                tempDir.resolve("p"), ClientStatus.PAST, "PastCo-past"));

        registry.addOrganization(org);
        registry.setLastScanTime(Instant.now());
        registry.save();

        // Reload and generate
        registry = new OrganizationRegistry(tempDir);
        registry.load();

        SkillGenerator generator = new SkillGenerator(tempDir, registry);
        SkillGenerator.GenerationResult result = generator.generateAll();

        // Check org skill contains all client status sections
        String orgContent = Files.readString(
                result.skillsDir().resolve("organization-testco.yaml"));
        assertTrue(orgContent.contains("Active Clients"));
        assertTrue(orgContent.contains("Signed Clients"));
        assertTrue(orgContent.contains("Opportunity Clients"));
        assertTrue(orgContent.contains("Past Clients"));

        // Navigate clients should have all clients
        String navContent = Files.readString(
                result.skillsDir().resolve("navigate-clients.yaml"));
        assertTrue(navContent.contains("ActiveCo"));
        assertTrue(navContent.contains("SignedCo"));
        assertTrue(navContent.contains("OpportunityCo"));
        assertTrue(navContent.contains("PastCo"));
    }

    @Test
    void fullPipeline_regeneration_overwrites() throws IOException {
        OrganizationRegistry registry = new OrganizationRegistry(tempDir);
        Organization org = new Organization("TestCo", OrganizationType.COMPANY,
                tempDir.resolve("TestCo"));
        registry.addOrganization(org);
        registry.setLastScanTime(Instant.now());
        registry.save();

        // First generation
        registry = new OrganizationRegistry(tempDir);
        registry.load();
        SkillGenerator generator = new SkillGenerator(tempDir, registry);
        generator.generateAll();

        // Add a client and regenerate
        registry = new OrganizationRegistry(tempDir);
        registry.load();
        registry.getOrganizations().get(0).addClient(
                new Client("NewClient", "TestCo",
                        tempDir.resolve("nc"), ClientStatus.ACTIVE, "NewClient"));
        registry.save();

        // Regenerate
        registry = new OrganizationRegistry(tempDir);
        registry.load();
        generator = new SkillGenerator(tempDir, registry);
        SkillGenerator.GenerationResult result = generator.generateAll();

        // Should include the new client
        String orgContent = Files.readString(
                result.skillsDir().resolve("organization-testco.yaml"));
        assertTrue(orgContent.contains("NewClient"));
    }
}
