package io.exoreaction.synthesis.validate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Rule-based checker that verifies specific factual claims in skill/doc files
 * against live source-of-truth values from the codebase.
 *
 * <p>Each rule targets a specific type of claim (e.g. "N boost fields",
 * "N tests passing") and reports mismatches with the actual value.
 */
public class IntegrityChecker {

    public record IntegrityIssue(Path file, int line, String claim,
                                  String actual, String ruleName) {}

    /**
     * Runs all built-in integrity rules against the provided skill files.
     *
     * @param skillFiles    files to scan for claims
     * @param workspaceRoot workspace root (for locating source files)
     * @return list of integrity issues found
     */
    public List<IntegrityIssue> checkAll(List<Path> skillFiles,
                                          Path workspaceRoot) throws IOException {
        List<IntegrityIssue> issues = new ArrayList<>();

        // Gather actual values once
        int actualBoostFieldCount = countBoostFields(workspaceRoot);
        int actualTestCount = countTestMethods(workspaceRoot);
        List<String> actualMigrations = listMigrationVersions(workspaceRoot);
        int actualPackageCount = countPackages(workspaceRoot);

        for (Path skillFile : skillFiles) {
            List<String> lines;
            try {
                lines = Files.readAllLines(skillFile);
            } catch (IOException e) {
                continue; // skip unreadable files
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNum = i + 1;

                // Rule 1: boost field count
                checkBoostFieldCount(skillFile, lineNum, line,
                        actualBoostFieldCount, issues);

                // Rule 2: test count
                checkTestCount(skillFile, lineNum, line,
                        actualTestCount, issues);

                // Rule 3: Flyway migrations
                checkMigrationVersions(skillFile, lineNum, line,
                        actualMigrations, issues);

                // Rule 4: package count
                checkPackageCount(skillFile, lineNum, line,
                        actualPackageCount, issues);
            }
        }

        return issues;
    }

    // --- Rule implementations ---

    private static final Pattern BOOST_FIELD_COUNT_PATTERN =
            Pattern.compile("(\\d+)\\s*(?:boost(?:ed)?|weighted)\\s*field",
                    Pattern.CASE_INSENSITIVE);

    private void checkBoostFieldCount(Path file, int line, String text,
                                       int actual, List<IntegrityIssue> issues) {
        if (actual < 0) return; // source not found
        Matcher m = BOOST_FIELD_COUNT_PATTERN.matcher(text);
        while (m.find()) {
            int claimed = Integer.parseInt(m.group(1));
            if (claimed != actual) {
                issues.add(new IntegrityIssue(file, line,
                        "\"" + m.group() + "\" (claims " + claimed + " boost fields)",
                        "actual boost field count: " + actual,
                        "BoostFieldCount"));
            }
        }
    }

    private static final Pattern TEST_COUNT_PATTERN =
            Pattern.compile("([\\d,]+)\\s*tests?\\s*(?:passing|pass|total)?",
                    Pattern.CASE_INSENSITIVE);

    private void checkTestCount(Path file, int line, String text,
                                 int actual, List<IntegrityIssue> issues) {
        if (actual < 0) return; // source not found
        Matcher m = TEST_COUNT_PATTERN.matcher(text);
        while (m.find()) {
            try {
                int claimed = Integer.parseInt(m.group(1).replace(",", ""));
                // Allow a tolerance of +/-100 tests (tests change frequently)
                if (Math.abs(claimed - actual) > 100) {
                    issues.add(new IntegrityIssue(file, line,
                            "\"" + m.group() + "\" (claims ~" + claimed + " tests)",
                            "actual test method count: ~" + actual,
                            "TestCount"));
                }
            } catch (NumberFormatException e) {
                // skip
            }
        }
    }

    private static final Pattern MIGRATION_PATTERN =
            Pattern.compile("V(\\d+)[^\\s]*(?:[\\s,]+V(\\d+)[^\\s]*)*",
                    Pattern.CASE_INSENSITIVE);

    private void checkMigrationVersions(Path file, int line, String text,
                                         List<String> actualVersions,
                                         List<IntegrityIssue> issues) {
        // Only flag if the skill explicitly lists migration versions
        if (!text.contains("V1") || actualVersions.isEmpty()) return;
        // Keep this rule simple for now - placeholder for future expansion
    }

    private static final Pattern PACKAGE_COUNT_PATTERN =
            Pattern.compile("(\\d+)\\s*packages?",
                    Pattern.CASE_INSENSITIVE);

    private void checkPackageCount(Path file, int line, String text,
                                    int actual, List<IntegrityIssue> issues) {
        if (actual < 0) return; // source not found
        Matcher m = PACKAGE_COUNT_PATTERN.matcher(text);
        while (m.find()) {
            int claimed = Integer.parseInt(m.group(1));
            if (Math.abs(claimed - actual) > 3) {
                issues.add(new IntegrityIssue(file, line,
                        "\"" + m.group() + "\" (claims " + claimed + " packages)",
                        "actual package count: " + actual,
                        "PackageCount"));
            }
        }
    }

    // --- Source-of-truth helpers ---

    int countBoostFields(Path workspaceRoot) throws IOException {
        Path searchIndex = workspaceRoot.resolve(
                "src/main/java/io/exoreaction/synthesis/index/SearchIndex.java");
        if (!Files.exists(searchIndex)) return -1;

        String source = Files.readString(searchIndex);
        int start = source.indexOf("FIELD_BOOSTS = Map.of(");
        if (start < 0) return -1;
        int end = source.indexOf(");", start);
        if (end < 0) return -1;
        String block = source.substring(start, end);
        int count = 0;
        for (String part : block.split(",")) {
            if (part.contains("DocumentFields.")) count++;
        }
        return count;
    }

    int countTestMethods(Path workspaceRoot) throws IOException {
        Path testRoot = workspaceRoot.resolve("src/test/java");
        if (!Files.exists(testRoot)) return -1;
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(testRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        int idx = 0;
                        while ((idx = content.indexOf("@Test", idx)) >= 0) {
                            count[0]++;
                            idx += 5;
                        }
                    } catch (IOException e) { /* skip */ }
                });
        }
        return count[0];
    }

    List<String> listMigrationVersions(Path workspaceRoot) throws IOException {
        Path migDir = workspaceRoot.resolve("src/main/resources/db/migration");
        if (!Files.exists(migDir)) return List.of();
        List<String> versions = new ArrayList<>();
        try (Stream<Path> entries = Files.list(migDir)) {
            entries.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                   .map(p -> p.getFileName().toString().replaceAll("__.*", ""))
                   .sorted()
                   .forEach(versions::add);
        }
        return versions;
    }

    int countPackages(Path workspaceRoot) throws IOException {
        Path srcRoot = workspaceRoot.resolve("src/main/java");
        if (!Files.exists(srcRoot)) return -1;
        Set<Path> packages = new HashSet<>();
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(Files::isDirectory)
                .filter(p -> !p.equals(srcRoot))
                .filter(p -> {
                    try (Stream<Path> children = Files.list(p)) {
                        return children.anyMatch(f -> f.toString().endsWith(".java"));
                    } catch (IOException e) { return false; }
                })
                .forEach(packages::add);
        }
        return packages.size();
    }
}