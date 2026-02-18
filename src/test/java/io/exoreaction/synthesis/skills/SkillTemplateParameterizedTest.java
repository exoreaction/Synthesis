package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for SkillTemplate — all org types, sanitizeForFilename edge cases,
 * all skill template methods produce valid YAML structure.
 */
class SkillTemplateParameterizedTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-02-14T12:00:00Z");

    // --- organizationContext for all OrganizationType values ---

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void organizationContext_allOrgTypes_producesValidYaml(OrganizationType type,
                                                            @TempDir Path tempDir) {
        Organization org = new Organization("TestOrg", type, tempDir.resolve("TestOrg"));
        String yaml = SkillTemplate.organizationContext(org, TIMESTAMP);

        assertFalse(yaml.isBlank(), "YAML should not be blank for type " + type);
        assertTrue(yaml.contains("name:"), "YAML should contain 'name:' for type " + type);
        assertTrue(yaml.contains("instructions: |"), "YAML should contain 'instructions: |'");
    }

    // --- workspaceContext for all OrganizationType values ---

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void workspaceContext_allOrgTypes_producesNonBlankYaml(OrganizationType type,
                                                            @TempDir Path tempDir) {
        Organization org = new Organization("OrgName", type, tempDir.resolve("OrgName"));
        String yaml = SkillTemplate.workspaceContext("MyWS", tempDir, List.of(org), TIMESTAMP);

        assertFalse(yaml.isBlank(), "workspaceContext should not be blank for type " + type);
        assertTrue(yaml.contains("MyWS"), "Should contain workspace name");
    }

    // --- sanitizeForFilename edge cases ---

    @ParameterizedTest
    @CsvSource({
        "eXOReaction,       exoreaction",
        "My Company,        my-company",
        "T-Hex,             t-hex",
        "CamelCase,         camelcase",
        "a--b,              a-b",
        "UPPER_CASE,        upper-case",
        "with spaces here,  with-spaces-here",
        "Simple,            simple"
    })
    void sanitizeForFilename_variousInputs(String input, String expected) {
        assertEquals(expected, SkillTemplate.sanitizeForFilename(input),
                "sanitizeForFilename('" + input + "') should be '" + expected + "'");
    }

    // --- all template methods return non-blank YAML ---

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void navigateClients_allOrgTypes_returnsNonBlank(OrganizationType type, @TempDir Path tempDir) {
        Organization org = new Organization("ClientOrg", type, tempDir.resolve("ClientOrg"));
        String yaml = SkillTemplate.navigateClients(List.of(org), TIMESTAMP);
        assertFalse(yaml.isBlank(), "navigateClients should be non-blank for " + type);
    }

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void pipelineTracker_allOrgTypes_returnsNonBlank(OrganizationType type, @TempDir Path tempDir) {
        Organization org = new Organization("PipelineOrg", type, tempDir.resolve("PipelineOrg"));
        String yaml = SkillTemplate.pipelineTracker(List.of(org), TIMESTAMP);
        assertFalse(yaml.isBlank(), "pipelineTracker should be non-blank for " + type);
    }

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void proofPoints_allOrgTypes_returnsNonBlank(OrganizationType type, @TempDir Path tempDir) {
        Organization org = new Organization("ProofOrg", type, tempDir.resolve("ProofOrg"));
        String yaml = SkillTemplate.proofPoints(List.of(org), TIMESTAMP);
        assertFalse(yaml.isBlank(), "proofPoints should be non-blank for " + type);
    }

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void architectureOverview_allOrgTypes_returnsNonBlank(OrganizationType type, @TempDir Path tempDir) {
        Organization org = new Organization("ArchOrg", type, tempDir.resolve("ArchOrg"));
        String yaml = SkillTemplate.architectureOverview(List.of(org), TIMESTAMP);
        assertFalse(yaml.isBlank(), "architectureOverview should be non-blank for " + type);
    }

    @ParameterizedTest
    @EnumSource(OrganizationType.class)
    void keyDecisions_allOrgTypes_returnsNonBlank(OrganizationType type, @TempDir Path tempDir) {
        Organization org = new Organization("DecisionOrg", type, tempDir.resolve("DecisionOrg"));
        String yaml = SkillTemplate.keyDecisions(List.of(org), TIMESTAMP);
        assertFalse(yaml.isBlank(), "keyDecisions should be non-blank for " + type);
    }

    // --- yamlHeader format ---

    @ParameterizedTest
    @CsvSource({
        "my-skill,       My Skill Description",
        "workspace-ctx,  Workspace context skill",
        "pipeline,       Pipeline tracker skill"
    })
    void yamlHeader_containsNameAndDescription(String name, String description) {
        String header = SkillTemplate.yamlHeader(name, description);
        assertTrue(header.contains(name), "Header should contain skill name");
        assertTrue(header.contains(description), "Header should contain description");
        assertTrue(header.startsWith("name: " + name), "Header should start with 'name:'");
    }

    // --- multiple organizations ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5})
    void workspaceContext_multipleOrgs_allOrgNamesPresent(int orgCount, @TempDir Path tempDir) {
        List<Organization> orgs = java.util.stream.IntStream.range(0, orgCount)
                .mapToObj(i -> new Organization("Org" + i, OrganizationType.COMPANY,
                        tempDir.resolve("Org" + i)))
                .toList();

        String yaml = SkillTemplate.workspaceContext("TestWorkspace", tempDir, orgs, TIMESTAMP);

        for (Organization org : orgs) {
            assertTrue(yaml.contains(org.getName()),
                    "YAML should mention org '" + org.getName() + "'");
        }
    }
}
