package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

/**
 * Detects which test classes cover a given source file using two strategies:
 * 1. Convention-based: MaintenanceService.java -> MaintenanceServiceTest.java
 * 2. Import-based: test files that import the source class
 */
public class TestCoverageAnalyzer {

    public record TestCoverageResult(
        String sourceFile,
        List<TestClass> testClasses,
        int testMethodCount
    ) {}

    public record TestClass(
        String relativePath,
        String fileName,
        String detectionMethod,
        int testMethodCount
    ) {}

    /**
     * Find test classes that cover the given source file.
     *
     * @param sourceFile     the source file to find tests for
     * @param allFiles       all indexed files (to find test files)
     * @param workspaceRoot  for reading file content
     * @return coverage result
     */
    public TestCoverageResult findTests(SearchResult sourceFile,
                                        List<SearchResult> allFiles,
                                        Path workspaceRoot) throws IOException {
        String baseName = stripExtension(sourceFile.fileName());
        List<TestClass> found = new ArrayList<>();

        // Strategy 1: Convention-based (ClassName -> ClassNameTest)
        String testName = baseName + "Test.java";
        for (SearchResult f : allFiles) {
            if (f.fileName().equals(testName)) {
                int methodCount = countTestMethods(f, workspaceRoot);
                found.add(new TestClass(f.relativePath(), f.fileName(), "convention", methodCount));
            }
        }

        // Strategy 2: Import-based (test files that import the source class)
        String importTarget = baseName;
        for (SearchResult f : allFiles) {
            if (!f.fileName().endsWith("Test.java") && !f.fileName().endsWith("Tests.java")) continue;
            if (f.equals(sourceFile)) continue;
            // Avoid duplicates from convention matching
            if (found.stream().anyMatch(tc -> tc.relativePath().equals(f.relativePath()))) continue;

            Path filePath = workspaceRoot.resolve(f.relativePath());
            if (!Files.exists(filePath)) continue;
            String content = Files.readString(filePath);
            // Check if test file imports the source class
            if (content.contains("import ") && content.contains(importTarget)) {
                int methodCount = countTestMethods(f, workspaceRoot);
                found.add(new TestClass(f.relativePath(), f.fileName(), "import", methodCount));
            }
        }

        int total = found.stream().mapToInt(TestClass::testMethodCount).sum();
        return new TestCoverageResult(sourceFile.relativePath(), found, total);
    }

    /**
     * Find source files that have NO test class (convention-based check only).
     */
    public List<SearchResult> findUntested(List<SearchResult> allFiles) {
        // Build set of base names that have a test
        Set<String> testedBaseNames = new HashSet<>();
        for (SearchResult f : allFiles) {
            String name = f.fileName();
            if (name.endsWith("Test.java")) {
                testedBaseNames.add(name.replace("Test.java", ".java"));
            } else if (name.endsWith("Tests.java")) {
                testedBaseNames.add(name.replace("Tests.java", ".java"));
            }
        }

        return allFiles.stream()
            .filter(f -> f.fileName().endsWith(".java"))
            .filter(f -> !f.fileName().endsWith("Test.java") && !f.fileName().endsWith("Tests.java"))
            .filter(f -> !isTestFile(f.relativePath()))
            .filter(f -> !testedBaseNames.contains(f.fileName()))
            .sorted(Comparator.comparing(SearchResult::fileName))
            .collect(Collectors.toList());
    }

    private boolean isTestFile(String relativePath) {
        return relativePath.contains("src/test/") || relativePath.contains("/test/");
    }

    private int countTestMethods(SearchResult f, Path workspaceRoot) {
        Path p = workspaceRoot.resolve(f.relativePath());
        if (!Files.exists(p)) return 0;
        try {
            String content = Files.readString(p);
            int count = 0;
            int idx = 0;
            String marker = "@" + "Test";
            while ((idx = content.indexOf(marker, idx)) >= 0) {
                count++;
                idx += 5;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf(".");
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
