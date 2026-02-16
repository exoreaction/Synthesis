package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientCodebaseResolver} auto-discovery system.
 */
class ClientCodebaseResolverTest {

    @TempDir
    Path tempDir;

    // =====================================================================
    // Layer 1: CODEBASE-INDEX.md Parsing Tests
    // =====================================================================

    @Test
    void parseCodebaseIndex_validClientSection_extractsMapping() throws IOException {
        String content = """
                # eXOReaction - Codebase Index

                ## Client Projects

                ### Elprint (25 repositories) - PCB/Printing Client

                **Location:** `/src/elprint/`
                **Domain:** PCB design, manufacturing
                """;

        Path indexFile = createTempFile(content);
        Organization org = createTestOrganization("eXOReaction", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertEquals(1, mappings.size());
        assertTrue(mappings.containsKey("Elprint"));

        ClientCodebaseResolver.CodebaseMapping mapping = mappings.get("Elprint");
        assertEquals("Elprint", mapping.getClientName());
        assertEquals(1, mapping.getCodebasePaths().size());
        assertTrue(mapping.getCodebasePaths().get(0).endsWith("/src/elprint"));
        assertEquals(0.5, mapping.getConfidence());
        assertTrue(mapping.getSignals().contains("CODEBASE-INDEX.md client section"));
    }

    @Test
    void parseCodebaseIndex_multipleClients_extractsAll() throws IOException {
        String content = """
                ## Client Projects

                ### Elprint (25 repositories) - PCB/Printing Client
                **Location:** `/src/elprint/`

                ### Entra (73 repositories) - Building Automation Client
                **Location:** `/src/entra/`

                ### Opplysningen (12 repositories) - Directory Services
                **Location:** `/src/opplysningen/`
                """;

        Path indexFile = createTempFile(content);
        Organization org = createTestOrganization("eXOReaction", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertEquals(3, mappings.size());
        assertTrue(mappings.containsKey("Elprint"));
        assertTrue(mappings.containsKey("Entra"));
        assertTrue(mappings.containsKey("Opplysningen"));
    }

    @Test
    void parseCodebaseIndex_clientWithoutLocation_skipped() throws IOException {
        String content = """
                ### Elprint (25 repositories) - PCB Client
                **Domain:** PCB design

                ### Entra (73 repositories) - Building Automation
                **Location:** `/src/entra/`
                """;

        Path indexFile = createTempFile(content);
        Organization org = createTestOrganization("eXOReaction", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertEquals(1, mappings.size());
        assertTrue(mappings.containsKey("Entra"));
        assertFalse(mappings.containsKey("Elprint"));
    }

    @Test
    void parseCodebaseIndex_emptyFile_returnsEmpty() throws IOException {
        Path indexFile = createTempFile("");
        Organization org = createTestOrganization("eXOReaction", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertTrue(mappings.isEmpty());
    }

    @Test
    void parseCodebaseIndex_noClientSections_returnsEmpty() throws IOException {
        String content = """
                # eXOReaction - Codebase Index

                ## Direct Projects

                This is some documentation without client sections.
                """;

        Path indexFile = createTempFile(content);
        Organization org = createTestOrganization("eXOReaction", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertTrue(mappings.isEmpty());
    }

    @Test
    void parseCodebaseIndex_locationWithBackticks_handlesCorrectly() throws IOException {
        String content = """
                ### Elprint (25 repositories) - PCB Client
                **Location:** `/src/elprint/`
                """;

        Path indexFile = createTempFile(content);
        Organization org = createTestOrganization("eXOReaction", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertEquals(1, mappings.size());
        String path = mappings.get("Elprint").getCodebasePaths().get(0);
        assertFalse(path.contains("`"));
    }

    @Test
    void parseCodebaseIndex_locationWithTildeExpansion_expandsToHome() throws IOException {
        String content = """
                ### Quadim (45 repositories) - SaaS Platform
                **Location:** `~/src/quadim/`
                """;

        Path indexFile = createTempFile(content);
        Organization org = createTestOrganization("Quadim", tempDir);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.parseCodebaseIndex(indexFile, org);

        assertEquals(1, mappings.size());
        String path = mappings.get("Quadim").getCodebasePaths().get(0);
        assertTrue(path.startsWith(System.getProperty("user.home")));
        assertTrue(path.endsWith("/src/quadim"));
    }

    // =====================================================================
    // resolveCodebasePath Tests
    // =====================================================================

    @ParameterizedTest
    @CsvSource({
            "/src/elprint/, /src/elprint",
            "/src/entra, /src/entra",
            "`/src/cantara/`, /src/cantara",
            "~/src/quadim/, /src/quadim"
    })
    void resolveCodebasePath_variousFormats_normalizesCorrectly(String input, String expectedSuffix) {
        String resolved = ClientCodebaseResolver.resolveCodebasePath(input);
        assertNotNull(resolved);
        assertTrue(resolved.endsWith(expectedSuffix),
                "Expected '" + resolved + "' to end with '" + expectedSuffix + "'");
    }

    @Test
    void resolveCodebasePath_withTilde_expandsToUserHome() {
        String result = ClientCodebaseResolver.resolveCodebasePath("~/src/test/");
        assertNotNull(result);
        assertTrue(result.startsWith(System.getProperty("user.home")));
        assertTrue(result.endsWith("/src/test"));
    }

    @Test
    void resolveCodebasePath_absolutePath_returnsAsIs() {
        String result = ClientCodebaseResolver.resolveCodebasePath("/absolute/path");
        assertEquals("/absolute/path", result);
    }

    @Test
    void resolveCodebasePath_emptyString_returnsNull() {
        assertNull(ClientCodebaseResolver.resolveCodebasePath(""));
    }

    @Test
    void resolveCodebasePath_null_returnsNull() {
        assertNull(ClientCodebaseResolver.resolveCodebasePath(null));
    }

    @Test
    void resolveCodebasePath_removesTrailingSlash() {
        String result = ClientCodebaseResolver.resolveCodebasePath("/src/elprint/");
        assertTrue(result.endsWith("/src/elprint"));
        assertFalse(result.endsWith("/src/elprint/"));
    }

    @Test
    void resolveCodebasePath_removesBackticks() {
        String result = ClientCodebaseResolver.resolveCodebasePath("`/src/test/`");
        assertFalse(result.contains("`"));
    }

    // =====================================================================
    // Layer 2: Organization Inheritance Tests
    // =====================================================================

    @Test
    void resolveFromOrganization_exactMatch_returnsMapping() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        org.setCodebasePaths(List.of(
                System.getProperty("user.home") + "/src/exoreaction",
                System.getProperty("user.home") + "/src/elprint"
        ));

        Client client = createTestClient("Elprint", "Elprint");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromOrganization(client, org);

        assertNotNull(mapping);
        assertEquals("Elprint", mapping.getClientName());
        assertEquals(1, mapping.getCodebasePaths().size());
        assertTrue(mapping.getCodebasePaths().get(0).contains("/src/elprint"));
        assertEquals(0.4, mapping.getConfidence());
    }

    @Test
    void resolveFromOrganization_partialMatch_returnsMapping() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        org.setCodebasePaths(List.of(
                System.getProperty("user.home") + "/src/elprint"
        ));

        // Client name contains "elprint" which should match "/src/elprint"
        // Note: matching is case-insensitive and checks if codebase name contains clean client name
        Client client = createTestClient("Elprint", "Elprint");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromOrganization(client, org);

        assertNotNull(mapping);
        assertEquals(1, mapping.getCodebasePaths().size());
        assertTrue(mapping.getCodebasePaths().get(0).contains("/src/elprint"));
    }

    @Test
    void resolveFromOrganization_noMatch_returnsNull() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        org.setCodebasePaths(List.of(
                System.getProperty("user.home") + "/src/other"
        ));

        Client client = createTestClient("Elprint", "Elprint");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromOrganization(client, org);

        assertNull(mapping);
    }

    @Test
    void resolveFromOrganization_emptyCodebasePaths_returnsNull() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        org.setCodebasePaths(List.of());

        Client client = createTestClient("Elprint", "Elprint");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromOrganization(client, org);

        assertNull(mapping);
    }

    @Test
    void resolveFromOrganization_opportunityPrefix_stripsBeforeMatching() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        org.setCodebasePaths(List.of(
                System.getProperty("user.home") + "/src/sparebank1"
        ));

        Client client = createTestClient("SpareBank1", "opportunity-SpareBank1");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromOrganization(client, org);

        assertNotNull(mapping);
    }

    @Test
    void resolveFromOrganization_pastSuffix_stripsBeforeMatching() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        org.setCodebasePaths(List.of(
                System.getProperty("user.home") + "/src/entra"
        ));

        Client client = createTestClient("Entra", "Entra-past");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromOrganization(client, org);

        assertNotNull(mapping);
    }

