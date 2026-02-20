package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TtlCommand} static helpers and the {@link TtlCommand.TtlRule} record.
 */
class TtlCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // loadRules
    // -------------------------------------------------------------------------

    @Test
    void loadRules_returnsEmptyWhenNoFile() throws IOException {
        List<TtlCommand.TtlRule> rules = TtlCommand.loadRules(workspace);
        assertNotNull(rules);
        assertTrue(rules.isEmpty());
    }

    @Test
    void loadRules_parsesRulesFromYaml() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve(".synthesis"));
        String yaml = """
                rules:
                  - pattern: "TONIGHT-*.md"
                    days: 3
                    createdAt: "2026-02-10"
                  - pattern: "finish-overnight.sh"
                    days: 7
                    createdAt: "2026-02-15"
                """;
        Files.writeString(dir.resolve("ttl-rules.yaml"), yaml);

        List<TtlCommand.TtlRule> rules = TtlCommand.loadRules(workspace);
        assertEquals(2, rules.size());

        assertEquals("TONIGHT-*.md", rules.get(0).pattern());
        assertEquals(3, rules.get(0).days());
        assertEquals(LocalDate.of(2026, 2, 10), rules.get(0).createdAt());

        assertEquals("finish-overnight.sh", rules.get(1).pattern());
        assertEquals(7, rules.get(1).days());
        assertEquals(LocalDate.of(2026, 2, 15), rules.get(1).createdAt());
    }

    // -------------------------------------------------------------------------
    // saveRules
    // -------------------------------------------------------------------------

    @Test
    void saveRules_writesValidYaml() throws IOException {
        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("*.tmp", 1, LocalDate.of(2026, 2, 20)),
                new TtlCommand.TtlRule("report.md", 14, LocalDate.of(2026, 2, 18))
        );

        TtlCommand.saveRules(workspace, rules);

        // Verify the file was created
        Path rulesFile = workspace.resolve(".synthesis/ttl-rules.yaml");
        assertTrue(Files.exists(rulesFile));

        // Load back and verify round-trip
        List<TtlCommand.TtlRule> loaded = TtlCommand.loadRules(workspace);
        assertEquals(2, loaded.size());
        assertEquals("*.tmp", loaded.get(0).pattern());
        assertEquals(1, loaded.get(0).days());
        assertEquals(LocalDate.of(2026, 2, 20), loaded.get(0).createdAt());
        assertEquals("report.md", loaded.get(1).pattern());
        assertEquals(14, loaded.get(1).days());
        assertEquals(LocalDate.of(2026, 2, 18), loaded.get(1).createdAt());
    }

    // -------------------------------------------------------------------------
    // upsertRule
    // -------------------------------------------------------------------------

    @Test
    void upsertRule_addsNewRule() {
        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("old.txt", 5, LocalDate.of(2026, 1, 1))
        );

        List<TtlCommand.TtlRule> updated = TtlCommand.upsertRule(rules, "new.txt", 10);
        assertEquals(2, updated.size());
        assertEquals("old.txt", updated.get(0).pattern());
        assertEquals("new.txt", updated.get(1).pattern());
        assertEquals(10, updated.get(1).days());
    }

    @Test
    void upsertRule_updatesExistingRule() {
        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("TONIGHT-*.md", 3, LocalDate.of(2026, 1, 1)),
                new TtlCommand.TtlRule("other.txt", 5, LocalDate.of(2026, 1, 1))
        );

        List<TtlCommand.TtlRule> updated = TtlCommand.upsertRule(rules, "TONIGHT-*.md", 7);
        assertEquals(2, updated.size());
        // Pattern should be updated with new days and today's date
        assertEquals("TONIGHT-*.md", updated.get(0).pattern());
        assertEquals(7, updated.get(0).days());
        assertEquals(LocalDate.now(), updated.get(0).createdAt());
        // Other rule should be unchanged
        assertEquals("other.txt", updated.get(1).pattern());
        assertEquals(5, updated.get(1).days());
    }

    // -------------------------------------------------------------------------
    // TtlRule
    // -------------------------------------------------------------------------

    @Test
    void TtlRule_isExpired_trueWhenPast() {
        // Created 10 days ago with 3-day TTL -> expired 7 days ago
        TtlCommand.TtlRule rule = new TtlCommand.TtlRule(
                "test.md", 3, LocalDate.now().minusDays(10));
        assertTrue(rule.isExpired());
    }

    @Test
    void TtlRule_isExpired_falseWhenFuture() {
        // Created today with 7-day TTL -> expires in 7 days
        TtlCommand.TtlRule rule = new TtlCommand.TtlRule(
                "test.md", 7, LocalDate.now());
        assertFalse(rule.isExpired());
    }

    @Test
    void TtlRule_expiresOn_correctDate() {
        LocalDate created = LocalDate.of(2026, 2, 20);
        TtlCommand.TtlRule rule = new TtlCommand.TtlRule("test.md", 5, created);
        assertEquals(LocalDate.of(2026, 2, 25), rule.expiresOn());
    }

    // -------------------------------------------------------------------------
    // findExpiredFiles
    // -------------------------------------------------------------------------

    @Test
    void findExpiredFiles_matchesGlobPattern() throws IOException {
        // Create files matching the pattern
        Files.writeString(workspace.resolve("TONIGHT-TEST.md"), "test content");
        Files.writeString(workspace.resolve("TONIGHT-PLAN.md"), "plan content");
        Files.writeString(workspace.resolve("README.md"), "readme");

        // Expired rule
        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("TONIGHT-*.md", 3, LocalDate.now().minusDays(10))
        );

        List<Path> expired = TtlCommand.findExpiredFiles(workspace, rules);
        assertEquals(2, expired.size());

        List<String> names = expired.stream()
                .map(p -> p.getFileName().toString())
                .toList();
        assertTrue(names.contains("TONIGHT-TEST.md"));
        assertTrue(names.contains("TONIGHT-PLAN.md"));
        assertFalse(names.contains("README.md"));
    }

    @Test
    void findExpiredFiles_matchesExactFilename() throws IOException {
        Files.writeString(workspace.resolve("finish-overnight.sh"), "#!/bin/bash");
        Files.writeString(workspace.resolve("other-script.sh"), "#!/bin/bash");

        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("finish-overnight.sh", 7, LocalDate.now().minusDays(10))
        );

        List<Path> expired = TtlCommand.findExpiredFiles(workspace, rules);
        assertEquals(1, expired.size());
        assertEquals("finish-overnight.sh", expired.get(0).getFileName().toString());
    }

    @Test
    void findExpiredFiles_doesNotMatchFutureRules() throws IOException {
        Files.writeString(workspace.resolve("TONIGHT-TEST.md"), "content");

        // Rule that expires in the future (not yet expired)
        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("TONIGHT-*.md", 30, LocalDate.now())
        );

        List<Path> expired = TtlCommand.findExpiredFiles(workspace, rules);
        assertTrue(expired.isEmpty(), "Files matching non-expired rules should not be returned");
    }

    @Test
    void findExpiredFiles_doesNotMatchSubdirectories() throws IOException {
        // Create a file in a subdirectory — should NOT be matched
        Path subdir = Files.createDirectories(workspace.resolve("subdir"));
        Files.writeString(subdir.resolve("TONIGHT-NESTED.md"), "nested");

        // Also create one at root level for comparison
        Files.writeString(workspace.resolve("TONIGHT-ROOT.md"), "root");

        List<TtlCommand.TtlRule> rules = List.of(
                new TtlCommand.TtlRule("TONIGHT-*.md", 3, LocalDate.now().minusDays(10))
        );

        List<Path> expired = TtlCommand.findExpiredFiles(workspace, rules);
        assertEquals(1, expired.size());
        assertEquals("TONIGHT-ROOT.md", expired.get(0).getFileName().toString());
    }
}
