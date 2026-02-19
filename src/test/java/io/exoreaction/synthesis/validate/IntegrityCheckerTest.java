package io.exoreaction.synthesis.validate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IntegrityChecker}.
 */
class IntegrityCheckerTest {

    private static final Path WORKSPACE_ROOT = Path.of("/src/exoreaction/Synthesis");

    @TempDir
    Path tempDir;

    @Test
    void countBoostFields_returnsCorrectCount() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int count = checker.countBoostFields(WORKSPACE_ROOT);
        assertEquals(6, count,
                "FIELD_BOOSTS should have 6 entries");
    }

    @Test
    void countTestMethods_returnsPositiveCount() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int count = checker.countTestMethods(WORKSPACE_ROOT);
        assertTrue(count > 1000, "Test method count should exceed 1000, was: " + count);
    }

    @Test
    void countPackages_returnsPositiveCount() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int count = checker.countPackages(WORKSPACE_ROOT);
        assertTrue(count > 20, "Package count should exceed 20, was: " + count);
    }

    @Test
    void listMigrationVersions_returnsVersions() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        List<String> versions = checker.listMigrationVersions(WORKSPACE_ROOT);
        assertFalse(versions.isEmpty(), "Should find migration versions");
        assertTrue(versions.contains("V1"), "Should contain V1");
        assertTrue(versions.contains("V2"), "Should contain V2");
        assertTrue(versions.contains("V3"), "Should contain V3");
        assertFalse(versions.contains("V7"), "V7 should be absent");
        assertTrue(versions.contains("V8"), "Should contain V8");
    }

    @Test
    void checkAll_flagsWrongBoostCount() throws IOException {
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "The index uses 3 boost fields for relevance ranking.\n");

        IntegrityChecker checker = new IntegrityChecker();
        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertFalse(issues.isEmpty(), "Should flag incorrect boost field count of 3");
        assertTrue(issues.stream().anyMatch(i -> i.ruleName().equals("BoostFieldCount")));
    }

    @Test
    void checkAll_acceptsCorrectBoostCount() throws IOException {
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "The index uses 6 boost fields for relevance ranking.\n");

        IntegrityChecker checker = new IntegrityChecker();
        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertTrue(issues.stream().noneMatch(i -> i.ruleName().equals("BoostFieldCount")));
    }

    @Test
    void checkAll_flagsVeryStaleTestCount() throws IOException {
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "The project has 100 tests passing.\n");

        IntegrityChecker checker = new IntegrityChecker();
        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertFalse(issues.isEmpty(), "Should flag obviously wrong test count of 100");
        assertTrue(issues.stream().anyMatch(i -> i.ruleName().equals("TestCount")));
    }

    @Test
    void checkAll_toleratesNearbyTestCount() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int actual = checker.countTestMethods(WORKSPACE_ROOT);
        int nearby = actual + 50;

        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "The project has " + nearby + " tests passing.\n");

        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertTrue(issues.stream().noneMatch(i -> i.ruleName().equals("TestCount")));
    }

    @Test
    void checkAll_flagsWrongPackageCount() throws IOException {
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "The codebase has 5 packages.\n");

        IntegrityChecker checker = new IntegrityChecker();
        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertFalse(issues.isEmpty(), "Should flag incorrect package count of 5");
        assertTrue(issues.stream().anyMatch(i -> i.ruleName().equals("PackageCount")));
    }

    @Test
    void checkAll_acceptsNearbyPackageCount() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int actual = checker.countPackages(WORKSPACE_ROOT);

        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "The codebase has " + actual + " packages.\n");

        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertTrue(issues.stream().noneMatch(i -> i.ruleName().equals("PackageCount")));
    }

    @Test
    void checkAll_handlesMultipleIssuesInOneFile() throws IOException {
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile,
                "# Summary\n" +
                "Uses 3 boost fields and has 100 tests passing.\n" +
                "The codebase has 5 packages.\n");

        IntegrityChecker checker = new IntegrityChecker();
        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertTrue(issues.size() >= 3, "Should find at least 3 issues, found: " + issues.size());
    }

    @Test
    void checkAll_returnsEmptyForNoClaimFile() throws IOException {
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, "# Overview\nThis skill describes how to use search.\n");

        IntegrityChecker checker = new IntegrityChecker();
        List<IntegrityChecker.IntegrityIssue> issues =
                checker.checkAll(List.of(skillFile), WORKSPACE_ROOT);

        assertTrue(issues.isEmpty());
    }

    @Test
    void countBoostFields_returnsNegativeForMissingFile() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int count = checker.countBoostFields(tempDir);
        assertEquals(-1, count);
    }

    @Test
    void countTestMethods_returnsNegativeForMissingDir() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        int count = checker.countTestMethods(tempDir);
        assertEquals(-1, count);
    }

    @Test
    void listMigrationVersions_returnsEmptyForMissingDir() throws IOException {
        IntegrityChecker checker = new IntegrityChecker();
        List<String> versions = checker.listMigrationVersions(tempDir);
        assertTrue(versions.isEmpty());
    }
}
