package io.exoreaction.synthesis.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkspaceMetadata — defaults, getters/setters, getWorkspaceType().
 */
class WorkspaceMetadataTest {

    // --- no-arg constructor defaults ---

    @Test
    void noArgConstructor_categoryDefaultIsMixed() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertEquals("mixed", metadata.getCategory());
    }

    @Test
    void noArgConstructor_primaryLanguageIsNull() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertNull(metadata.getPrimaryLanguage());
    }

    @Test
    void noArgConstructor_repoCountIsZero() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertEquals(0, metadata.getRepoCount());
    }

    @Test
    void noArgConstructor_descriptionIsEmpty() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertEquals("", metadata.getDescription());
    }

    @Test
    void noArgConstructor_companyIsNull() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertNull(metadata.getCompany());
    }

    @Test
    void noArgConstructor_tagsIsEmpty() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertEquals("", metadata.getTags());
    }

    // --- builder constructor ---

    @Test
    void builderConstructor_storesAllFields() {
        WorkspaceMetadata metadata = new WorkspaceMetadata(
                "source-code", "java", 15, "Java microservices workspace", "eXOReaction");

        assertEquals("source-code", metadata.getCategory());
        assertEquals("java", metadata.getPrimaryLanguage());
        assertEquals(15, metadata.getRepoCount());
        assertEquals("Java microservices workspace", metadata.getDescription());
        assertEquals("eXOReaction", metadata.getCompany());
    }

    // --- setters ---

    @Test
    void setCategory_updatesValue() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCategory("documents");
        assertEquals("documents", metadata.getCategory());
    }

    @Test
    void setPrimaryLanguage_updatesValue() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setPrimaryLanguage("kotlin");
        assertEquals("kotlin", metadata.getPrimaryLanguage());
    }

    @Test
    void setRepoCount_updatesValue() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setRepoCount(42);
        assertEquals(42, metadata.getRepoCount());
    }

    @Test
    void setDescription_updatesValue() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setDescription("Test description");
        assertEquals("Test description", metadata.getDescription());
    }

    @Test
    void setCompany_updatesValue() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCompany("Quadim");
        assertEquals("Quadim", metadata.getCompany());
    }

    @Test
    void setTags_updatesValue() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setTags("backend,api,core");
        assertEquals("backend,api,core", metadata.getTags());
    }

    // --- getWorkspaceType ---

    @Test
    void getWorkspaceType_defaultCategory_returnsMixed() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertEquals(WorkspaceType.MIXED, metadata.getWorkspaceType());
    }

    @Test
    void getWorkspaceType_sourceCode_returnsSourceCode() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCategory("source-code");
        assertEquals(WorkspaceType.SOURCE_CODE, metadata.getWorkspaceType());
    }

    @Test
    void getWorkspaceType_documents_returnsDocuments() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCategory("documents");
        assertEquals(WorkspaceType.DOCUMENTS, metadata.getWorkspaceType());
    }

    @Test
    void getWorkspaceType_staging_returnsStaging() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCategory("staging");
        assertEquals(WorkspaceType.STAGING, metadata.getWorkspaceType());
    }

    @Test
    void getWorkspaceType_nullCategory_returnsMixed() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCategory(null);
        assertEquals(WorkspaceType.MIXED, metadata.getWorkspaceType());
    }

    // --- toString ---

    @Test
    void toString_containsCategory() {
        WorkspaceMetadata metadata = new WorkspaceMetadata("source-code", "java", 5, "desc", "Corp");
        assertTrue(metadata.toString().contains("source-code"), "toString should contain category");
    }

    @Test
    void toString_containsLanguageWhenSet() {
        WorkspaceMetadata metadata = new WorkspaceMetadata("source-code", "kotlin", 5, "desc", null);
        assertTrue(metadata.toString().contains("kotlin"), "toString should include language when set");
    }

    @Test
    void toString_containsRepoCountWhenPositive() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setRepoCount(10);
        assertTrue(metadata.toString().contains("10"), "toString should include repo count when > 0");
    }

    @Test
    void toString_containsCompanyWhenSet() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setCompany("Cantara");
        assertTrue(metadata.toString().contains("Cantara"), "toString should include company when set");
    }

    @Test
    void toString_noArgConstructor_noNullPointerException() {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        assertDoesNotThrow(metadata::toString, "toString should not throw NPE for default-constructed object");
    }
}
