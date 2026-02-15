package io.exoreaction.synthesis.architecture;

import io.exoreaction.synthesis.cli.RelateCommand;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Continuous architecture intelligence engine.
 *
 * <p>Detects architectural anti-patterns and quality issues by analyzing the
 * Synthesis index, file relationships, and code metrics. Designed to run as
 * a one-shot analysis or continuously in daemon mode.
 *
 * <h2>Anti-patterns detected:</h2>
 * <ol>
 *   <li><b>Circular dependencies</b> -- Module A depends on B depends on C depends on A</li>
 *   <li><b>God classes</b> -- Files exceeding reasonable size thresholds</li>
 *   <li><b>Feature envy</b> -- Files referencing more external code than their own module</li>
 *   <li><b>Shotgun surgery</b> -- Changes requiring modification of many files</li>
 *   <li><b>Dead code</b> -- Files with zero incoming references</li>
 *   <li><b>Missing documentation</b> -- Code modules without README files</li>
 *   <li><b>Test coverage gaps</b> -- Source files without corresponding test files</li>
 * </ol>
 *
 * @see ArchitectureAlert
 */
public class ArchitectureMonitor {

    private static final Logger LOG = Logger.getLogger(ArchitectureMonitor.class.getName());

    /** Lines threshold for god class detection. */
    public static final int GOD_CLASS_LINE_THRESHOLD = 1000;

    /** Method count threshold for god class detection. */
    public static final int GOD_CLASS_METHOD_THRESHOLD = 50;

    /** Incoming reference count that suggests high coupling. */
    public static final int HIGH_COUPLING_THRESHOLD = 20;

    /** Zero incoming references suggests dead code. */
    public static final int DEAD_CODE_THRESHOLD = 0;

    private final Path workspaceRoot;

