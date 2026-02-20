package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScopeResolver} positional scope inference.
 */
class ScopeResolverTest {

    private static final String ORG_BASE = "/home/totto/Documents/eXOReaction";
    private static final String ORG_NAME = "eXOReaction";

    private OrganizationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new OrganizationRegistry(Path.of("/home/totto/Documents"));

        Organization org = new Organization(ORG_NAME, OrganizationType.COMPANY,
                Path.of(ORG_BASE));

        // Add a client under the standard clients/ directory
        Client elprint = new Client("Elprint", ORG_NAME,
                Path.of(ORG_BASE + "/clients/Elprint"),
                ClientStatus.ACTIVE, "Elprint");
        org.addClient(elprint);

        registry.addOrganization(org);
    }

    @Test
    void resolve_nullRegistry_returnsWorkspaceScope() {
        ScopeResolver resolver = new ScopeResolver(null);

        ScopeResolver.ResolvedScope scope = resolver.resolve(Path.of(ORG_BASE + "/some/file"));

        assertEquals(ScopeLevel.WORKSPACE, scope.level());
        assertNull(scope.organization());
        assertNull(scope.entity());
    }

    @Test
    void resolve_pathOutsideAllOrgs_returnsWorkspaceScope() {
        ScopeResolver resolver = new ScopeResolver(registry);

        ScopeResolver.ResolvedScope scope = resolver.resolve(Path.of("/home/totto/Downloads/random"));

        assertEquals(ScopeLevel.WORKSPACE, scope.level());
        assertNull(scope.organization());
        assertNull(scope.entity());
    }

    @Test
    void resolve_pathInsideOrgButNotClient_returnsOrganizationScope() {
        ScopeResolver resolver = new ScopeResolver(registry);

        ScopeResolver.ResolvedScope scope = resolver.resolve(Path.of(ORG_BASE + "/marketing/campaign.md"));

        assertEquals(ScopeLevel.ORGANIZATION, scope.level());
        assertEquals(ORG_NAME, scope.organization());
        assertNull(scope.entity());
    }

    @Test
    void resolve_pathInsideClientDir_returnsEntityScope() {
        ScopeResolver resolver = new ScopeResolver(registry);

        ScopeResolver.ResolvedScope scope = resolver.resolve(
                Path.of(ORG_BASE + "/clients/Elprint/project/README.md"));

        assertEquals(ScopeLevel.ENTITY, scope.level());
        assertEquals(ORG_NAME, scope.organization());
        assertEquals("Elprint", scope.entity());
    }

    @Test
    void resolve_pathWithOpportunityPrefix_returnsEntityScope() {
        ScopeResolver resolver = new ScopeResolver(registry);

        // opportunity-* directory under the org, not registered as a Client
        ScopeResolver.ResolvedScope scope = resolver.resolve(
                Path.of(ORG_BASE + "/opportunity-SpareBank1/proposal.pdf"));

        assertEquals(ScopeLevel.ENTITY, scope.level());
        assertEquals(ORG_NAME, scope.organization());
        assertEquals("SpareBank1", scope.entity());
    }

    @Test
    void resolve_pathInsideClientsSubdir_returnsEntityScope() {
        ScopeResolver resolver = new ScopeResolver(registry);

        // A client directory under clients/ that is NOT registered in the Organization's client list
        ScopeResolver.ResolvedScope scope = resolver.resolve(
                Path.of(ORG_BASE + "/clients/NordicEnergy/docs/report.md"));

        assertEquals(ScopeLevel.ENTITY, scope.level());
        assertEquals(ORG_NAME, scope.organization());
        assertEquals("NordicEnergy", scope.entity());
    }
}
