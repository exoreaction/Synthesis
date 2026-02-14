package io.exoreaction.synthesis.insights;

import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Calculates knowledge graph insights and metrics from indexed files.
 *
 * <p>Provides four categories of analysis:
 * <ul>
 *   <li>Connectivity: references, hubs, orphans, circular dependencies</li>
 *   <li>Complexity: file sizes, nesting depths, directory sizes</li>
 *   <li>Quality: documentation coverage, test ratios, dead code</li>
 *   <li>Architecture: layering, cohesion, coupling</li>
 * </ul>
 */
public class InsightsEngine {

    // Reference detection patterns (shared with RelateCommand)
    private static final Pattern JAVA_IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)", Pattern.MULTILINE);
    private static final Pattern GENERIC_FILE_REF = Pattern.compile(
            "(?:['\"`])([\\w./-]+\\.(?:java|py|js|ts|md|yaml|yml|json|xml|go|rs|kt))['\"`]");

    /**
     * Result of a full insights analysis.
     */
    public record InsightsReport(
            ConnectivityMetrics connectivity,
            ComplexityMetrics complexity,
            QualityMetrics quality,
            ArchitectureMetrics architecture,
            List<String> warnings,
            List<String> recommendations
    ) {}

    public record ConnectivityMetrics(
            Map<String, Integer> incomingRefs,    // file -> incoming reference count
            Map<String, Integer> outgoingRefs,    // file -> outgoing reference count
            List<String> orphanedFiles,
            List<List<String>> circularClusters,
            double averageRefsPerFile,
            int totalReferences
    ) {}

    public record ComplexityMetrics(
            Map<String, Integer> filesPerDirectory,
            Map<Integer, Integer> nestingDepthDistribution,
            Map<String, Long> fileSizeDistribution,  // bucket -> count
            Map<String, Long> typeRatio,
            List<SearchResult> largestFiles,
            double averageFileSize,
            int maxNestingDepth
    ) {}

    public record QualityMetrics(
            double documentationCoverage,
            double testRatio,
            List<String> deadCodeCandidates,
            List<String> hotspotFiles,
            int directoriesWithReadme,
            int totalDirectories,
            int testFiles,
            int sourceFiles
    ) {}

    public record ArchitectureMetrics(
            Map<String, Double> directoryCohesion,
            Map<String, Integer> directoryCoupling,
            List<String> layeringViolations,
            int moduleCount
    ) {}

    /**
     * Analyzes the given set of indexed files and produces a comprehensive insights report.
     */
    public InsightsReport analyze(List<SearchResult> allFiles, Path workspaceRoot) {
        // Build reference graph
        Map<String, Set<String>> outgoingGraph = new LinkedHashMap<>();
        Map<String, Set<String>> incomingGraph = new LinkedHashMap<>();
        Map<String, String> fileNameIndex = new HashMap<>();

        // Initialize graphs
        for (SearchResult file : allFiles) {
            outgoingGraph.put(file.relativePath(), new LinkedHashSet<>());
            incomingGraph.put(file.relativePath(), new LinkedHashSet<>());
            fileNameIndex.put(file.fileName(), file.relativePath());
        }

        // Build reference graph by analyzing file content
        for (SearchResult file : allFiles) {
            try {
                if (!Files.exists(file.path()) || !Files.isReadable(file.path())) continue;
                String content = FileUtils.readPreview(file.path(), 30_000);
                if (content.isEmpty()) continue;

                Set<String> references = extractReferences(content, file, fileNameIndex);
                for (String ref : references) {
                    if (!ref.equals(file.relativePath()) && outgoingGraph.containsKey(ref)) {
                        outgoingGraph.get(file.relativePath()).add(ref);
                        incomingGraph.get(ref).add(file.relativePath());
                    }
                }
            } catch (IOException e) {
                // Skip unreadable files
            }
        }

        ConnectivityMetrics connectivity = computeConnectivity(outgoingGraph, incomingGraph);
        ComplexityMetrics complexity = computeComplexity(allFiles, workspaceRoot);
        QualityMetrics quality = computeQuality(allFiles, workspaceRoot, incomingGraph);
        ArchitectureMetrics architecture = computeArchitecture(allFiles, outgoingGraph);

        List<String> warnings = generateWarnings(connectivity, complexity, quality);
        List<String> recommendations = generateRecommendations(connectivity, complexity, quality, architecture);

        return new InsightsReport(connectivity, complexity, quality, architecture, warnings, recommendations);
    }

    private Set<String> extractReferences(String content, SearchResult file,
                                           Map<String, String> fileNameIndex) {
        Set<String> references = new LinkedHashSet<>();

        // Java imports
        if ("Java".equals(file.language())) {
            Matcher m = JAVA_IMPORT.matcher(content);
            while (m.find()) {
                String imp = m.group(1);
                String[] parts = imp.split("\\.");
                String className = parts[parts.length - 1] + ".java";
                String resolved = fileNameIndex.get(className);
                if (resolved != null) references.add(resolved);
            }
        }

        // Markdown links
        if ("MARKDOWN".equals(file.fileType())) {
            Matcher m = MARKDOWN_LINK.matcher(content);
            while (m.find()) {
                String link = m.group(2);
                if (!link.startsWith("http") && !link.startsWith("#")) {
                    String fileName = link.contains("/") ? link.substring(link.lastIndexOf('/') + 1) : link;
                    // Remove anchor
                    if (fileName.contains("#")) fileName = fileName.substring(0, fileName.indexOf('#'));
                    String resolved = fileNameIndex.get(fileName);
                    if (resolved != null) references.add(resolved);
                }
            }
        }

        // Generic file references
        Matcher m = GENERIC_FILE_REF.matcher(content);
        while (m.find()) {
            String ref = m.group(1);
            String fileName = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            String resolved = fileNameIndex.get(fileName);
            if (resolved != null) references.add(resolved);
        }

        return references;
    }

    private ConnectivityMetrics computeConnectivity(Map<String, Set<String>> outgoing,
                                                     Map<String, Set<String>> incoming) {
        Map<String, Integer> incomingRefs = new LinkedHashMap<>();
        Map<String, Integer> outgoingRefs = new LinkedHashMap<>();
        List<String> orphaned = new ArrayList<>();
        int totalRefs = 0;

        for (Map.Entry<String, Set<String>> entry : incoming.entrySet()) {
            incomingRefs.put(entry.getKey(), entry.getValue().size());
        }
        for (Map.Entry<String, Set<String>> entry : outgoing.entrySet()) {
            outgoingRefs.put(entry.getKey(), entry.getValue().size());
            totalRefs += entry.getValue().size();
        }

        // Find orphaned files (no incoming AND no outgoing)
        for (String file : outgoing.keySet()) {
            if (incoming.getOrDefault(file, Set.of()).isEmpty() &&
                outgoing.getOrDefault(file, Set.of()).isEmpty()) {
                orphaned.add(file);
            }
        }

        // Detect circular dependencies using Tarjan's algorithm (simplified)
        List<List<String>> circularClusters = detectCircularDeps(outgoing);

        double avgRefs = outgoing.isEmpty() ? 0 : (double) totalRefs / outgoing.size();

        return new ConnectivityMetrics(incomingRefs, outgoingRefs, orphaned,
                circularClusters, avgRefs, totalRefs);
    }

    private List<List<String>> detectCircularDeps(Map<String, Set<String>> graph) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                List<String> path = new ArrayList<>();
                findCycles(node, graph, visited, inStack, path, cycles);
            }
        }

        return cycles;
    }

    private void findCycles(String node, Map<String, Set<String>> graph,
                            Set<String> visited, Set<String> inStack,
                            List<String> path, List<List<String>> cycles) {
        visited.add(node);
        inStack.add(node);
        path.add(node);

        for (String neighbor : graph.getOrDefault(node, Set.of())) {
            if (!visited.contains(neighbor)) {
                findCycles(neighbor, graph, visited, inStack, path, cycles);
            } else if (inStack.contains(neighbor)) {
                // Found a cycle
                int start = path.indexOf(neighbor);
                if (start >= 0 && cycles.size() < 10) {
                    List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
                    cycle.add(neighbor);
                    cycles.add(cycle);
                }
            }
        }

        path.remove(path.size() - 1);
        inStack.remove(node);
    }

    private ComplexityMetrics computeComplexity(List<SearchResult> allFiles, Path workspaceRoot) {
        // Files per directory
        Map<String, Integer> filesPerDir = new TreeMap<>();
        for (SearchResult file : allFiles) {
            String dir = file.relativePath().contains("/") ?
                    file.relativePath().substring(0, file.relativePath().lastIndexOf('/')) : ".";
            filesPerDir.merge(dir, 1, Integer::sum);
        }

        // Nesting depth distribution
        Map<Integer, Integer> nestingDist = new TreeMap<>();
        int maxNesting = 0;
        for (SearchResult file : allFiles) {
            int depth = file.relativePath().split("/").length - 1;
            nestingDist.merge(depth, 1, Integer::sum);
            maxNesting = Math.max(maxNesting, depth);
        }

        // File size distribution
        Map<String, Long> sizeDistribution = new LinkedHashMap<>();
        long tiny = 0, small = 0, medium = 0, large = 0, huge = 0;
        for (SearchResult file : allFiles) {
            if (file.sizeBytes() < 1024) tiny++;
            else if (file.sizeBytes() < 10240) small++;
            else if (file.sizeBytes() < 102400) medium++;
            else if (file.sizeBytes() < 1048576) large++;
            else huge++;
        }
        sizeDistribution.put("<1KB", tiny);
        sizeDistribution.put("1-10KB", small);
        sizeDistribution.put("10-100KB", medium);
        sizeDistribution.put("100KB-1MB", large);
        sizeDistribution.put(">1MB", huge);

        // Type ratio
        Map<String, Long> typeRatio = allFiles.stream()
                .filter(f -> f.fileType() != null)
                .collect(Collectors.groupingBy(SearchResult::fileType, Collectors.counting()));

        // Largest files
        List<SearchResult> largest = allFiles.stream()
                .sorted(Comparator.comparingLong(SearchResult::sizeBytes).reversed())
                .limit(10)
                .toList();

        double avgSize = allFiles.isEmpty() ? 0 :
                allFiles.stream().mapToLong(SearchResult::sizeBytes).average().orElse(0);

        return new ComplexityMetrics(filesPerDir, nestingDist, sizeDistribution,
                typeRatio, largest, avgSize, maxNesting);
    }

    private QualityMetrics computeQuality(List<SearchResult> allFiles, Path workspaceRoot,
                                           Map<String, Set<String>> incomingGraph) {
        // Documentation coverage: directories with README
        Set<String> allDirs = new TreeSet<>();
        Set<String> dirsWithReadme = new TreeSet<>();
        for (SearchResult file : allFiles) {
            String dir = file.relativePath().contains("/") ?
                    file.relativePath().substring(0, file.relativePath().lastIndexOf('/')) : ".";
            allDirs.add(dir);
            if (file.fileName().equalsIgnoreCase("README.md") ||
                file.fileName().equalsIgnoreCase("README.txt") ||
                file.fileName().equalsIgnoreCase("README")) {
                dirsWithReadme.add(dir);
            }
        }

        double docCoverage = allDirs.isEmpty() ? 0 :
                (double) dirsWithReadme.size() / allDirs.size() * 100;

        // Test ratio
        int testFiles = 0;
        int sourceFiles = 0;
        for (SearchResult file : allFiles) {
            if ("CODE".equals(file.fileType())) {
                String name = file.fileName().toLowerCase();
                if (name.contains("test") || name.contains("spec") ||
                    file.relativePath().contains("/test/") || file.relativePath().contains("/tests/") ||
                    file.relativePath().contains("/__tests__/")) {
                    testFiles++;
                } else {
                    sourceFiles++;
                }
            }
        }

        double testRatio = sourceFiles == 0 ? 0 : (double) testFiles / sourceFiles;

        // Dead code candidates (0 incoming refs, not entry points)
        List<String> deadCode = new ArrayList<>();
        Set<String> entryPoints = Set.of("Main.java", "App.java", "Application.java",
                "index.js", "index.ts", "main.py", "main.go", "mod.rs",
                "pom.xml", "package.json", "build.gradle", "Makefile",
                "README.md", "CLAUDE.md", "Dockerfile");

        for (Map.Entry<String, Set<String>> entry : incomingGraph.entrySet()) {
            if (entry.getValue().isEmpty()) {
                String fileName = entry.getKey().contains("/") ?
                        entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1) : entry.getKey();
                // Skip entry points, test files, and config files
                if (!entryPoints.contains(fileName) &&
                    !fileName.toLowerCase().contains("test") &&
                    !fileName.toLowerCase().contains("spec") &&
                    !fileName.startsWith(".")) {
                    deadCode.add(entry.getKey());
                }
            }
        }
        // Limit to top 20
        if (deadCode.size() > 20) deadCode = deadCode.subList(0, 20);

        // Hotspot files: high incoming refs AND not small files
        List<String> hotspots = new ArrayList<>();
        for (SearchResult file : allFiles) {
            int inRefs = incomingGraph.getOrDefault(file.relativePath(), Set.of()).size();
            if (inRefs >= 5 && file.sizeBytes() > 10_000) {
                hotspots.add(file.relativePath() + " (" + inRefs + " refs, " +
                        FileUtils.formatSize(file.sizeBytes()) + ")");
            }
        }
        hotspots.sort(Comparator.reverseOrder());
        if (hotspots.size() > 10) hotspots = hotspots.subList(0, 10);

        return new QualityMetrics(docCoverage, testRatio, deadCode, hotspots,
                dirsWithReadme.size(), allDirs.size(), testFiles, sourceFiles);
    }

    private ArchitectureMetrics computeArchitecture(List<SearchResult> allFiles,
                                                     Map<String, Set<String>> outgoing) {
        // Group files by top-level directory (module)
        Map<String, Set<String>> modules = new LinkedHashMap<>();
        for (SearchResult file : allFiles) {
            String[] parts = file.relativePath().split("/");
            String module = parts.length > 1 ? parts[0] : ".";
            modules.computeIfAbsent(module, k -> new HashSet<>()).add(file.relativePath());
        }

        // Directory cohesion: % of references that stay within the same directory
        Map<String, Double> cohesion = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> module : modules.entrySet()) {
            int internalRefs = 0;
            int totalRefs = 0;
            for (String file : module.getValue()) {
                Set<String> refs = outgoing.getOrDefault(file, Set.of());
                for (String ref : refs) {
                    totalRefs++;
                    if (module.getValue().contains(ref)) {
                        internalRefs++;
                    }
                }
            }
            double coh = totalRefs == 0 ? 1.0 : (double) internalRefs / totalRefs;
            cohesion.put(module.getKey(), coh);
        }

        // Directory coupling: how many other modules does each module reference
        Map<String, Integer> coupling = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> module : modules.entrySet()) {
            Set<String> referencedModules = new HashSet<>();
            for (String file : module.getValue()) {
                Set<String> refs = outgoing.getOrDefault(file, Set.of());
                for (String ref : refs) {
                    String refModule = ref.split("/").length > 1 ? ref.split("/")[0] : ".";
                    if (!refModule.equals(module.getKey())) {
                        referencedModules.add(refModule);
                    }
                }
            }
            coupling.put(module.getKey(), referencedModules.size());
        }

        // Simple layering violation detection
        List<String> violations = new ArrayList<>();
        // Check for bi-directional module dependencies
        for (Map.Entry<String, Set<String>> moduleA : modules.entrySet()) {
            Set<String> aRefsModules = new HashSet<>();
            for (String file : moduleA.getValue()) {
                for (String ref : outgoing.getOrDefault(file, Set.of())) {
                    String refModule = ref.split("/").length > 1 ? ref.split("/")[0] : ".";
                    aRefsModules.add(refModule);
                }
            }

            for (String targetModule : aRefsModules) {
                if (targetModule.equals(moduleA.getKey())) continue;
                // Check reverse: does targetModule also reference moduleA?
                Set<String> targetFiles = modules.getOrDefault(targetModule, Set.of());
                for (String file : targetFiles) {
                    for (String ref : outgoing.getOrDefault(file, Set.of())) {
                        String refModule = ref.split("/").length > 1 ? ref.split("/")[0] : ".";
                        if (refModule.equals(moduleA.getKey())) {
                            String violation = moduleA.getKey() + " <-> " + targetModule +
                                    " (bidirectional dependency)";
                            if (!violations.contains(violation) && violations.size() < 10) {
                                violations.add(violation);
                            }
                        }
                    }
                }
            }
        }

        return new ArchitectureMetrics(cohesion, coupling, violations, modules.size());
    }

    private List<String> generateWarnings(ConnectivityMetrics conn, ComplexityMetrics comp,
                                           QualityMetrics qual) {
        List<String> warnings = new ArrayList<>();

        if (!conn.circularClusters().isEmpty()) {
            warnings.add("Circular dependencies: " + conn.circularClusters().size() + " clusters detected");
        }

        double orphanPct = conn.orphanedFiles().isEmpty() ? 0 :
                (double) conn.orphanedFiles().size() / (conn.incomingRefs().size()) * 100;
        if (orphanPct > 20) {
            warnings.add(String.format("High orphan rate: %.1f%% of files have no references", orphanPct));
        }

        for (SearchResult large : comp.largestFiles()) {
            if (large.sizeBytes() > 500_000) {
                warnings.add("Very large file: " + large.relativePath() + " (" +
                        FileUtils.formatSize(large.sizeBytes()) + ")");
            }
        }

        if (qual.documentationCoverage() < 30) {
            warnings.add(String.format("Low documentation coverage: %.0f%% directories have README",
                    qual.documentationCoverage()));
        }

        if (qual.testRatio() < 0.5 && qual.sourceFiles() > 10) {
            warnings.add(String.format("Low test ratio: %.1f:1 (test:source)",
                    qual.testRatio()));
        }

        return warnings;
    }

    private List<String> generateRecommendations(ConnectivityMetrics conn, ComplexityMetrics comp,
                                                   QualityMetrics qual, ArchitectureMetrics arch) {
        List<String> recs = new ArrayList<>();

        if (!qual.hotspotFiles().isEmpty()) {
            recs.add("Review hotspot files (high references + large size = change risk)");
        }

        if (!qual.deadCodeCandidates().isEmpty()) {
            recs.add("Review " + qual.deadCodeCandidates().size() +
                    " dead code candidates for potential removal");
        }

        if (!arch.layeringViolations().isEmpty()) {
            recs.add("Resolve " + arch.layeringViolations().size() +
                    " bidirectional module dependencies");
        }

        if (qual.documentationCoverage() < 50) {
            recs.add("Add README files to " + (qual.totalDirectories() - qual.directoriesWithReadme()) +
                    " undocumented directories");
        }

        // Check for bloated directories
        comp.filesPerDirectory().entrySet().stream()
                .filter(e -> e.getValue() > 30)
                .findFirst()
                .ifPresent(e -> recs.add("Consider splitting " + e.getKey() +
                        " (" + e.getValue() + " files) into sub-directories"));

        return recs;
    }
}
