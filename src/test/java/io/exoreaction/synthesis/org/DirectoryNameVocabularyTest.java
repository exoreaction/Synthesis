package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryNameVocabularyTest {

    private final DirectoryNameVocabulary vocabulary = new DirectoryNameVocabulary();

    private static ScopeResolver.ResolvedScope workspaceScope() {
        return new ScopeResolver.ResolvedScope(ScopeLevel.WORKSPACE, null, null);
    }

    private static ScopeResolver.ResolvedScope orgScope(String org) {
        return new ScopeResolver.ResolvedScope(ScopeLevel.ORGANIZATION, org, null);
    }

    private static ScopeResolver.ResolvedScope entityScope(String org, String entity) {
        return new ScopeResolver.ResolvedScope(ScopeLevel.ENTITY, org, entity);
    }

    @Test
    void inferFromName_knownName_returnsIdentity() {
        Optional<DirectoryIdentity> result = vocabulary.inferFromName("meeting-notes", workspaceScope());

        assertTrue(result.isPresent(), "Expected identity for 'meeting-notes'");
        DirectoryIdentity identity = result.get();
        assertTrue(identity.acceptsTypes().contains("meeting-notes"),
                "Types should contain 'meeting-notes', got: " + identity.acceptsTypes());
        assertEquals(0.6, identity.confidence(), 0.001);
        assertEquals("inferred from directory name", identity.source());
        assertTrue(identity.acceptsPatterns().isEmpty(), "Patterns should be empty for name vocabulary");
        assertNotNull(identity.lastSynced(), "lastSynced should be set");
    }

    @Test
    void inferFromName_unknownName_returnsEmpty() {
        Optional<DirectoryIdentity> result = vocabulary.inferFromName("random-stuff", workspaceScope());

        assertTrue(result.isEmpty(), "Expected empty for unrecognized name 'random-stuff'");
    }

    @Test
    void inferFromName_caseInsensitive() {
        Optional<DirectoryIdentity> lower = vocabulary.inferFromName("meeting-notes", workspaceScope());
        Optional<DirectoryIdentity> mixed = vocabulary.inferFromName("Meeting-Notes", workspaceScope());

        assertTrue(lower.isPresent());
        assertTrue(mixed.isPresent());
        assertEquals(lower.get().acceptsTypes(), mixed.get().acceptsTypes());
        assertEquals(lower.get().acceptsFormats(), mixed.get().acceptsFormats());
    }

    @Test
    void inferFromName_kebabCase() {
        Optional<DirectoryIdentity> result = vocabulary.inferFromName("meeting-notes", workspaceScope());

        assertTrue(result.isPresent(), "Expected identity for kebab-case 'meeting-notes'");
        assertTrue(result.get().acceptsTypes().contains("meeting-notes"));
        assertTrue(result.get().acceptsTypes().contains("minutes"));
    }

    @Test
    void inferFromName_scopePopulated() {
        ScopeResolver.ResolvedScope scope = entityScope("Acme Corp", "Project X");

        Optional<DirectoryIdentity> result = vocabulary.inferFromName("invoices", scope);

        assertTrue(result.isPresent());
        DirectoryIdentity identity = result.get();
        assertEquals(ScopeLevel.ENTITY, identity.scopeLevel());
        assertEquals("Acme Corp", identity.scopeOrganization());
        assertEquals("Project X", identity.scopeEntity());
    }

    @Test
    void inferFromName_norwegianName() {
        Optional<DirectoryIdentity> result = vocabulary.inferFromName("faktura", workspaceScope());

        assertTrue(result.isPresent(), "Expected identity for Norwegian name 'faktura'");
        assertTrue(result.get().acceptsTypes().contains("invoice"),
                "Types should contain 'invoice', got: " + result.get().acceptsTypes());
        assertTrue(result.get().acceptsTypes().contains("financial"));
    }

    @Test
    void inferFromName_automationDir() {
        Optional<DirectoryIdentity> result = vocabulary.inferFromName("automation", workspaceScope());

        assertTrue(result.isPresent(), "Expected identity for 'automation'");
        assertTrue(result.get().acceptsTypes().contains("automation"),
                "Types should contain 'automation', got: " + result.get().acceptsTypes());
        assertTrue(result.get().acceptsTypes().contains("scripts"));
        assertTrue(result.get().acceptsFormats().contains("sh"));
        assertTrue(result.get().acceptsFormats().contains("py"));
    }
}
