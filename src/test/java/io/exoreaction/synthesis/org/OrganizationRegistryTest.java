package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrganizationRegistry} persistence and lookup.
 */
class OrganizationRegistryTest {

    @TempDir
    Path tempDir;

    private OrganizationRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tempDir.resolve(".synthesis"));
        registry = new OrganizationRegistry(tempDir);
    }

    @Test
    void emptyRegistry_hasNoOrganizations() {
        assertFalse(registry.hasOrganizations());
        assertTrue(registry.getOrganizations().isEmpty());
    }

    @Test
    void addOrganization_makesItFindable() {
        Organization org = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        registry.addOrganization(org);

        assertTrue(registry.hasOrganizations());
        assertEquals(1, registry.getOrganizations().size());
    }

    @Test
    void findOrganization_caseInsensitive() {
        Organization org = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        registry.addOrganization(org);

        assertTrue(registry.findOrganization("eXOReaction").isPresent());
        assertTrue(registry.findOrganization("exoreaction").isPresent());
        assertTrue(registry.findOrganization("EXOREACTION").isPresent());
        assertFalse(registry.findOrganization("NonExistent").isPresent());
    }

    @Test
    void findClient_searchesAllOrgs() {
        Organization org1 = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        org1.addClient(new Client("Elprint", "eXOReaction",
                tempDir.resolve("eXOReaction/clients/Elprint"),
                ClientStatus.ACTIVE, "Elprint"));
        registry.addOrganization(org1);

        Organization org2 = new Organization("Quadim", OrganizationType.COMPANY,
                tempDir.resolve("Quadim"));
        org2.addClient(new Client("CatalystOne", "Quadim",
                tempDir.resolve("Quadim/clients/CatalystOne"),
                ClientStatus.ACTIVE, "CatalystOne"));
        registry.addOrganization(org2);

        assertTrue(registry.findClient("Elprint").isPresent());
        assertTrue(registry.findClient("CatalystOne").isPresent());
        assertFalse(registry.findClient("NonExistent").isPresent());
    }

    @Test
    void getAllClients_returnsAllAcrossOrgs() {
        Organization org1 = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        org1.addClient(new Client("Elprint", "eXOReaction",
                tempDir.resolve("a"), ClientStatus.ACTIVE, "Elprint"));
        org1.addClient(new Client("Entra", "eXOReaction",
                tempDir.resolve("b"), ClientStatus.PAST, "Entra-past"));
        registry.addOrganization(org1);

        Organization org2 = new Organization("Quadim", OrganizationType.COMPANY,
                tempDir.resolve("Quadim"));
        org2.addClient(new Client("CatalystOne", "Quadim",
                tempDir.resolve("c"), ClientStatus.ACTIVE, "CatalystOne"));
        registry.addOrganization(org2);

        assertEquals(3, registry.getAllClients().size());
    }

    @Test
    void resolveOrganization_matchesFileToOrg() throws IOException {
        Path orgDir = tempDir.resolve("eXOReaction");
        Files.createDirectories(orgDir);

        Organization org = new Organization("eXOReaction", OrganizationType.COMPANY, orgDir);
        registry.addOrganization(org);

        assertEquals("eXOReaction",
                registry.resolveOrganization(orgDir.resolve("business/strategy.md")));
        assertNull(registry.resolveOrganization(tempDir.resolve("Quadim/readme.md")));
    }

    @Test
    void resolveClient_matchesFileToClient() throws IOException {
        Path orgDir = tempDir.resolve("eXOReaction");
        Path clientDir = orgDir.resolve("clients/Elprint");
        Files.createDirectories(clientDir);

        Organization org = new Organization("eXOReaction", OrganizationType.COMPANY, orgDir);
        org.addClient(new Client("Elprint", "eXOReaction", clientDir,
                ClientStatus.ACTIVE, "Elprint"));
        registry.addOrganization(org);

        assertEquals("Elprint",
                registry.resolveClient(clientDir.resolve("project/file.java")));
        assertNull(registry.resolveClient(orgDir.resolve("business/strategy.md")));
    }

    @Test
    void buildKeywordIndex_includesAllEntities() {
        Organization org = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        org.setKeywords(List.of("SDD", "workshop"));
        org.addClient(new Client("SpareBank1", "eXOReaction",
                tempDir.resolve("a"), ClientStatus.OPPORTUNITY, "opportunity-SpareBank1"));
        org.addProduct(new Product("xorcery-aaa", "eXOReaction",
                tempDir.resolve("b")));
        registry.addOrganization(org);

        Map<String, String> index = registry.buildKeywordIndex();
        assertEquals("eXOReaction", index.get("exoreaction"));
        assertEquals("eXOReaction", index.get("sdd"));
        assertEquals("eXOReaction", index.get("workshop"));
        assertEquals("eXOReaction", index.get("sparebank1"));
        assertEquals("eXOReaction", index.get("xorcery-aaa"));
    }

    @Test
    void saveAndLoad_roundTrip() throws IOException {
        Organization org = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        org.setDescription("Consulting company for SDD");
        org.setKeywords(List.of("SDD", "workshop"));
        org.setCodebasePaths(List.of("/home/totto/src/exoreaction"));
        org.addClient(new Client("Elprint", "eXOReaction",
                tempDir.resolve("eXOReaction/clients/Elprint"),
                ClientStatus.ACTIVE, "Elprint"));
        org.addClient(new Client("SpareBank1", "eXOReaction",
                tempDir.resolve("eXOReaction/clients/opportunity-SpareBank1"),
                ClientStatus.OPPORTUNITY, "opportunity-SpareBank1"));
        org.addProduct(new Product("workshop", "eXOReaction",
                tempDir.resolve("eXOReaction/products/workshop")));
        registry.addOrganization(org);

        Organization org2 = new Organization("Cantara", OrganizationType.FOUNDATION,
                tempDir.resolve("Cantara"));
        org2.setDescription("Open source foundation");
        registry.addOrganization(org2);

        registry.setLastScanTime(Instant.parse("2026-02-14T12:00:00Z"));
        registry.save();

        // Load into fresh registry
        OrganizationRegistry loaded = new OrganizationRegistry(tempDir);
        loaded.load();

        assertEquals(2, loaded.getOrganizations().size());
        assertEquals(Instant.parse("2026-02-14T12:00:00Z"), loaded.getLastScanTime());

        Organization loadedOrg = loaded.findOrganization("eXOReaction").orElseThrow();
        assertEquals("eXOReaction", loadedOrg.getName());
        assertEquals(OrganizationType.COMPANY, loadedOrg.getType());
        assertEquals("Consulting company for SDD", loadedOrg.getDescription());
        assertEquals(2, loadedOrg.getClients().size());
        assertEquals(1, loadedOrg.getProducts().size());
        assertEquals(List.of("SDD", "workshop"), loadedOrg.getKeywords());
        assertEquals(List.of("/home/totto/src/exoreaction"), loadedOrg.getCodebasePaths());

        Client loadedElprint = loadedOrg.findClient("Elprint").orElseThrow();
        assertEquals(ClientStatus.ACTIVE, loadedElprint.getStatus());
        assertEquals("Elprint", loadedElprint.getDirectoryName());

        Client loadedSB = loadedOrg.findClient("SpareBank1").orElseThrow();
        assertEquals(ClientStatus.OPPORTUNITY, loadedSB.getStatus());
        assertEquals("opportunity-SpareBank1", loadedSB.getDirectoryName());

        Organization loadedCantara = loaded.findOrganization("Cantara").orElseThrow();
        assertEquals(OrganizationType.FOUNDATION, loadedCantara.getType());
    }

    @Test
    void load_noFile_startsEmpty() throws IOException {
        OrganizationRegistry fresh = new OrganizationRegistry(tempDir);
        fresh.load(); // No file exists
        assertFalse(fresh.hasOrganizations());
    }

    @Test
    void clear_removesAll() {
        registry.addOrganization(new Organization("A", OrganizationType.COMPANY, tempDir.resolve("A")));
        registry.setLastScanTime(Instant.now());
        assertTrue(registry.hasOrganizations());

        registry.clear();
        assertFalse(registry.hasOrganizations());
        assertNull(registry.getLastScanTime());
    }

    @Test
    void save_createsDirectory() throws IOException {
        Path deepDir = tempDir.resolve("deep/nested");
        Files.createDirectories(deepDir);
        OrganizationRegistry reg = new OrganizationRegistry(deepDir);
        reg.addOrganization(new Organization("Test", OrganizationType.OTHER, deepDir.resolve("Test")));
        reg.save(); // Should create .synthesis/ directory

        assertTrue(Files.exists(deepDir.resolve(".synthesis/organizations.json")));
    }

    @Test
    void specialCharactersInNames_roundTrip() throws IOException {
        Organization org = new Organization("eXOReaction AS (Norway)", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        org.setDescription("Description with \"quotes\" and \\backslashes");
        registry.addOrganization(org);
        registry.save();

        OrganizationRegistry loaded = new OrganizationRegistry(tempDir);
        loaded.load();

        Organization loadedOrg = loaded.findOrganization("eXOReaction AS (Norway)").orElseThrow();
        assertEquals("Description with \"quotes\" and \\backslashes", loadedOrg.getDescription());
    }
}
