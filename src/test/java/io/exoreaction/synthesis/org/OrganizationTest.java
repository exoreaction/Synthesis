package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Organization} entity.
 */
class OrganizationTest {

    private Organization org;

    @BeforeEach
    void setUp() {
        org = new Organization("eXOReaction", OrganizationType.COMPANY,
                Path.of("/home/totto/Documents/eXOReaction"));
    }

    @Test
    void constructor_setsBasicFields() {
        assertEquals("eXOReaction", org.getName());
        assertEquals(OrganizationType.COMPANY, org.getType());
        assertEquals("/home/totto/Documents/eXOReaction", org.getBasePath());
        assertTrue(org.getClients().isEmpty());
        assertTrue(org.getProducts().isEmpty());
        assertTrue(org.getCodebasePaths().isEmpty());
    }

    @Test
    void addClient_addsToList() {
        Client client = new Client("Elprint", "eXOReaction",
                Path.of("/home/totto/Documents/eXOReaction/clients/Elprint"),
                ClientStatus.ACTIVE, "Elprint");
        org.addClient(client);

        assertEquals(1, org.getClients().size());
        assertEquals("Elprint", org.getClients().get(0).getName());
    }

    @Test
    void addProduct_addsToList() {
        Product product = new Product("Workshop", "eXOReaction",
                Path.of("/home/totto/Documents/eXOReaction/products/workshop"));
        org.addProduct(product);

        assertEquals(1, org.getProducts().size());
        assertEquals("Workshop", org.getProducts().get(0).getName());
    }

    @Test
    void addCodebasePath_addsOnce() {
        org.addCodebasePath("/home/totto/src/exoreaction");
        org.addCodebasePath("/home/totto/src/exoreaction"); // duplicate
        org.addCodebasePath("/home/totto/src/elprint");

        assertEquals(2, org.getCodebasePaths().size());
    }

    @Test
    void containsPath_matchesDirectChildren() {
        assertTrue(org.containsPath(Path.of("/home/totto/Documents/eXOReaction/README.md")));
        assertTrue(org.containsPath(Path.of("/home/totto/Documents/eXOReaction/clients/Elprint/file.md")));
    }

    @Test
    void containsPath_doesNotMatchSiblings() {
        assertFalse(org.containsPath(Path.of("/home/totto/Documents/Quadim/README.md")));
        assertFalse(org.containsPath(Path.of("/home/totto/Documents/README.md")));
    }

    @Test
    void getClientsByStatus_filtersCorrectly() {
        org.addClient(new Client("Elprint", "eXOReaction",
                Path.of("/tmp/a"), ClientStatus.ACTIVE, "Elprint"));
        org.addClient(new Client("Entra", "eXOReaction",
                Path.of("/tmp/b"), ClientStatus.PAST, "Entra-past"));
        org.addClient(new Client("SpareBank1", "eXOReaction",
                Path.of("/tmp/c"), ClientStatus.OPPORTUNITY, "opportunity-SpareBank1"));

        assertEquals(1, org.getClientsByStatus(ClientStatus.ACTIVE).size());
        assertEquals(1, org.getClientsByStatus(ClientStatus.PAST).size());
        assertEquals(1, org.getClientsByStatus(ClientStatus.OPPORTUNITY).size());
        assertEquals(0, org.getClientsByStatus(ClientStatus.SIGNED).size());
    }

    @Test
    void findClient_caseInsensitive() {
        org.addClient(new Client("SpareBank1", "eXOReaction",
                Path.of("/tmp/c"), ClientStatus.OPPORTUNITY, "opportunity-SpareBank1"));

        assertTrue(org.findClient("SpareBank1").isPresent());
        assertTrue(org.findClient("sparebank1").isPresent());
        assertTrue(org.findClient("SPAREBANK1").isPresent());
        assertFalse(org.findClient("NonExistent").isPresent());
    }

    @Test
    void resolveClient_findsCorrectClient() {
        Client client = new Client("Elprint", "eXOReaction",
                Path.of("/home/totto/Documents/eXOReaction/clients/Elprint"),
                ClientStatus.ACTIVE, "Elprint");
        org.addClient(client);

        Optional<Client> resolved = org.resolveClient(
                Path.of("/home/totto/Documents/eXOReaction/clients/Elprint/project/file.java"));
        assertTrue(resolved.isPresent());
        assertEquals("Elprint", resolved.get().getName());
    }

    @Test
    void resolveClient_returnsEmptyForNonClientPath() {
        Client client = new Client("Elprint", "eXOReaction",
                Path.of("/home/totto/Documents/eXOReaction/clients/Elprint"),
                ClientStatus.ACTIVE, "Elprint");
        org.addClient(client);

        Optional<Client> resolved = org.resolveClient(
                Path.of("/home/totto/Documents/eXOReaction/business/strategy.md"));
        assertFalse(resolved.isPresent());
    }

    @Test
    void noArgConstructor_createsValidInstance() {
        Organization emptyOrg = new Organization();
        assertNotNull(emptyOrg.getClients());
        assertNotNull(emptyOrg.getProducts());
        assertNotNull(emptyOrg.getCodebasePaths());
        assertNotNull(emptyOrg.getKeywords());
        assertNotNull(emptyOrg.getMetadata());
    }

    @Test
    void setDescription_works() {
        org.setDescription("Consulting company");
        assertEquals("Consulting company", org.getDescription());
    }

    @Test
    void toString_includesNameAndType() {
        String str = org.toString();
        assertTrue(str.contains("eXOReaction"));
        assertTrue(str.contains("COMPANY"));
    }
}