    // =====================================================================
    // Layer 3: Path Name Heuristics Tests
    // =====================================================================

    @Test
    void resolveFromHeuristics_directoryExists_returnsMapping() throws IOException {
        // Create a test directory structure
        Path srcDir = tempDir.resolve("src");
        Path clientDir = srcDir.resolve("elprint");
        Files.createDirectories(clientDir);

        Organization org = createTestOrganization("eXOReaction", tempDir);
        Client client = createTestClient("elprint", "Elprint");

        // Temporarily override System.getProperty for user.home
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            ClientCodebaseResolver.CodebaseMapping mapping =
                    ClientCodebaseResolver.resolveFromHeuristics(client, org);

            assertNotNull(mapping);
            assertEquals(0.3, mapping.getConfidence());
            assertTrue(mapping.getSignals().contains("Path name heuristic"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void resolveFromHeuristics_directoryDoesNotExist_returnsNull() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        Client client = createTestClient("NonExistent", "NonExistent");

        ClientCodebaseResolver.CodebaseMapping mapping =
                ClientCodebaseResolver.resolveFromHeuristics(client, org);

        assertNull(mapping);
    }

    // =====================================================================
    // Integration Tests: resolveAll
    // =====================================================================

    @Test
    void resolveAll_layer1Priority_usesCodebaseIndexFirst() throws IOException {
        // Create CODEBASE-INDEX.md
        String content = """
                ### Elprint (25 repositories) - PCB Client
                **Location:** `/src/elprint/`
                """;

        Path orgDir = tempDir.resolve("eXOReaction");
        Files.createDirectories(orgDir);
        Path indexFile = orgDir.resolve("CODEBASE-INDEX.md");
        Files.writeString(indexFile, content);

        Organization org = createTestOrganization("eXOReaction", orgDir);
        org.setCodebasePaths(List.of("/src/other")); // Different path

        Client client = createTestClient("Elprint", "Elprint");
        org.addClient(client);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.resolveAll(List.of(org));

        assertEquals(1, mappings.size());
        ClientCodebaseResolver.CodebaseMapping mapping = mappings.get("Elprint");
        assertNotNull(mapping);
        // Should use Layer 1 (CODEBASE-INDEX.md) not Layer 2 (organization)
        assertEquals(0.5, mapping.getConfidence());
        assertTrue(mapping.getCodebasePaths().get(0).endsWith("/src/elprint"));
    }

    @Test
    void resolveAll_extractsFromCodebaseIndex() throws IOException {
        String content = """
                ### Elprint (25 repositories) - PCB Client
                **Location:** `/src/elprint/`

                ### Entra (73 repositories) - Building Automation
                **Location:** `/src/entra/`
                """;

        Path orgDir = tempDir.resolve("eXOReaction");
        Files.createDirectories(orgDir);
        Path indexFile = orgDir.resolve("CODEBASE-INDEX.md");
        Files.writeString(indexFile, content);

        Organization org = createTestOrganization("eXOReaction", orgDir);

        Client client1 = createTestClient("Elprint", "Elprint");
        Client client2 = createTestClient("Entra", "Entra");
        org.addClient(client1);
        org.addClient(client2);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.resolveAll(List.of(org));

        assertEquals(2, mappings.size());
        assertTrue(mappings.containsKey("Elprint"));
        assertTrue(mappings.containsKey("Entra"));
    }

    @Test
    void resolveAll_multipleOrganizations_resolvesAll() throws IOException {
        // Organization 1: eXOReaction
        Path org1Dir = tempDir.resolve("eXOReaction");
        Files.createDirectories(org1Dir);
        Files.writeString(org1Dir.resolve("CODEBASE-INDEX.md"), """
                ### Elprint (25 repositories) - PCB Client
                **Location:** `/src/elprint/`
                """);

        Organization org1 = createTestOrganization("eXOReaction", org1Dir);
        Client client1 = createTestClient("Elprint", "Elprint");
        org1.addClient(client1);

        // Organization 2: Quadim
        Path org2Dir = tempDir.resolve("Quadim");
        Files.createDirectories(org2Dir);
        Files.writeString(org2Dir.resolve("CODEBASE-INDEX.md"), """
                ### CatalystOne (5 repositories) - HR Tech Client
                **Location:** `/src/catalystone/`
                """);

        Organization org2 = createTestOrganization("Quadim", org2Dir);
        Client client2 = createTestClient("CatalystOne", "CatalystOne");
        org2.addClient(client2);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.resolveAll(List.of(org1, org2));

        assertEquals(2, mappings.size());
        assertTrue(mappings.containsKey("Elprint"));
        assertTrue(mappings.containsKey("CatalystOne"));
    }

    @Test
    void resolveAll_noMatchingLayers_returnsEmpty() {
        Organization org = createTestOrganization("eXOReaction", tempDir);
        Client client = createTestClient("NonExistent", "NonExistent");
        org.addClient(client);

        Map<String, ClientCodebaseResolver.CodebaseMapping> mappings =
                ClientCodebaseResolver.resolveAll(List.of(org));

        assertTrue(mappings.isEmpty());
    }

    // =====================================================================
    // CodebaseMapping Tests
    // =====================================================================

    @Test
    void codebaseMapping_immutableLists_cannotModify() {
        List<String> paths = new ArrayList<>();
        paths.add("/src/test");
        List<String> signals = new ArrayList<>();
        signals.add("test signal");

        ClientCodebaseResolver.CodebaseMapping mapping =
                new ClientCodebaseResolver.CodebaseMapping("Test", paths, 0.5, signals);

        assertThrows(UnsupportedOperationException.class,
                () -> mapping.getCodebasePaths().add("/src/other"));
        assertThrows(UnsupportedOperationException.class,
                () -> mapping.getSignals().add("other signal"));
    }

    @Test
    void codebaseMapping_toString_containsKeyInfo() {
        ClientCodebaseResolver.CodebaseMapping mapping =
                new ClientCodebaseResolver.CodebaseMapping(
                        "Elprint",
                        List.of("/src/elprint"),
                        0.5,
                        List.of("test")
                );

        String str = mapping.toString();
        assertTrue(str.contains("Elprint"));
        assertTrue(str.contains("/src/elprint"));
        assertTrue(str.contains("0.5"));
    }

    // =====================================================================
    // Helper Methods
    // =====================================================================

    private Path createTempFile(String content) throws IOException {
        Path file = tempDir.resolve("CODEBASE-INDEX.md");
        Files.writeString(file, content);
        return file;
    }

    private Organization createTestOrganization(String name, Path basePath) {
        return new Organization(name, OrganizationType.COMPANY, basePath);
    }

    private Client createTestClient(String name, String directoryName) {
        Path clientPath = tempDir.resolve("clients").resolve(directoryName);
        return new Client(name, "TestOrg", clientPath, ClientStatus.ACTIVE, directoryName);
    }
}
