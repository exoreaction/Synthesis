package io.exoreaction.synthesis.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillMatcherTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private Path writeSkill(String filename, String name, String description,
                            String triggerPhrases, String instructions) throws Exception {
        String yaml = "name: " + name + "\n" +
                "description: " + description + "\n" +
                "trigger_phrases:\n" + triggerPhrases +
                "instructions: |\n  " + instructions + "\n";
        Path file = tempDir.resolve(filename);
        Files.writeString(file, yaml);
        return file;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void testMatchReturnsTopN() throws Exception {
        writeSkill("gerber.yaml", "pcb-gerber-format",
                "Parse Gerber RS-274X PCB format files",
                "  - \"parse Gerber format\"\n  - \"PCB file parser\"\n",
                "Handles Gerber RS-274X format parsing.");

        writeSkill("validation.yaml", "pcb-validation-rules",
                "Validate PCB design rules",
                "  - \"validate PCB design\"\n  - \"run design rule check\"\n",
                "Applies validation rules to PCB layouts.");

        writeSkill("unrelated.yaml", "docker-deployment",
                "Deploy services with Docker Compose",
                "  - \"deploy with docker\"\n  - \"start containers\"\n",
                "Manages Docker Compose deployments.");

        List<SkillMatcher.SkillMatch> results = SkillMatcher.match(tempDir, "parse Gerber format", 5);

        assertFalse(results.isEmpty(), "Should find at least one match");
        assertEquals("pcb-gerber-format", results.get(0).skillName(),
                "Gerber skill should rank first for 'parse Gerber format'");
        assertTrue(results.get(0).score() > results.get(results.size() - 1).score()
                || results.size() == 1,
                "Results should be ordered by score descending");
    }

    @Test
    void testTriggerPhraseBoost() throws Exception {
        // Skill A: trigger phrase matches query exactly
        writeSkill("skill-a.yaml", "skill-with-trigger",
                "General purpose skill",
                "  - \"parse Gerber format\"\n",
                "Does various things.");

        // Skill B: description matches but no trigger
        writeSkill("skill-b.yaml", "skill-description-match",
                "Parse Gerber format files for PCB design",
                "  - \"something unrelated\"\n",
                "Processes PCB data.");

        List<SkillMatcher.SkillMatch> results = SkillMatcher.match(tempDir, "parse Gerber format", 5);

        assertFalse(results.isEmpty());
        // Skill with trigger phrase match should score higher
        assertEquals("skill-with-trigger", results.get(0).skillName(),
                "Trigger phrase match should score higher than description-only match");
    }

    @Test
    void testEmptySkillsDir() {
        // tempDir is empty
        List<SkillMatcher.SkillMatch> results = SkillMatcher.match(tempDir, "some query", 5);
        assertTrue(results.isEmpty(), "Empty dir should return empty results");
    }

    @Test
    void testMissingSkillsDir() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        List<SkillMatcher.SkillMatch> results = SkillMatcher.match(nonExistent, "query", 5);
        assertTrue(results.isEmpty(), "Missing dir should return empty results gracefully");
    }

    @Test
    void testTopNLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            writeSkill("skill-" + i + ".yaml", "skill-" + i,
                    "Description with keyword gerber parser",
                    "  - \"gerber parser skill\"\n",
                    "Parses gerber format files with various techniques.");
        }

        List<SkillMatcher.SkillMatch> results = SkillMatcher.match(tempDir, "gerber parser", 3);
        assertEquals(3, results.size(), "Should return exactly topN results");
    }

    @Test
    void testListReturnsAllSkills() throws Exception {
        writeSkill("alpha.yaml", "alpha-skill", "Alpha description",
                "  - \"alpha trigger\"\n", "Alpha instructions.");
        writeSkill("beta.yaml", "beta-skill", "Beta description",
                "  - \"beta trigger\"\n", "Beta instructions.");
        writeSkill("gamma.yaml", "gamma-skill", "Gamma description",
                "  - \"gamma trigger\"\n", "Gamma instructions.");

        List<SkillMatcher.SkillMatch> skills = SkillMatcher.list(tempDir);
        assertEquals(3, skills.size(), "list() should return all skills");
        // All scores should be 0 (unranked)
        skills.forEach(m -> assertEquals(0.0, m.score(), "list() scores should be 0"));
    }

    @Test
    void testTokenise() {
        Set<String> tokens = SkillMatcher.tokenise("Parse Gerber RS-274X format files");
        assertTrue(tokens.contains("parse"), "Should contain 'parse'");
        assertTrue(tokens.contains("gerber"), "Should contain 'gerber'");
        assertTrue(tokens.contains("274x"), "Should contain '274x'");
        assertTrue(tokens.contains("format"), "Should contain 'format'");
        assertTrue(tokens.contains("files"), "Should contain 'files'");
        // "rs" is 2 chars — below 3-char minimum
        assertFalse(tokens.contains("rs"), "Should filter tokens shorter than 3 chars");
    }

    @Test
    void testMalformedYamlSkipped() throws Exception {
        // Write a valid skill
        writeSkill("valid.yaml", "valid-skill", "A valid skill",
                "  - \"valid trigger\"\n", "Valid instructions.");
        // Write malformed YAML
        Files.writeString(tempDir.resolve("broken.yaml"), "{ unclosed: [bracket");

        // Should not throw — just skip the broken file
        List<SkillMatcher.SkillMatch> results = SkillMatcher.match(tempDir, "valid trigger", 5);
        assertFalse(results.isEmpty(), "Should return results despite broken YAML file");
    }

    @Test
    void testCompactOutputViaList() throws Exception {
        writeSkill("myskill.yaml", "my-skill", "Test skill for compact output",
                "  - \"compact output test\"\n", "Verifies compact mode.");

        List<SkillMatcher.SkillMatch> skills = SkillMatcher.list(tempDir);
        assertEquals(1, skills.size());
        // Compact output is just skillName — verify it's non-blank
        assertFalse(skills.get(0).skillName().isBlank());
        assertFalse(skills.get(0).firstLine().isBlank());
    }

    @Test
    void testCountSubdirectorySkillsDetectsSkillMdDirs() throws Exception {
        Path skillDir = tempDir.resolve("deployment");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: deployment\n---\nBody.");

        assertEquals(1, SkillMatcher.countSubdirectorySkills(tempDir));
    }

    @Test
    void testCountSubdirectorySkillsIgnoresDirsWithoutSkillMd() throws Exception {
        Path notASkill = tempDir.resolve("scratch");
        Files.createDirectory(notASkill);
        Files.writeString(notASkill.resolve("notes.txt"), "irrelevant");

        assertEquals(0, SkillMatcher.countSubdirectorySkills(tempDir));
    }

    @Test
    void testCountSubdirectorySkillsZeroForFlatYamlOnly() throws Exception {
        writeSkill("flat.yaml", "flat-skill", "Flat description",
                "  - \"flat trigger\"\n", "Flat instructions.");

        assertEquals(0, SkillMatcher.countSubdirectorySkills(tempDir));
    }

    @Test
    void testCountSubdirectorySkillsZeroForMissingDir() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        assertEquals(0, SkillMatcher.countSubdirectorySkills(nonExistent));
    }

    @Test
    void testMatchStillReturnsEmptyForSubdirectoryOnlySkills() throws Exception {
        // Documents current match()/list() scope: subdirectory-format skills are
        // not indexed (Option A fix from issue #340 is a warning, not format support).
        Path skillDir = tempDir.resolve("deployment");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: deployment\n---\nBody.");

        assertTrue(SkillMatcher.match(tempDir, "deploy", 5).isEmpty());
        assertTrue(SkillMatcher.list(tempDir).isEmpty());
        assertEquals(1, SkillMatcher.countSubdirectorySkills(tempDir),
                "warning signal should fire even though match/list stay empty");
    }
}