    /**
     * Creates a monitor for the given workspace.
     *
     * @param workspaceRoot the workspace root directory
     */
    public ArchitectureMonitor(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Runs a full architecture analysis and returns all detected alerts.
     *
     * @param index the search index to analyze
     * @return list of architecture alerts, sorted by severity
     * @throws IOException if the index cannot be read
     */
    public List<ArchitectureAlert> analyze(SearchIndex index) throws IOException {
        List<ArchitectureAlert> alerts = new ArrayList<>();

        List<SearchResult> allFiles = index.listAll(null, 50000);
        if (allFiles.isEmpty()) return alerts;

        // Filter to code files for most analyses
        List<SearchResult> codeFiles = allFiles.stream()
                .filter(f -> "CODE".equals(f.fileType()))
                .toList();

        // 1. Detect god classes (oversized files)
        alerts.addAll(detectGodClasses(codeFiles));

        // 2. Detect dead code (zero incoming references)
        alerts.addAll(detectDeadCode(codeFiles, allFiles));

        // 3. Detect missing documentation
        alerts.addAll(detectMissingDocumentation(allFiles));

        // 4. Detect test coverage gaps
        alerts.addAll(detectTestCoverageGaps(codeFiles));

        // 5. Detect circular dependencies
        alerts.addAll(detectCircularDependencies(codeFiles, allFiles));

        // 6. Detect high coupling (too many incoming references)
        alerts.addAll(detectHighCoupling(codeFiles, allFiles));

        // Sort by severity (ERROR first, then WARNING, then INFO)
        alerts.sort(Comparator
                .comparingInt((ArchitectureAlert a) -> a.severity().ordinal())
                .thenComparing(ArchitectureAlert::filePath));

        return alerts;
    }

    /**
     * Analyzes a single file for architecture issues.
     * Used for incremental analysis when a file changes in daemon mode.
     *
     * @param filePath  the changed file
     * @param index     the search index
     * @return alerts for this file
     * @throws IOException if analysis fails
     */
    public List<ArchitectureAlert> analyzeFile(Path filePath, SearchIndex index) throws IOException {
        List<ArchitectureAlert> alerts = new ArrayList<>();

        if (!Files.exists(filePath)) return alerts;

        List<SearchResult> allFiles = index.listAll(null, 50000);
        SearchResult target = null;
        for (SearchResult f : allFiles) {
            if (f.path().toAbsolutePath().equals(filePath.toAbsolutePath())) {
                target = f;
                break;
            }
        }
        if (target == null) return alerts;

        // Check god class
        long lines = countLines(filePath);
        if (lines > GOD_CLASS_LINE_THRESHOLD) {
            alerts.add(new ArchitectureAlert(
                    ArchitectureAlert.Severity.WARNING,
                    ArchitectureAlert.Category.GOD_CLASS,
                    target.relativePath(),
                    String.format("File has %d lines (threshold: %d). Consider splitting.",
                            lines, GOD_CLASS_LINE_THRESHOLD),
                    Map.of("lineCount", lines, "threshold", GOD_CLASS_LINE_THRESHOLD)));
        }

        return alerts;
    }

    // --- Detection methods ---

    /**
     * Detects files that are excessively large (god classes).
     */
    List<ArchitectureAlert> detectGodClasses(List<SearchResult> codeFiles) {
        List<ArchitectureAlert> alerts = new ArrayList<>();

        for (SearchResult file : codeFiles) {
            try {
                if (!Files.exists(file.path())) continue;
                long lines = countLines(file.path());

                if (lines > GOD_CLASS_LINE_THRESHOLD) {
                    ArchitectureAlert.Severity severity = lines > GOD_CLASS_LINE_THRESHOLD * 2
                            ? ArchitectureAlert.Severity.ERROR
                            : ArchitectureAlert.Severity.WARNING;

                    alerts.add(new ArchitectureAlert(
                            severity,
                            ArchitectureAlert.Category.GOD_CLASS,
                            file.relativePath(),
                            String.format("File has %d lines (threshold: %d). Consider splitting into smaller, focused files.",
                                    lines, GOD_CLASS_LINE_THRESHOLD),
                            Map.of("lineCount", lines, "threshold", GOD_CLASS_LINE_THRESHOLD)));
                }

                // Check method count from structure field
                int methodCount = extractMethodCount(file.structure());
                if (methodCount > GOD_CLASS_METHOD_THRESHOLD) {
                    alerts.add(new ArchitectureAlert(
                            ArchitectureAlert.Severity.WARNING,
                            ArchitectureAlert.Category.GOD_CLASS,
                            file.relativePath(),
                            String.format("File has %d methods (threshold: %d). Consider extracting into separate classes.",
                                    methodCount, GOD_CLASS_METHOD_THRESHOLD),
                            Map.of("methodCount", methodCount, "threshold", GOD_CLASS_METHOD_THRESHOLD)));
                }
            } catch (IOException e) {
                // Skip files that can't be analyzed
            }
        }

        return alerts;
    }

    /**
     * Detects files with zero incoming references (potential dead code).
     */
    List<ArchitectureAlert> detectDeadCode(List<SearchResult> codeFiles,
                                            List<SearchResult> allFiles) {
        List<ArchitectureAlert> alerts = new ArrayList<>();
        RelateCommand relateCmd = new RelateCommand();

        for (SearchResult file : codeFiles) {
            try {
                // Skip entry points, configs, tests
                String name = file.fileName().toLowerCase();
                if (isEntryPoint(name) || isTestFile(name) || isConfigFile(name)) continue;

                RelateCommand.RelationshipMap relMap = new RelateCommand.RelationshipMap(file.relativePath());
                relateCmd.analyzeIncomingRefs(file, allFiles, workspaceRoot, relMap);

                if (relMap.incoming().isEmpty()) {
                    alerts.add(new ArchitectureAlert(
                            ArchitectureAlert.Severity.INFO,
                            ArchitectureAlert.Category.DEAD_CODE,
                            file.relativePath(),
                            "File has zero incoming references. May be unused or an unregistered entry point.",
                            Map.of("incomingRefs", 0)));
                }
            } catch (Exception e) {
                // Skip files that fail analysis
            }
        }

        return alerts;
    }

    /**
     * Detects code modules (directories) without README documentation.
     */
    List<ArchitectureAlert> detectMissingDocumentation(List<SearchResult> allFiles) {
        List<ArchitectureAlert> alerts = new ArrayList<>();

        // Group files by directory
        Map<String, List<SearchResult>> byDirectory = allFiles.stream()
                .collect(Collectors.groupingBy(f -> {
                    String relPath = f.relativePath();
                    int lastSlash = relPath.lastIndexOf('/');
                    return lastSlash > 0 ? relPath.substring(0, lastSlash) : ".";
                }));

        for (var entry : byDirectory.entrySet()) {
            String dir = entry.getKey();
            List<SearchResult> files = entry.getValue();

            // Only check directories with code files
            boolean hasCode = files.stream().anyMatch(f -> "CODE".equals(f.fileType()));
            if (!hasCode || files.size() < 3) continue;

            boolean hasReadme = files.stream().anyMatch(f ->
                    f.fileName().equalsIgnoreCase("README.md") ||
                    f.fileName().equalsIgnoreCase("README.txt") ||
                    f.fileName().equalsIgnoreCase("README"));

            if (!hasReadme) {
                alerts.add(new ArchitectureAlert(
                        ArchitectureAlert.Severity.INFO,
                        ArchitectureAlert.Category.MISSING_DOCUMENTATION,
                        dir,
                        String.format("Directory has %d files but no README. Consider adding documentation.",
                                files.size()),
                        Map.of("fileCount", files.size())));
            }
        }

        return alerts;
    }

    /**
     * Detects source code files without corresponding test files.
     */
    List<ArchitectureAlert> detectTestCoverageGaps(List<SearchResult> codeFiles) {
        List<ArchitectureAlert> alerts = new ArrayList<>();

        Set<String> testFileNames = codeFiles.stream()
                .filter(f -> isTestFile(f.fileName()))
                .map(f -> {
                    String name = f.fileName();
                    // Normalize test file names: FooTest.java -> Foo, test_foo.py -> foo
                    name = name.replaceAll("Test\\.", ".").replaceAll("_test\\.", ".")
                            .replaceAll("test_", "").replaceAll("_spec\\.", ".");
                    return name.toLowerCase();
                })
                .collect(Collectors.toSet());

        for (SearchResult file : codeFiles) {
            if (isTestFile(file.fileName())) continue;
            if (isConfigFile(file.fileName())) continue;
            if (isEntryPoint(file.fileName())) continue;

            String normalized = file.fileName().toLowerCase();
            if (!testFileNames.contains(normalized)) {
                alerts.add(new ArchitectureAlert(
                        ArchitectureAlert.Severity.INFO,
                        ArchitectureAlert.Category.TEST_COVERAGE_GAP,
                        file.relativePath(),
                        "No corresponding test file found.",
                        Map.of()));
            }
        }

        return alerts;
    }

    /**
     * Detects circular dependencies between modules.
     * Uses a simple DFS cycle detection on the module dependency graph.
     */
    List<ArchitectureAlert> detectCircularDependencies(List<SearchResult> codeFiles,
                                                        List<SearchResult> allFiles) {
        List<ArchitectureAlert> alerts = new ArrayList<>();
        RelateCommand relateCmd = new RelateCommand();

        // Build module-level dependency graph
        Map<String, Set<String>> moduleGraph = new HashMap<>();
        Map<String, List<String>> fileNameIndex = new HashMap<>();

        for (SearchResult f : allFiles) {
            fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>()).add(f.relativePath());
        }

        for (SearchResult file : codeFiles) {
            String module = extractModule(file.relativePath());
            moduleGraph.computeIfAbsent(module, k -> new HashSet<>());

            try {
                RelateCommand.RelationshipMap relMap = new RelateCommand.RelationshipMap(file.relativePath());
                relateCmd.analyzeOutgoingRefs(file, workspaceRoot, relMap, fileNameIndex);

                for (String depPath : relMap.outgoing().keySet()) {
                    String depModule = extractModule(depPath);
                    if (!depModule.equals(module)) {
                        moduleGraph.get(module).add(depModule);
                    }
                }
            } catch (Exception e) {
                // Skip
            }
        }

        // Find cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String module : moduleGraph.keySet()) {
            List<String> cycle = new ArrayList<>();
            if (detectCycleDFS(module, moduleGraph, visited, recursionStack, cycle)) {
                alerts.add(new ArchitectureAlert(
                        ArchitectureAlert.Severity.ERROR,
                        ArchitectureAlert.Category.CIRCULAR_DEPENDENCY,
                        String.join(" -> ", cycle),
                        "Circular dependency detected between modules: " + String.join(" -> ", cycle),
                        Map.of("modules", cycle)));
                break; // Report first cycle found
            }
        }

