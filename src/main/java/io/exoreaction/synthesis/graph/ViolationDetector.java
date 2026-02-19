package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects architectural violations in the dependency graph.
 *
 * <p>Analyses Java import statements to find:
 * <ul>
 *   <li><b>Layering violations</b> — imports from a higher layer to a lower layer
 *       (e.g., service layer importing CLI layer)</li>
 *   <li><b>Circular dependencies</b> — package-level import cycles
 *       (e.g., config imports core AND core imports config)</li>
 * </ul>
 *
 * <p>Layer assignments follow a standard 4-tier architecture:
 * <pre>
 *   Layer 1 (Foundation):  core, config, util, db
 *   Layer 2 (Index/Graph): index, graph, search, analyzer
 *   Layer 3 (Services):    ai, mcp, lsp, architecture, enrichment, summary, ...
 *   Layer 4 (CLI):         cli
 * </pre>
 *
 * <p>A layering violation occurs when a package at Layer N imports from Layer M
 * where M &gt; N (lower layers must not depend on higher layers).
 *
 * @author Thor Henning Hetland / eXOReaction
 * @see GraphBuilder
 */
public class ViolationDetector {

    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "^import\\s+([\\w.]+);", Pattern.MULTILINE);

    /**
     * Default layer assignments for Synthesis packages.
     * Key = package name (last segment), Value = layer number (1=foundation, 4=CLI).
     */
    private static final Map<String, Integer> DEFAULT_LAYERS = Map.ofEntries(
            // Layer 1: Foundation
            Map.entry("core", 1),
            Map.entry("config", 1),
            Map.entry("util", 1),
            Map.entry("db", 1),

            // Layer 2: Index & Graph
            Map.entry("index", 2),
            Map.entry("graph", 2),
            Map.entry("search", 2),
            Map.entry("analyzer", 2),
            Map.entry("validate", 2),

            // Layer 3: Services
            Map.entry("ai", 3),
            Map.entry("mcp", 3),
            Map.entry("lsp", 3),
            Map.entry("architecture", 3),
            Map.entry("enrichment", 3),
            Map.entry("summary", 3),
            Map.entry("insights", 3),
            Map.entry("report", 3),
            Map.entry("research", 3),
            Map.entry("changelog", 3),
            Map.entry("staging", 3),
            Map.entry("tracking", 3),
            Map.entry("metrics", 3),
            Map.entry("telemetry", 3),
            Map.entry("git", 3),
            Map.entry("org", 3),
            Map.entry("skills", 3),
            Map.entry("workspace", 3),
            Map.entry("update", 3),

            // Layer 4: CLI
            Map.entry("cli", 4)
    );

    /**
     * Human-readable names for each layer.
     */
    private static final Map<Integer, String> LAYER_NAMES = Map.of(
            1, "Foundation",
            2, "Index/Graph",
            3, "Services",
            4, "CLI"
    );

    private final Map<String, Integer> layerAssignments;

    /**
     * Creates a ViolationDetector with default layer assignments.
     */
    public ViolationDetector() {
        this.layerAssignments = DEFAULT_LAYERS;
    }

    /**
     * Creates a ViolationDetector with custom layer assignments.
     *
     * @param customLayers package-name to layer-number mapping
     */
    public ViolationDetector(Map<String, Integer> customLayers) {
        this.layerAssignments = customLayers;
    }

    // -----------------------------------------------------------------------
    // Result types
    // -----------------------------------------------------------------------

    /**
     * A single layering violation: package A (at layer X) imports package B (at layer Y)
     * where Y &gt; X.
     */
    public record LayeringViolation(
            String sourceFile,
            String sourcePackage,
            int sourceLayer,
            String targetClass,
            String targetPackage,
            int targetLayer,
            String suggestion
    ) {
        /**
         * Severity score: higher layer difference = more severe.
         */
        public int severity() {
            return targetLayer - sourceLayer;
        }
    }

    /**
     * A circular dependency between two packages.
     */
    public record CircularDependency(
            String packageA,
            String packageB,
            List<String> aImportsFromB,
            List<String> bImportsFromA,
            boolean isDirect
    ) {
        /**
         * Severity score: direct cycles are worse than transitive.
         */
        public int severity() {
            return isDirect ? 3 : 1;
        }
    }

    /**
     * Complete violation report for a workspace.
     */
    public record ViolationReport(
            List<LayeringViolation> layeringViolations,
            List<CircularDependency> circularDependencies,
            int totalFiles,
            int javaFiles
    ) {
        public boolean hasViolations() {
            return !layeringViolations.isEmpty() || !circularDependencies.isEmpty();
        }

        public int totalViolations() {
            return layeringViolations.size() + circularDependencies.size();
        }
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    /**
     * Analyses all indexed files and detects violations.
     *
     * @param allFiles      all indexed files in the workspace
     * @param workspaceRoot workspace root path for reading file contents
     * @return violation report
     */
    public ViolationReport detect(List<SearchResult> allFiles, Path workspaceRoot) {
        // Filter to Java files only (import analysis is Java-specific for now)
        List<SearchResult> javaFiles = allFiles.stream()
                .filter(f -> "Java".equals(f.language()))
                .toList();

        // Build package → imports map
        Map<String, Map<String, List<String>>> packageImports = buildPackageImportMap(javaFiles);

        // Detect layering violations
        List<LayeringViolation> layeringViolations = detectLayeringViolations(javaFiles);

        // Detect circular dependencies
        List<CircularDependency> circularDeps = detectCircularDependencies(packageImports);

        return new ViolationReport(layeringViolations, circularDeps,
                allFiles.size(), javaFiles.size());
    }

    /**
     * Detects layering violations: imports going from a lower layer to a higher layer.
     */
    List<LayeringViolation> detectLayeringViolations(List<SearchResult> javaFiles) {
        List<LayeringViolation> violations = new ArrayList<>();

        for (SearchResult file : javaFiles) {
            String sourcePackage = extractPackageName(file.relativePath());
            int sourceLayer = getLayer(sourcePackage);
            if (sourceLayer == 0) continue; // Unknown package, skip

            List<String> imports = extractImports(file);
            for (String imp : imports) {
                String targetPackage = extractPackageFromImport(imp);
                if (targetPackage == null || targetPackage.equals(sourcePackage)) continue;

                int targetLayer = getLayer(targetPackage);
                if (targetLayer == 0) continue; // Unknown, skip

                if (targetLayer > sourceLayer) {
                    String suggestion = buildSuggestion(sourcePackage, sourceLayer,
                            targetPackage, targetLayer, imp);
                    violations.add(new LayeringViolation(
                            file.relativePath(), sourcePackage, sourceLayer,
                            imp, targetPackage, targetLayer, suggestion));
                }
            }
        }

        return violations;
    }

    /**
     * Detects circular dependencies between packages.
     */
    List<CircularDependency> detectCircularDependencies(
            Map<String, Map<String, List<String>>> packageImports) {
        List<CircularDependency> cycles = new ArrayList<>();
        Set<String> checkedPairs = new HashSet<>();

        for (String pkgA : packageImports.keySet()) {
            Map<String, List<String>> aImports = packageImports.get(pkgA);
            if (aImports == null) continue;

            for (String pkgB : aImports.keySet()) {
                if (pkgB.equals(pkgA)) continue;

                // Avoid duplicate pair reports
                String pairKey = pkgA.compareTo(pkgB) < 0
                        ? pkgA + "|" + pkgB : pkgB + "|" + pkgA;
                if (checkedPairs.contains(pairKey)) continue;
                checkedPairs.add(pairKey);

                // Check if B also imports from A
                Map<String, List<String>> bImports = packageImports.get(pkgB);
                if (bImports != null && bImports.containsKey(pkgA)) {
                    List<String> aToB = aImports.get(pkgB);
                    List<String> bToA = bImports.get(pkgA);
                    cycles.add(new CircularDependency(
                            pkgA, pkgB, aToB, bToA, true));
                }
            }
        }

        return cycles;
    }

    // -----------------------------------------------------------------------
    // Import extraction
    // -----------------------------------------------------------------------

    /**
     * Builds a map: sourcePackage → { targetPackage → [imported classes] }.
     */
    Map<String, Map<String, List<String>>> buildPackageImportMap(List<SearchResult> javaFiles) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();

        for (SearchResult file : javaFiles) {
            String sourcePackage = extractPackageName(file.relativePath());
            if (sourcePackage == null) continue;

            List<String> imports = extractImports(file);
            for (String imp : imports) {
                String targetPackage = extractPackageFromImport(imp);
                if (targetPackage == null || targetPackage.equals(sourcePackage)) continue;

                result.computeIfAbsent(sourcePackage, k -> new LinkedHashMap<>())
                        .computeIfAbsent(targetPackage, k -> new ArrayList<>())
                        .add(imp);
            }
        }

        return result;
    }

    /**
     * Extracts Java import statements from a file.
     */
    List<String> extractImports(SearchResult file) {
        List<String> imports = new ArrayList<>();
        try {
            Path filePath = file.path();
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) return imports;

            String content = FileUtils.readPreview(filePath, 50_000);
            Matcher m = JAVA_IMPORT.matcher(content);
            while (m.find()) {
                String imp = m.group(1);
                // Filter to project-local imports only
                if (isProjectImport(imp)) {
                    imports.add(imp);
                }
            }
        } catch (IOException e) {
            // Skip unreadable files
        }
        return imports;
    }

    // -----------------------------------------------------------------------
    // Package / layer resolution
    // -----------------------------------------------------------------------

    /**
     * Extracts the Synthesis sub-package name from a relative file path.
     * For example, {@code "src/main/java/.../cli/GraphCommand.java"} yields {@code "cli"}.
     *
     * @return the sub-package name, or null if not resolvable
     */
    String extractPackageName(String relativePath) {
        if (relativePath == null) return null;

        // Look for synthesis package structure
        int idx = relativePath.indexOf("io/exoreaction/synthesis/");
        if (idx >= 0) {
            String afterSynthesis = relativePath.substring(idx + "io/exoreaction/synthesis/".length());
            int slash = afterSynthesis.indexOf('/');
            if (slash > 0) {
                return afterSynthesis.substring(0, slash);
            }
        }

        // Fallback: parent directory
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash > 0) {
            String parentDir = relativePath.substring(0, lastSlash);
            int prevSlash = parentDir.lastIndexOf('/');
            return prevSlash >= 0 ? parentDir.substring(prevSlash + 1) : parentDir;
        }

        return null;
    }

    /**
     * Extracts the Synthesis sub-package from a fully-qualified import.
     * For example, {@code "io.exoreaction.synthesis.cli.RelateCommand"} yields {@code "cli"}.
     *
     * @return the sub-package name, or null if not a Synthesis import
     */
    String extractPackageFromImport(String importStr) {
        if (importStr == null) return null;

        String prefix = "io.exoreaction.synthesis.";
        if (!importStr.startsWith(prefix)) return null;

        String remainder = importStr.substring(prefix.length());
        int dot = remainder.indexOf('.');
        if (dot > 0) {
            return remainder.substring(0, dot);
        }

        return null; // Root package class (like SynthesisApp)
    }

    /**
     * Returns the layer number for a given package, or 0 if unknown.
     */
    int getLayer(String packageName) {
        if (packageName == null) return 0;
        return layerAssignments.getOrDefault(packageName, 0);
    }

    /**
     * Returns the human-readable layer name.
     */
    public String getLayerName(int layer) {
        return LAYER_NAMES.getOrDefault(layer, "Unknown");
    }

    /**
     * Returns the layer assignments (for testing).
     */
    Map<String, Integer> getLayerAssignments() {
        return Collections.unmodifiableMap(layerAssignments);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Checks if an import is a project-local import (within io.exoreaction.synthesis).
     */
    private boolean isProjectImport(String importStr) {
        return importStr.startsWith("io.exoreaction.synthesis.");
    }

    /**
     * Builds a human-readable suggestion for fixing a layering violation.
     */
    private String buildSuggestion(String sourcePackage, int sourceLayer,
                                    String targetPackage, int targetLayer,
                                    String importedClass) {
        String className = importedClass.contains(".")
                ? importedClass.substring(importedClass.lastIndexOf('.') + 1)
                : importedClass;

        return String.format("Layer %d (%s) imports Layer %d (%s) — " +
                        "extract %s logic to a service class in Layer %d or below",
                sourceLayer, getLayerName(sourceLayer),
                targetLayer, getLayerName(targetLayer),
                className,
                sourceLayer);
    }
}
