package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InteractiveConfirmation}.
 */
class InteractiveConfirmationTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void disableAnsi() {
        AnsiOutput.setEnabled(false);
    }

    // --- Accept all (Y) ---

    @Test
    void confirm_acceptAll_returnsAllOrganizations() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("Y\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertEquals(2, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
        assertFalse(result.wasReviewed());
    }

    @Test
    void confirm_emptyInput_defaultsToAcceptAll() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertEquals(2, result.accepted().size());
    }

    // --- Reject all (n) ---

    @Test
    void confirm_rejectAll_returnsNoAccepted() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("n\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertTrue(result.accepted().isEmpty());
        assertEquals(2, result.rejected().size());
        assertFalse(result.wasReviewed());
    }

    @Test
    void confirm_rejectNo_returnsNoAccepted() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("no\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertTrue(result.accepted().isEmpty());
    }

    // --- Review mode ---

    @Test
    void confirm_review_promptsEachOrg() throws IOException {
        // Accept first, reject second
        InteractiveConfirmation confirmation = createConfirmation("review\nY\nn\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertEquals(1, result.accepted().size());
        assertEquals(1, result.rejected().size());
        assertTrue(result.wasReviewed());
        assertEquals("OrgA", result.accepted().get(0).getName());
        assertEquals("OrgB", result.rejected().get(0).getName());
    }

    @Test
    void confirm_review_acceptAll() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("r\nY\nY\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertEquals(2, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void confirm_review_rejectAll() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("review\nn\nn\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertTrue(result.accepted().isEmpty());
        assertEquals(2, result.rejected().size());
    }

    // --- Empty discoveries ---

    @Test
    void confirm_emptyList_returnsEmptyResult() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("");

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(List.of());

        assertTrue(result.accepted().isEmpty());
        assertTrue(result.rejected().isEmpty());
        assertFalse(result.wasReviewed());
    }

    // --- hasAccepted ---

    @Test
    void hasAccepted_withOrgs_returnsTrue() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("Y\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertTrue(result.hasAccepted());
    }

    @Test
    void hasAccepted_withNoOrgs_returnsFalse() throws IOException {
        InteractiveConfirmation confirmation = createConfirmation("n\n");
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);

        assertFalse(result.hasAccepted());
    }

    // --- Display output ---

    @Test
    void confirm_displaysOrganizationNames() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveConfirmation confirmation = createConfirmation("Y\n", output);
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        confirmation.confirm(discoveries);

        String displayed = output.toString();
        assertTrue(displayed.contains("OrgA"), "Should display OrgA");
        assertTrue(displayed.contains("OrgB"), "Should display OrgB");
    }

    @Test
    void confirm_displaysConfidence() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveConfirmation confirmation = createConfirmation("Y\n", output);
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        confirmation.confirm(discoveries);

        String displayed = output.toString();
        assertTrue(displayed.contains("/10"), "Should display confidence score");
    }

    @Test
    void confirm_displaysSignals() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveConfirmation confirmation = createConfirmation("Y\n", output);
        List<DiscoveredOrganization> discoveries = createDiscoveries();

        confirmation.confirm(discoveries);

        String displayed = output.toString();
        assertTrue(displayed.contains("business/"), "Should display detection signals");
    }

    // --- Type badge formatting ---

    @Test
    void formatTypeBadge_company() {
        InteractiveConfirmation confirmation = createConfirmation("");
        String badge = confirmation.formatTypeBadge(OrganizationType.COMPANY);
        assertTrue(badge.contains("COMPANY"));
    }

    @Test
    void formatTypeBadge_foundation() {
        InteractiveConfirmation confirmation = createConfirmation("");
        String badge = confirmation.formatTypeBadge(OrganizationType.FOUNDATION);
        assertTrue(badge.contains("FOUNDATION"));
    }

    // --- Helper methods ---

    private InteractiveConfirmation createConfirmation(String input) {
        return createConfirmation(input, new ByteArrayOutputStream());
    }

    private InteractiveConfirmation createConfirmation(String input,
                                                        ByteArrayOutputStream output) {
        BufferedReader reader = new BufferedReader(new StringReader(input));
        PrintStream printStream = new PrintStream(output);
        return new InteractiveConfirmation(reader, printStream);
    }

    private List<DiscoveredOrganization> createDiscoveries() {
        Organization orgA = new Organization("OrgA", OrganizationType.COMPANY,
                tempDir.resolve("OrgA"));
        orgA.addClient(new Client("ClientX", "OrgA",
                tempDir.resolve("x"), ClientStatus.ACTIVE, "ClientX"));

        Organization orgB = new Organization("OrgB", OrganizationType.FOUNDATION,
                tempDir.resolve("OrgB"));

        return List.of(
                new DiscoveredOrganization(orgA, 10, "3 clients, business/ directory"),
                new DiscoveredOrganization(orgB, 5, "README.md, business/ directory")
        );
    }
}