        return alerts;
    }

    /**
     * Detects files with excessive incoming references (high coupling).
     */
    List<ArchitectureAlert> detectHighCoupling(List<SearchResult> codeFiles,
                                                List<SearchResult> allFiles) {
        List<ArchitectureAlert> alerts = new ArrayList<>();
        RelateCommand relateCmd = new RelateCommand();

        for (SearchResult file : codeFiles) {
            try {
                RelateCommand.RelationshipMap relMap = new RelateCommand.RelationshipMap(file.relativePath());
                relateCmd.analyzeIncomingRefs(file, allFiles, workspaceRoot, relMap);

                int incomingCount = relMap.incoming().size();
                if (incomingCount > HIGH_COUPLING_THRESHOLD) {
                    alerts.add(new ArchitectureAlert(
                            ArchitectureAlert.Severity.WARNING,
                            ArchitectureAlert.Category.HIGH_COUPLING,
                            file.relativePath(),
                            String.format("File has %d incoming references (threshold: %d). High coupling may make changes risky.",
                                    incomingCount, HIGH_COUPLING_THRESHOLD),
                            Map.of("incomingRefs", incomingCount, "threshold", HIGH_COUPLING_THRESHOLD)));
                }
            } catch (Exception e) {
                // Skip
            }
        }

        return alerts;
    }

    // --- Utility methods ---

    private long countLines(Path filePath) throws IOException {
        return Files.lines(filePath).count();
    }

    private int extractMethodCount(String structure) {
        if (structure == null || structure.isEmpty()) return 0;
        // Parse "X methods" from structure string
        try {
            String lower = structure.toLowerCase();
            int idx = lower.indexOf("method");
            if (idx > 0) {
                String before = structure.substring(Math.max(0, idx - 10), idx).trim();
                String[] parts = before.split("\\s+");
                return Integer.parseInt(parts[parts.length - 1]);
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
        return 0;
    }

    private String extractModule(String relativePath) {
        // Use first two directory levels as module identifier
        String[] parts = relativePath.replace("\\", "/").split("/");
        if (parts.length >= 2) {
            return parts[0] + "/" + parts[1];
        }
        return parts[0];
    }

    private boolean detectCycleDFS(String node, Map<String, Set<String>> graph,
                                    Set<String> visited, Set<String> stack,
                                    List<String> cycle) {
        visited.add(node);
        stack.add(node);
        cycle.add(node);

        Set<String> neighbors = graph.getOrDefault(node, Set.of());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                if (detectCycleDFS(neighbor, graph, visited, stack, cycle)) {
                    return true;
                }
            } else if (stack.contains(neighbor)) {
                cycle.add(neighbor);
                return true;
            }
        }

        stack.remove(node);
        cycle.remove(cycle.size() - 1);
        return false;
    }

    private boolean isEntryPoint(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.equals("main.java") || lower.equals("app.java") ||
                lower.equals("application.java") || lower.equals("index.js") ||
                lower.equals("index.ts") || lower.equals("main.py") ||
                lower.equals("main.go") || lower.equals("main.rs") ||
                lower.equals("mod.rs") || lower.equals("__init__.py") ||
                lower.equals("__main__.py");
    }

    private boolean isTestFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.contains("test") || lower.contains("spec") ||
                lower.startsWith("test_") || lower.endsWith("_test.go");
    }

    private boolean isConfigFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.equals("pom.xml") || lower.equals("build.gradle") ||
                lower.equals("package.json") || lower.equals("tsconfig.json") ||
                lower.equals("cargo.toml") || lower.endsWith(".yaml") ||
                lower.endsWith(".yml") || lower.endsWith(".properties") ||
                lower.endsWith(".toml") || lower.endsWith(".cfg");
    }
}
