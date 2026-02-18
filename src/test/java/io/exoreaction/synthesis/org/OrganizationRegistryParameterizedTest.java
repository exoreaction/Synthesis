package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for OrganizationRegistry — add, find, resolve, keyword index,
 * clear, save/load, and workspace resolution.
 */
class OrganizationRegistryParameterizedTest {

    @TempDir
    Path workspaceRoot;

    private OrganizationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new OrganizationRegistry(workspaceRoot);
    }

    // --- Initial state ---

    @Test
    void newRegistry_isEmpty() {
        assertFalse(registry.hasOrganizations());
        assertTrue(registry.getOrganizations().isEmpty());
        assertNull(registry.getLastScanTime());
    }

    // --- addOrganization + findOrganization ---

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void addOrganization_allTypes_canBeFoundByName(OrganizationType type) {
        String name = "TestOrg-" + type.name();
        registry.addOrganization(new Organization(name, type, workspaceRoot.resolve(name)));

        Optional<Organization> found = registry.findOrganization(name);
        assertTrue(found.isPresent(), "Should find org by name");
        assertEquals(type, found.get().getType());
    }

    @ParameterizedTest
    @CsvSource({
        "MyCompany,  mycompany",
        "MyCompany,  MYCOMPANY",
        "MyCompany,  MyCompany"
    })
    void findOrganization_caseInsensitive(String orgName, String searchName) {
        registry.addOrganization(
                new Organization(orgName, OrganizationType.COMPANY, workspaceRoot.resolve(orgName)));
        assertTrue(registry.findOrganization(searchName).isPresent(),
                "findOrganization should be case-insensitive");
    }

    @Test
    void findOrganization_nonExistent_returnsEmpty() {
        registry.addOrganization(
                new Organization("Existing", OrganizationType.COMPANY, workspaceRoot.resolve("Existing")));
        assertTrue(registry.findOrganization("NonExistent").isEmpty());
    }

    // --- Multiple organizations ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void addMultipleOrganizations_allPresent(int count) {
        for (int i = 0; i < count; i++) {
            registry.addOrganization(
                    new Organization("Org" + i, OrganizationType.COMPANY, workspaceRoot.resolve("Org" + i)));
        }
        assertEquals(count, registry.getOrganizations().size());
        assertTrue(registry.hasOrganizations());
    }

    // --- clear ---

    @Test
    void clear_removesAllOrganizationsAndTime() {
        registry.addOrganization(
                new Organization("A", OrganizationType.COMPANY, workspaceRoot.resolve("A")));
        registry.setLastScanTime(Instant.now());

        registry.clear();

        assertFalse(registry.hasOrganizations());
        assertNull(registry.getLastScanTime());
        assertTrue(registry.getOrganizations().isEmpty());
    }

    // --- lastScanTime ---

    @Test
    void setLastScanTime_roundTrip() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        registry.setLastScanTime(now);
        assertEquals(now, registry.getLastScanTime());
    }

    // --- findClient (cross-organization) ---

    @Test
    void findClient_acrossOrganizations_findsCorrectOne() {
        Organization org1 = new Organization("OrgA", OrganizationType.COMPANY,
                workspaceRoot.resolve("OrgA"));
        Organization org2 = new Organization("OrgB", OrganizationType.FOUNDATION,
                workspaceRoot.resolve("OrgB"));

        org1.addClient(new Client("ClientX", "OrgA",
                workspaceRoot.resolve("OrgA/clients/ClientX"), ClientStatus.ACTIVE, "ClientX"));
        org2.addClient(new Client("ClientY", "OrgB",
                workspaceRoot.resolve("OrgB/clients/ClientY"), ClientStatus.ACTIVE, "ClientY"));

        registry.addOrganization(org1);
        registry.addOrganization(org2);

        assertTrue(registry.findClient("ClientX").isPresent(), "Should find ClientX");
        assertTrue(registry.findClient("ClientY").isPresent(), "Should find ClientY");
        assertTrue(registry.findClient("NonExistent").isEmpty());
    }

    // --- getAllClients ---

    @Test
    void getAllClients_returnsClientsFromAllOrganizations() {
        Organization org1 = new Organization("Org1", OrganizationType.COMPANY,
                workspaceRoot.resolve("Org1"));
        Organization org2 = new Organization("Org2", OrganizationType.COMPANY,
                workspaceRoot.resolve("Org2"));

        org1.addClient(new Client("ClientA", "Org1",
                workspaceRoot.resolve("Org1/a"), ClientStatus.ACTIVE, "ClientA"));
        org1.addClient(new Client("ClientB", "Org1",
                workspaceRoot.resolve("Org1/b"), ClientStatus.ACTIVE, "ClientB"));
        org2.addClient(new Client("ClientC", "Org2",
                workspaceRoot.resolve("Org2/c"), ClientStatus.ACTIVE, "ClientC"));

        registry.addOrganization(org1);
        registry.addOrganization(org2);

        List<Client> all = registry.getAllClients();
        assertEquals(3, all.size(), "Should return all 3 clients across orgs");
    }

    @Test
    void getAllClients_noOrganizations_returnsEmpty() {
        assertTrue(registry.getAllClients().isEmpty());
    }

    // --- buildKeywordIndex ---

    @Test
    void buildKeywordIndex_containsOrgName() {
        registry.addOrganization(
                new Organization("Alpha", OrganizationType.COMPANY, workspaceRoot.resolve("Alpha")));
        Map<String, String> index = registry.buildKeywordIndex();
        assertTrue(index.containsKey("alpha"), "Index should contain org name (lowercase)");
        assertEquals("Alpha", index.get("alpha"));
    }

    @Test
    void buildKeywordIndex_containsClientNames() {
        Organization org = new Organization("TestOrg", OrganizationType.COMPANY,
                workspaceRoot.resolve("TestOrg"));
        org.addClient(new Client("ClientZ", "TestOrg",
                workspaceRoot.resolve("TestOrg/clients/ClientZ"), ClientStatus.ACTIVE, "ClientZ"));
        registry.addOrganization(org);

        Map<String, String> index = registry.buildKeywordIndex();
        assertTrue(index.containsKey("clientz"), "Index should contain client name (lowercase)");
    }

    @Test
    void buildKeywordIndex_emptyRegistry_returnsEmptyMap() {
        assertTrue(registry.buildKeywordIndex().isEmpty());
    }

    // --- getOrgsFilePath ---

    @Test
    void getOrgsFilePath_containsSynthesisDirectory() {
        Path orgsFile = registry.getOrgsFilePath();
        assertTrue(orgsFile.toString().contains(".synthesis"),
                "orgsFile path should contain '.synthesis'");
        assertTrue(orgsFile.getFileName().toString().endsWith(".json"),
                "orgsFile should be a JSON file");
    }

    // --- save and load round-trip ---

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void saveLoad_roundTrip_preservesOrgType(OrganizationType type) throws IOException {
        String name = "RoundTripOrg";
        registry.addOrganization(
                new Organization(name, type, workspaceRoot.resolve(name)));
        registry.setLastScanTime(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        registry.save();

        OrganizationRegistry loaded = new OrganizationRegistry(workspaceRoot);
        loaded.load();

        Optional<Organization> found = loaded.findOrganization(name);
        assertTrue(found.isPresent(), "Organization should survive save/load");
        assertEquals(type, found.get().getType(), "Type should be preserved");
    }

    @Test
    void load_nonExistentFile_doesNotThrow() throws IOException {
        // No .synthesis/organizations.json file → should just be empty
        registry.load();
        assertFalse(registry.hasOrganizations());
    }
}
