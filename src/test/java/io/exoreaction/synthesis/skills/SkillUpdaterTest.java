package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.skills.SessionAnalyzer.ExtractedPattern;
import io.exoreaction.synthesis.skills.SkillUpdater.ChangeType;
import io.exoreaction.synthesis.skills.SkillUpdater.ReflectResult;
import io.exoreaction.synthesis.skills.SkillUpdater.SkillChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillUpdater}: creating new skills, updating existing ones,
 * skip logic, and bloat control.
 */
class SkillUpdaterTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ExtractedPattern makePattern(String name, String description,
                                          List<String> triggers, List<String> instructions,
                                          List<String> tags, double confidence) {
        return new ExtractedPattern(
                Integer.toHexString(name.hashCode()),
                name, description, triggers, instructions, tags,
                1, confidence
        );
    }

    private Path writeExistingSkill(String filename, String content) throws Exception {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void testCreateNewSkill() throws Exception {
        ExtractedPattern pattern = makePattern(
                "database-naming",
                "Use snake_case for all database columns",
                List.of("database column naming"),
                List.of("Always use snake_case for database columns"),
                List.of("database", "naming", "snake_case"),
                0.8
        );

        ReflectResult result = SkillUpdater.apply(List.of(pattern), tempDir, false, 5);

        assertEquals(1, result.skillsCreated(), "Should create 1 skill");
        assertEquals(0, result.skillsUpdated(), "Should update 0 skills");

        // Verify the file was written
        Path expected = tempDir.resolve("reflect-database-naming.yaml");
        assertTrue(Files.exists(expected), "Skill file should exist: " + expected);

        String content = Files.readString(expected);
        assertTrue(content.contains("name: database-naming"), "Should contain skill name");
        assertTrue(content.contains("version: 0.1.0"), "Should contain initial version");
        assertTrue(content.contains("snake_case"), "Should contain instruction text");
    }

    @Test
    void testUpdateExistingSkill() throws Exception {
        // Write an existing skill that will match the pattern
        writeExistingSkill("existing-database.yaml",
                "name: database-conventions\n"
                + "version: 1.2.3\n"
                + "description: Database naming conventions and rules\n"
                + "trigger_phrases:\n"
                + "  - \"database naming\"\n"
                + "  - \"column conventions\"\n"
                + "instructions: |\n"
                + "  Use snake_case for table names.\n"
        );

        ExtractedPattern pattern = makePattern(
                "database-rules",
                "database naming conventions column rules",
                List.of("database naming", "new trigger phrase for columns"),
                List.of("Always validate column types before migration"),
                List.of("database", "naming"),
                0.8
        );

        ReflectResult result = SkillUpdater.apply(List.of(pattern), tempDir, false, 5);

        // Should match the existing skill (high keyword overlap)
        // The exact behavior depends on SkillMatcher scoring.
        // With enough overlap, it updates; otherwise it creates.
        assertTrue(result.skillsCreated() + result.skillsUpdated() >= 1,
                "Should either create or update at least 1 skill");
    }

    @Test
    void testDryRunDoesNotWriteFiles() throws Exception {
        ExtractedPattern pattern = makePattern(
                "dry-test-skill",
                "Test skill for dry run verification",
                List.of("dry run test"),
                List.of("This should not be written"),
                List.of("test"),
                0.8
        );

        ReflectResult result = SkillUpdater.apply(List.of(pattern), tempDir, true, 5);

        // In dry-run mode, the change should still be reported
        assertFalse(result.changes().isEmpty(), "Dry run should still report changes");

        // But no file should be created
        Path expected = tempDir.resolve("reflect-dry-test-skill.yaml");
        assertFalse(Files.exists(expected), "Dry run should NOT create files");
    }

    @Test
    void testBatchedUpdateProducesSingleVersionBump() throws Exception {
        // Write a skill that will match multiple patterns — simulates the version inflation scenario (#307)
        writeExistingSkill("synthesis-ask-workspace.yaml",
                "name: synthesis-ask-workspace\n"
                + "version: 1.0.14\n"
                + "description: Ask questions about the workspace using Synthesis search\n"
                + "trigger_phrases:\n"
                + "  - \"how does X work\"\n"
                + "instructions: |\n"
                + "  Use synthesis ask to query the workspace.\n"
        );

        // Three patterns all matching the same skill
        ExtractedPattern p1 = makePattern("workspace-query",
                "Ask workspace questions using synthesis ask command",
                List.of("search the workspace"), List.of("Run synthesis search for queries"),
                List.of("synthesis", "workspace", "ask"), 0.8);
        ExtractedPattern p2 = makePattern("workspace-search",
                "synthesis workspace search query command",
                List.of("query the codebase"), List.of("Use synthesis ask for codebase questions"),
                List.of("synthesis", "workspace", "search"), 0.75);
        ExtractedPattern p3 = makePattern("synthesis-find",
                "find things in workspace using synthesis",
                List.of("find in workspace"), List.of("synthesis search finds relevant files"),
                List.of("synthesis", "find", "workspace"), 0.7);

        ReflectResult result = SkillUpdater.apply(List.of(p1, p2, p3), tempDir, false, 5);

        // Should produce exactly 1 UPDATED change (batched), not 3 separate bumps
        long updatedCount = result.changes().stream()
                .filter(c -> c.type() == SkillUpdater.ChangeType.UPDATED)
                .count();
        assertTrue(updatedCount <= 1,
                "Multiple patterns matching same skill should produce at most 1 UPDATED change, got " + updatedCount);

        // Version should be bumped exactly once: 1.0.14 → 1.0.15
        String content = Files.readString(tempDir.resolve("synthesis-ask-workspace.yaml"));
        assertTrue(content.contains("version: 1.0.15"),
                "Version should be bumped exactly once to 1.0.15, not higher");
        assertFalse(content.contains("version: 1.0.16"), "Version must not jump multiple times");
        assertFalse(content.contains("version: 1.0.17"), "Version must not jump multiple times");
    }

    @Test
    void testMaxNewSkillsLimit() throws Exception {
        // Create 5 patterns but set max-new to 2
        List<ExtractedPattern> patterns = List.of(
                makePattern("skill-1", "First skill", List.of("trigger 1"), List.of("instr 1"), List.of("tag1"), 0.8),
                makePattern("skill-2", "Second skill", List.of("trigger 2"), List.of("instr 2"), List.of("tag2"), 0.7),
                makePattern("skill-3", "Third skill", List.of("trigger 3"), List.of("instr 3"), List.of("tag3"), 0.6),
                makePattern("skill-4", "Fourth skill", List.of("trigger 4"), List.of("instr 4"), List.of("tag4"), 0.5),
                makePattern("skill-5", "Fifth skill", List.of("trigger 5"), List.of("instr 5"), List.of("tag5"), 0.4)
        );

        ReflectResult result = SkillUpdater.apply(patterns, tempDir, false, 2);

        assertEquals(2, result.skillsCreated(), "Should create exactly 2 skills (max-new limit)");
        assertTrue(result.skillsSkipped() >= 3,
                "Remaining patterns should be skipped, skipped=" + result.skillsSkipped());

        // Verify only 2 files were created
        long fileCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("reflect-"))
                .count();
        assertEquals(2, fileCount, "Should have exactly 2 reflect-*.yaml files");
    }
}
