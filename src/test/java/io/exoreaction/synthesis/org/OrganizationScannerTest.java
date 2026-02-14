package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrganizationScanner} auto-discovery.
 */
class OrganizationScannerTest {

    @TempDir
    Path tempDir;

    private OrganizationScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new OrganizationScanner(tempDir);
    }

    // --- Confidence scoring ---

    @Test
    void computeConfidence_fullOrg_highScore() throws IOException {
        Path orgDir = createOrgDirectory("TestCompany",
                true, true, true, true, true);

        int confidence = scanner.computeConfidence(orgDir);
        assertTrue(confidence >= 8, "Full org should have high confidence, got: " + confidence);
    }

    @Test
    void computeConfidence_minimalOrg_meetsThreshold() throws IOException {
        // Just clients/ and business/ = 4
        Path orgDir = tempDir.resolve("MinimalOrg");
        Files.createDirectories(orgDir.resolve("clients"));
        Files.createDirectories(orgDir.resolve("business"));

        int confidence = scanner.computeConfidence(orgDir);
        assertTrue(confidence >= 3, "Minimal org should meet threshold, got: " + confidence);
    }

    @Test
    void computeConfidence_emptyDir_belowThreshold() throws IOException {
        Path emptyDir = tempDir.resolve("EmptyDir");
        Files.createDirectories(emptyDir);

        int confidence = scanner.computeConfidence(emptyDir);
        assertTrue(confidence < 3, "Empty dir should be below threshold, got: " + confidence);
    }

    @Test
    void computeConfidence_codebaseIndex_strongSignal() throws IOException {
        Path dir = tempDir.resolve("WithIndex");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("CODEBASE-INDEX.md"), "# Codebase\n");

        int confidence = scanner.computeConfidence(dir);
        assertTrue(confidence >= 3, "CODEBASE-INDEX.md should be strong signal, got: " + confidence);
    }

    // --- Organization discovery ---

    @Test
    void scan_discoversMultipleOrgs() throws IOException {
        createOrgDirectory("CompanyA", true, true, true, true, false);
        createOrgDirectory("CompanyB", true, false, true, true, false);

        OrganizationRegistry registry = scanner.scan();

        assertTrue(registry.hasOrganizations());
        assertTrue(registry.getOrganizations().size() >= 2);
        assertTrue(registry.findOrganization("CompanyA").isPresent());
        assertTrue(registry.findOrganization("CompanyB").isPresent());
    }

    @Test
    void scan_skipsArchiveAndPersonal() throws IOException {
        Files.createDirectories(tempDir.resolve("archive/old-stuff"));
        Files.createDirectories(tempDir.resolve("personal/events"));
        createOrgDirectory("RealOrg", true, true, true, true, false);

        OrganizationRegistry registry = scanner.scan();

        assertFalse(registry.findOrganization("archive").isPresent());
        assertFalse(registry.findOrganization("personal").isPresent());
        assertTrue(registry.findOrganization("RealOrg").isPresent());
    }

    @Test
    void scan_skipsHiddenDirectories() throws IOException {
        Files.createDirectories(tempDir.resolve(".hidden/clients"));
        Files.createDirectories(tempDir.resolve(".synthesis"));
        createOrgDirectory("VisibleOrg", true, true, true, true, false);

        OrganizationRegistry registry = scanner.scan();

        assertFalse(registry.findOrganization(".hidden").isPresent());
        assertFalse(registry.findOrganization(".synthesis").isPresent());
    }

    @Test
    void scan_skipsBelowThreshold() throws IOException {
        Path simple = tempDir.resolve("SimpleDir");
        Files.createDirectories(simple);
        // No org signals

        OrganizationRegistry registry = scanner.scan();
        assertFalse(registry.findOrganization("SimpleDir").isPresent());
    }

    @Test
    void scan_setsTimestamp() throws IOException {
        createOrgDirectory("TestOrg", true, true, true, true, false);

        OrganizationRegistry registry = scanner.scan();
        assertNotNull(registry.getLastScanTime());
    }

    // --- Client discovery ---

    @Test
    void discoverClients_detectsActiveClients() throws IOException {
        Path orgDir = createOrgDirectory("TestOrg", true, false, true, false, false);
        Path clientsDir = orgDir.resolve("clients");
        Files.createDirectories(clientsDir.resolve("Elprint"));
        Files.createDirectories(clientsDir.resolve("Opplysningen-1881"));

        List<Client> clients = scanner.discoverClients(clientsDir, "TestOrg");

        assertEquals(2, clients.size());
        assertTrue(clients.stream().anyMatch(c -> c.getName().equals("Elprint") && c.getStatus() == ClientStatus.ACTIVE));
        assertTrue(clients.stream().anyMatch(c -> c.getName().equals("Opplysningen-1881") && c.getStatus() == ClientStatus.ACTIVE));
    }

    @Test
    void discoverClients_detectsOpportunities() throws IOException {
        Path orgDir = createOrgDirectory("TestOrg", true, false, true, false, false);
        Path clientsDir = orgDir.resolve("clients");
        Files.createDirectories(clientsDir.resolve("opportunity-SpareBank1"));
        Files.createDirectories(clientsDir.resolve("opportunity-Mynder"));

        List<Client> clients = scanner.discoverClients(clientsDir, "TestOrg");

        assertEquals(2, clients.size());
        assertTrue(clients.stream().allMatch(c -> c.getStatus() == ClientStatus.OPPORTUNITY));
        assertTrue(clients.stream().anyMatch(c -> c.getName().equals("SpareBank1")));
        assertTrue(clients.stream().anyMatch(c -> c.getName().equals("Mynder")));
    }

    @Test
    void discoverClients_detectsPast() throws IOException {
        Path orgDir = createOrgDirectory("TestOrg", true, false, true, false, false);
        Path clientsDir = orgDir.resolve("clients");
        Files.createDirectories(clientsDir.resolve("Entra-past"));
        Files.createDirectories(clientsDir.resolve("CatalystOne-past"));

        List<Client> clients = scanner.discoverClients(clientsDir, "TestOrg");

        assertEquals(2, clients.size());
        assertTrue(clients.stream().allMatch(c -> c.getStatus() == ClientStatus.PAST));
    }

    @Test
    void discoverClients_skipsArchiveAndScreenshots() throws IOException {
        Path orgDir = createOrgDirectory("TestOrg", true, false, true, false, false);
        Path clientsDir = orgDir.resolve("clients");
        Files.createDirectories(clientsDir.resolve("Elprint"));
        Files.createDirectories(clientsDir.resolve("archive"));
        Files.createDirectories(clientsDir.resolve("screenshots"));

        List<Client> clients = scanner.discoverClients(clientsDir, "TestOrg");

        assertEquals(1, clients.size());
        assertEquals("Elprint", clients.get(0).getName());
    }

    @Test
    void discoverClients_sortsByStatusThenName() throws IOException {
        Path orgDir = createOrgDirectory("TestOrg", true, false, true, false, false);
        Path clientsDir = orgDir.resolve("clients");
        Files.createDirectories(clientsDir.resolve("opportunity-Zzz"));
        Files.createDirectories(clientsDir.resolve("Aaa"));
        Files.createDirectories(clientsDir.resolve("Bbb-past"));

        List<Client> clients = scanner.discoverClients(clientsDir, "TestOrg");

        assertEquals(3, clients.size());
        // Active first
        assertEquals("Aaa", clients.get(0).getName());
        assertEquals(ClientStatus.ACTIVE, clients.get(0).getStatus());
        // Opportunity second
        assertEquals("Zzz", clients.get(1).getName());
        assertEquals(ClientStatus.OPPORTUNITY, clients.get(1).getStatus());
        // Past last
        assertEquals("Bbb", clients.get(2).getName());
        assertEquals(ClientStatus.PAST, clients.get(2).getStatus());
    }

    // --- Product discovery ---

    @Test
    void discoverProducts_detectsAll() throws IOException {
        Path orgDir = createOrgDirectory("TestOrg", true, false, false, true, false);
        Path productsDir = orgDir.resolve("products");
        Files.createDirectories(productsDir.resolve("workshop"));
        Files.createDirectories(productsDir.resolve("xorcery-aaa"));

        List<Product> products = scanner.discoverProducts(productsDir, "TestOrg");

        assertEquals(2, products.size());
        assertTrue(products.stream().anyMatch(p -> p.getName().equals("workshop")));
        assertTrue(products.stream().anyMatch(p -> p.getName().equals("xorcery-aaa")));
    }

    // --- Type detection ---

    @Test
    void detectType_companyWithClients() throws IOException {
        Path dir = tempDir.resolve("TestCo");
        Files.createDirectories(dir.resolve("clients"));

        assertEquals(OrganizationType.COMPANY, scanner.detectType(dir, "TestCo"));
    }

    @Test
    void detectType_foundationWithFrameworks() throws IOException {
        Path dir = tempDir.resolve("Cantara");
        Files.createDirectories(dir.resolve("frameworks"));

        assertEquals(OrganizationType.FOUNDATION, scanner.detectType(dir, "Cantara"));
    }

    @Test
    void detectType_conceptWithTheory() throws IOException {
        Path dir = tempDir.resolve("Merkabit");
        Files.createDirectories(dir.resolve("theory"));

        assertEquals(OrganizationType.CONCEPT, scanner.detectType(dir, "Merkabit"));
    }

    @Test
    void detectType_holding() throws IOException {
        Path dir = tempDir.resolve("T-Hex");
        Files.createDirectories(dir);

        assertEquals(OrganizationType.HOLDING, scanner.detectType(dir, "T-Hex"));
    }

    // --- Description extraction ---

    @Test
    void extractDescription_fromReadme() throws IOException {
        Path readme = tempDir.resolve("README.md");
        Files.writeString(readme, """
                # eXOReaction - Skill-Driven Development

                Consulting company for SDD methodology training and consulting.

                ---
                """);

        String desc = scanner.extractDescription(readme);
        assertNotNull(desc);
        assertTrue(desc.contains("Consulting company"));
    }

    @Test
    void extractDescription_stripsMarkdownFormatting() throws IOException {
        Path readme = tempDir.resolve("README.md");
        Files.writeString(readme, """
                # Title

                **Bold text** with *italic* content.
                """);

        String desc = scanner.extractDescription(readme);
        assertNotNull(desc);
        assertTrue(desc.contains("Bold text"));
        assertFalse(desc.contains("**"));
    }

    @Test
    void extractDescription_truncatesLongLines() throws IOException {
        Path readme = tempDir.resolve("README.md");
        Files.writeString(readme, "# Title\n\n" + "x".repeat(200));

        String desc = scanner.extractDescription(readme);
        assertNotNull(desc);
        assertTrue(desc.length() <= 150);
        assertTrue(desc.endsWith("..."));
    }

    @Test
    void extractDescription_returnsNullForEmptyReadme() throws IOException {
        Path readme = tempDir.resolve("README.md");
        Files.writeString(readme, "# Title\n\n---\n");

        String desc = scanner.extractDescription(readme);
        assertNull(desc);
    }

    // --- Codebase path extraction ---

    @Test
    void extractCodebasePaths_findsSourcePaths() throws IOException {
        Path codebaseIndex = tempDir.resolve("CODEBASE-INDEX.md");
        Files.writeString(codebaseIndex, """
                # Codebase Index

                Main repo: /src/exoreaction/lib-pcb/
                Client: /src/elprint/
                """);

        List<String> paths = scanner.extractCodebasePaths(codebaseIndex);
        assertTrue(paths.size() >= 2);
    }

    // --- Keyword generation ---

    @Test
    void generateKeywords_includesAllEntities() {
        Organization org = new Organization("TestOrg", OrganizationType.COMPANY,
                tempDir.resolve("TestOrg"));
        org.addClient(new Client("ClientA", "TestOrg",
                tempDir.resolve("a"), ClientStatus.ACTIVE, "ClientA"));
        org.addProduct(new Product("ProductX", "TestOrg",
                tempDir.resolve("b")));

        List<String> keywords = scanner.generateKeywords(org);
        assertTrue(keywords.contains("TestOrg"));
        assertTrue(keywords.contains("ClientA"));
        assertTrue(keywords.contains("ProductX"));
    }

    // --- Helper methods ---

    private Path createOrgDirectory(String name, boolean readme, boolean codebaseIndex,
                                     boolean clients, boolean products, boolean marketing)
            throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);

        if (readme) {
            Files.writeString(dir.resolve("README.md"),
                    "# " + name + "\n\nDescription of " + name + ".\n");
        }
        if (codebaseIndex) {
            Files.writeString(dir.resolve("CODEBASE-INDEX.md"),
                    "# Codebase Index\n\nRepo: /src/" + name.toLowerCase() + "/\n");
        }
        if (clients) {
            Files.createDirectories(dir.resolve("clients"));
        }
        if (products) {
            Files.createDirectories(dir.resolve("products"));
        }
        if (marketing) {
            Files.createDirectories(dir.resolve("marketing"));
        }

        // Always add business for confidence
        Files.createDirectories(dir.resolve("business"));

        return dir;
    }
}
