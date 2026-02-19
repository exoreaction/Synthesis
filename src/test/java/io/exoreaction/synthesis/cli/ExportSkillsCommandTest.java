package io.exoreaction.synthesis.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExportSkillsCommand tiered skill loading.
 * Verifies the skills-manifest.json structure, tier assignments,
 * and trigger metadata.
 */
class ExportSkillsCommandTest {

    private static final String MANIFEST_RESOURCE = "claude-skills/skills-manifest.json";

    private ObjectMapper mapper;
    private JsonNode manifest;
    private JsonNode skillsArray;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
            assertNotNull(is, "skills-manifest.json must exist in classpath");
            manifest = mapper.readTree(is);
        }
        skillsArray = manifest.get("skills");
        assertNotNull(skillsArray, "Manifest must have a 'skills' array");
        assertTrue(skillsArray.isArray(), "'skills' must be a JSON array");
    }

    @Test
    void manifestContainsTierField() {
        for (JsonNode skill : skillsArray) {
            String name = skill.get("name").asText();
            assertTrue(skill.has("tier"),
                    "Skill '" + name + "' must have a 'tier' field");
            int tier = skill.get("tier").asInt();
            assertTrue(tier >= 1 && tier <= 3,
                    "Skill '" + name + "' tier must be 1, 2, or 3 but was " + tier);
        }
    }

    @Test
    void manifestContainsTriggersField() {
        for (JsonNode skill : skillsArray) {
            String name = skill.get("name").asText();
            assertTrue(skill.has("triggers"),
                    "Skill '" + name + "' must have a 'triggers' field");
            JsonNode triggers = skill.get("triggers");
            assertTrue(triggers.isArray(),
                    "Skill '" + name + "' triggers must be an array");
            assertTrue(triggers.size() >= 2,
                    "Skill '" + name + "' should have at least 2 triggers but had " + triggers.size());
        }
    }

    @Test
    void tier1ContainsCoreSkills() {
        Set<String> tier1Names = new HashSet<>();
        for (JsonNode skill : skillsArray) {
            if (skill.get("tier").asInt() == 1) {
                tier1Names.add(skill.get("name").asText());
            }
        }

        assertTrue(tier1Names.contains("synthesis-search-workspace"),
                "search-workspace must be tier 1");
        assertTrue(tier1Names.contains("synthesis-development"),
                "development must be tier 1");
        assertTrue(tier1Names.contains("synthesis-product-context"),
                "product-context must be tier 1");

        assertTrue(tier1Names.size() <= 6,
                "Tier 1 should be a small core set, was " + tier1Names.size());
        assertTrue(tier1Names.size() >= 3,
                "Tier 1 should have at least 3 core skills, was " + tier1Names.size());
    }

    @Test
    void tier2IncludesTier1() {
        int tier1Count = 0;
        int tier2Count = 0;
        int tier3Count = 0;

        for (JsonNode skill : skillsArray) {
            int tier = skill.get("tier").asInt();
            switch (tier) {
                case 1 -> tier1Count++;
                case 2 -> tier2Count++;
                case 3 -> tier3Count++;
            }
        }

        assertTrue(tier2Count > tier1Count,
                "Tier 2 count (" + tier2Count + ") should exceed tier 1 count (" + tier1Count + ")");
        assertTrue(tier2Count > tier3Count,
                "Tier 2 count (" + tier2Count + ") should exceed tier 3 count (" + tier3Count + ")");

        assertEquals(skillsArray.size(), tier1Count + tier2Count + tier3Count,
                "Sum of tier counts must equal total skills");
    }

    @Test
    void tier3ContainsReferenceSkills() {
        Set<String> tier3Names = new HashSet<>();
        for (JsonNode skill : skillsArray) {
            if (skill.get("tier").asInt() == 3) {
                tier3Names.add(skill.get("name").asText());
            }
        }

        assertTrue(tier3Names.contains("synthesis-benchmark"),
                "benchmark should be tier 3");
        assertTrue(tier3Names.contains("synthesis-linkedin-campaign"),
                "linkedin-campaign should be tier 3");
    }

    @Test
    void defaultExportsAll() {
        int totalSkills = skillsArray.size();
        int includedAtTier3 = 0;
        for (JsonNode skill : skillsArray) {
            int tier = skill.get("tier").asInt();
            if (tier <= 3) {
                includedAtTier3++;
            }
        }
        assertEquals(totalSkills, includedAtTier3,
                "Default tier 3 should include all skills");
    }

    @Test
    void tier1FilterExcludesHigherTiers() {
        int tier1Only = 0;
        int excluded = 0;
        for (JsonNode skill : skillsArray) {
            int tier = skill.get("tier").asInt();
            if (tier <= 1) {
                tier1Only++;
            } else {
                excluded++;
            }
        }
        assertTrue(tier1Only > 0, "Must have at least one tier 1 skill");
        assertTrue(excluded > 0, "Must exclude some skills at tier > 1");
        assertTrue(tier1Only < skillsArray.size(),
                "Tier 1 filter should export fewer than all skills");
    }

    @Test
    void allSkillsHaveRequiredFields() {
        for (JsonNode skill : skillsArray) {
            String name = skill.has("name") ? skill.get("name").asText() : "<missing>";
            assertTrue(skill.has("name"), "Skill must have 'name'");
            assertTrue(skill.has("file"), "Skill '" + name + "' must have 'file'");
            assertTrue(skill.has("command"), "Skill '" + name + "' must have 'command'");
            assertTrue(skill.has("category"), "Skill '" + name + "' must have 'category'");
            assertTrue(skill.has("description"), "Skill '" + name + "' must have 'description'");
            assertTrue(skill.has("tier"), "Skill '" + name + "' must have 'tier'");
            assertTrue(skill.has("triggers"), "Skill '" + name + "' must have 'triggers'");
        }
    }

    @Test
    void skillNamesAreUnique() {
        Set<String> names = new HashSet<>();
        for (JsonNode skill : skillsArray) {
            String name = skill.get("name").asText();
            assertTrue(names.add(name), "Duplicate skill name: " + name);
        }
    }

    @Test
    void manifestHasVersionField() {
        assertTrue(manifest.has("version"), "Manifest must have a 'version' field");
        String version = manifest.get("version").asText();
        assertFalse(version.isBlank(), "Version must not be blank");
    }
}
