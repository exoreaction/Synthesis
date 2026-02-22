package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * {@code synthesis code-graph} -- manage the persisted code knowledge graph.
 *
 * <p>The code knowledge graph stores class-level dependency edges (imports,
 * extends, implements) and cross-format links (SQL&rarr;Java) in SQLite.
 * Once populated, {@code relate} and {@code impact} commands use instant
 * database lookups instead of reading source files.
 *
 * <p>Usage:
 * <pre>
 *   synthesis code-graph extract                # full extraction
 *   synthesis code-graph extract --incremental  # only changed files
 *   synthesis code-graph extract --stats        # show extraction statistics
 *   synthesis code-graph extract --dry-run      # show what would be extracted
 *   synthesis code-graph describe               # show module profiles
 *   synthesis code-graph describe --module cli  # filter by module substring
 *   synthesis code-graph health                 # show code health signals
 *   synthesis code-graph health --errors-only   # HIGH severity only
 * </pre>
 *
 * @since v1.9.9 (CKG-1.06)
 */
@Command(
        name = "code-graph",
        aliases = {"cg"},
        description = "Code knowledge graph: dependency DAG, health signals, quality gaps",
        mixinStandardHelpOptions = true,
        subcommands = {
                CodeGraphCommand.ExtractSub.class,
                CodeGraphCommand.DescribeSub.class,
                CodeGraphCommand.HealthSub.class,
                CodeGraphCommand.GapsSub.class
        }
)
public class CodeGraphCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = "--cycles", description = "Show circular dependencies")
    boolean cycles;

    @Option(names = "--hotspots", description = "Show unstable high-coupling packages")
    boolean hotspots;

    @Option(names = "--instability", description = "Sort packages by instability descending")
    boolean instability;

    @Option(names = "--layers", description = "Show layer diagram with violations")
    boolean layers;

    @Option(names = "--cross-format", description = "Show SQL/YAML->Java cross-format links")
    boolean crossFormat;

    @Option(names = "--format", description = "Output format: text (default) or mermaid")
    String format = "text";

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            String wsPath = workspaceRoot.toString();

            CodeGraphRepository repo = new CodeGraphRepository();
            DagRenderer renderer = new DagRenderer(repo);

            // Check if graph has data
            int profileCount = countProfiles(conn, wsPath);
            if (profileCount == 0) {
                System.out.println();
                System.out.println("No code graph data. Run first: synthesis code-graph extract -d <workspace>");
                System.out.println();
                return 0;
            }

            if (cycles) {
                return showCycles(renderer, wsPath, conn);
            }
            if (hotspots) {
                return showHotspots(renderer, wsPath, conn);
            }
            if (instability) {
                return showInstability(renderer, wsPath, conn);
            }
            if (crossFormat) {
                return showCrossFormat(repo, wsPath, conn);
            }

            // Default (no flags or --layers): show full DAG
            if ("mermaid".equalsIgnoreCase(format)) {
                String output = renderer.renderMermaid(wsPath, conn);
                System.out.println(output);
            } else {
                String output = renderer.renderAscii(wsPath, conn);
                System.out.println(output);
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Code graph failed: " + e.getMessage());
            return 1;
        }
    }

    private int showCycles(DagRenderer renderer, String wsPath, Connection conn) throws Exception {
        List<DagRenderer.CircularDep> cycleList = renderer.findCycles(wsPath, conn);
        System.out.println();
        if (cycleList.isEmpty()) {
            System.out.println("No circular dependencies detected.");
        } else {
            System.out.println("Circular Dependencies (" + cycleList.size() + " cycle"
                    + (cycleList.size() != 1 ? "s" : "") + ")");
            System.out.println();
            for (DagRenderer.CircularDep c : cycleList) {
                System.out.println("  [!] " + c.packageA() + " \u2194 " + c.packageB());
                System.out.println("      " + c.packageA() + " \u2192 " + c.packageB()
                        + ": " + c.edgesAtoB() + " edges");
                System.out.println("      " + c.packageB() + " \u2192 " + c.packageA()
                        + ": " + c.edgesBtoA() + " edges");
                System.out.println();
            }
        }
        System.out.println();
        return 0;
    }

    private int showHotspots(DagRenderer renderer, String wsPath, Connection conn) throws Exception {
        List<DagRenderer.ModuleProfile> hotspotList = renderer.findHotspots(wsPath, conn);
        System.out.println();
        if (hotspotList.isEmpty()) {
            System.out.println("No hotspots detected (instability > 0.7 AND fan-in > 2).");
        } else {
            System.out.println("Hotspot Packages (" + hotspotList.size() + " found)");
            System.out.println("  Criteria: instability > 0.7 AND fan-in > 2");
            System.out.println();
            for (DagRenderer.ModuleProfile p : hotspotList) {
                System.out.println("  \u26a0 " + p.modulePath());
                System.out.println("    fan-in: " + p.fanIn() + "  fan-out: " + p.fanOut()
                        + "  instability: " + String.format("%.2f", p.instability()));
                System.out.println();
            }
        }
        System.out.println();
        return 0;
    }

    private int showInstability(DagRenderer renderer, String wsPath, Connection conn) throws Exception {
        List<DagRenderer.ModuleProfile> sorted = renderer.sortedByInstability(wsPath, conn);
        System.out.println();
        System.out.println("Packages by Instability (descending)");
        System.out.println();
        for (DagRenderer.ModuleProfile p : sorted) {
            String bar = instabilityBar(p.instability());
            System.out.println(String.format("  %-50s %s %.2f  (fan-in: %d, fan-out: %d)",
                    p.modulePath(), bar, p.instability(), p.fanIn(), p.fanOut()));
        }
        System.out.println();
        return 0;
    }

    private int showCrossFormat(CodeGraphRepository repo, String wsPath, Connection conn) throws Exception {
        List<CodeGraphRepository.CrossFormatLinkRecord> links = repo.getCrossFormatLinks(conn, wsPath);
        System.out.println();
        if (links.isEmpty()) {
            System.out.println("No cross-format links found.");
        } else {
            System.out.println("Cross-Format Links (" + links.size() + " found)");
            System.out.println();
            for (CodeGraphRepository.CrossFormatLinkRecord link : links) {
                System.out.println("  " + link.sourceFile() + " \u2192 " + link.targetFile());
                System.out.println("    type: " + link.linkType() + "  entity: " + link.entityName());
                System.out.println();
            }
        }
        System.out.println();
        return 0;
    }

    private static String instabilityBar(double instability) {
        int filled = (int) Math.round(instability * 10);
        int empty = 10 - filled;
        return "\u2588".repeat(filled) + "\u2591".repeat(empty);
    }

    private int countProfiles(Connection conn, String wsPath) throws Exception {
        String sql = "SELECT COUNT(*) FROM module_profiles WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wsPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: extract
    // -----------------------------------------------------------------------

    /**
     * Extracts code dependency information from source files and persists
     * it to the code knowledge graph tables in SQLite.
     *
     * <p>Supports full extraction (clears and rebuilds), incremental updates
     * (only changed files), dry-run (reports counts without writing), and
     * stats-only mode (shows current graph statistics).
     */
    @Command(name = "extract",
            description = "Extract code dependencies and persist to SQLite",
            mixinStandardHelpOptions = true)
    static class ExtractSub implements Callable<Integer> {

        @ParentCommand
        private CodeGraphCommand parent;

        @Option(names = {"--incremental"},
                description = "Only re-extract changed files (faster for large codebases)",
                defaultValue = "false")
        private boolean incremental;

        @Option(names = {"--stats"},
                description = "Show current graph statistics without extracting",
                defaultValue = "false")
        private boolean statsOnly;

        @Option(names = {"--dry-run"},
                description = "Show what would be extracted without writing to the database",
                defaultValue = "false")
        private boolean dryRun;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                var validation = workspace.validate();
                if (validation.isPresent()) {
                    AnsiOutput.printError(validation.get());
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                Connection conn = db.getConnection();

                if (statsOnly) {
                    return showStats(conn, workspaceRoot);
                }

                if (dryRun) {
                    return showDryRun(workspaceRoot);
                }

                CodeGraphExtractor extractor = new CodeGraphExtractor();

                if (incremental) {
                    return runIncremental(extractor, conn, workspaceRoot);
                } else {
                    return runFull(extractor, conn, workspaceRoot);
                }
            } catch (Exception e) {
                AnsiOutput.printError("Code graph extraction failed: " + e.getMessage());
                return 1;
            }
        }

        private int showStats(Connection conn, Path workspaceRoot) throws Exception {
            CodeGraphRepository repo = new CodeGraphRepository();
            String wsPath = workspaceRoot.toString();

            int deps = repo.countDependencies(conn, wsPath);
            int links = repo.countCrossFormatLinks(conn, wsPath);
            boolean populated = repo.isPopulated(conn, wsPath);

            System.out.println();
            System.out.println("Code Knowledge Graph: " + workspaceRoot.getFileName());
            System.out.println();
            System.out.println("  Status:             " + (populated ? "populated" : "empty"));
            System.out.println("  Dependencies:       " + deps);
            System.out.println("  Cross-format links: " + links);
            System.out.println();

            if (!populated) {
                System.out.println("  Run 'synthesis code-graph extract' to populate the graph.");
                System.out.println();
            }
            return 0;
        }

        private int showDryRun(Path workspaceRoot) throws IOException {
            List<Path> javaFiles = findJavaFiles(workspaceRoot);
            List<Path> sqlFiles = findSqlFiles(workspaceRoot);

            System.out.println();
            System.out.println("Code Graph Extraction (dry-run)");
            System.out.println();
            System.out.println("  Java files:  " + javaFiles.size());
            System.out.println("  SQL files:   " + sqlFiles.size());
            System.out.println("  Total files: " + (javaFiles.size() + sqlFiles.size()));
            System.out.println();
            System.out.println("  No changes made. Remove --dry-run to extract.");
            System.out.println();
            return 0;
        }

        private int runFull(CodeGraphExtractor extractor, Connection conn,
                            Path workspaceRoot) throws Exception {
            System.out.println();
            System.out.println("Extracting code graph (full)...");

            CodeGraphStats stats = extractor.extractAndPersist(workspaceRoot, conn);

            System.out.println();
            System.out.println("  Files processed:    " + stats.filesProcessed());
            System.out.println("  Dependencies found: " + stats.dependenciesFound());
            System.out.println("  Cross-format links: " + stats.crossFormatLinks());
            System.out.println("  Packages found:     " + stats.packagesFound());
            System.out.println("  External deps:      " + stats.externalDeps());
            System.out.println("  Elapsed:            " + stats.elapsedMs() + " ms");

            // Auto-compute module profiles after successful extraction
            ModuleProfileComputer profileComputer = new ModuleProfileComputer(new CodeGraphRepository());
            int profileCount = profileComputer.computeAndPersist(workspaceRoot.toString(), conn);
            System.out.println("  Module profiles:    " + profileCount);

            System.out.println();
            return 0;
        }

        private int runIncremental(CodeGraphExtractor extractor, Connection conn,
                                   Path workspaceRoot) throws Exception {
            // For incremental, find all Java files as the "changed" set
            List<Path> javaFiles = findJavaFiles(workspaceRoot);
            Set<Path> changed = new HashSet<>(javaFiles);

            System.out.println();
            System.out.println("Extracting code graph (incremental, " + changed.size() + " files)...");

            CodeGraphStats stats = extractor.incrementalUpdate(workspaceRoot, conn, changed);

            System.out.println();
            System.out.println("  Files processed:    " + stats.filesProcessed());
            System.out.println("  Dependencies found: " + stats.dependenciesFound());
            System.out.println("  Packages found:     " + stats.packagesFound());
            System.out.println("  External deps:      " + stats.externalDeps());
            System.out.println("  Elapsed:            " + stats.elapsedMs() + " ms");

            // Auto-compute module profiles after successful extraction
            ModuleProfileComputer profileComputer = new ModuleProfileComputer(new CodeGraphRepository());
            int profileCount = profileComputer.computeAndPersist(workspaceRoot.toString(), conn);
            System.out.println("  Module profiles:    " + profileCount);

            System.out.println();
            return 0;
        }

        private List<Path> findJavaFiles(Path root) throws IOException {
            try (Stream<Path> walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("/."))
                        .toList();
            }
        }

        private List<Path> findSqlFiles(Path root) throws IOException {
            try (Stream<Path> walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".sql"))
                        .filter(p -> !p.toString().contains("/."))
                        .toList();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: describe
    // -----------------------------------------------------------------------

    /**
     * Shows module profiles: per-package summaries including fan-in, fan-out,
     * instability, inferred purpose, and file count.
     *
     * <p>Use {@code --refresh} to re-extract and recompute profiles before display.
     *
     * @since v1.12.2 (CKG-2.03)
     */
    @Command(name = "describe",
            description = "Show module profiles (packages, fan-in/out, instability)",
            mixinStandardHelpOptions = true)
    static class DescribeSub implements Callable<Integer> {

        @ParentCommand
        private CodeGraphCommand parent;

        @Option(names = {"--module"},
                description = "Filter by module name substring")
        private String moduleFilter;

        @Option(names = {"--instability"},
                description = "Sort by instability (descending)",
                defaultValue = "false")
        private boolean sortByInstability;

        @Option(names = {"--format"},
                description = "Output format: text or json (default: text)",
                defaultValue = "text")
        private String format;

        @Option(names = {"--refresh"},
                description = "Re-extract dependencies and recompute profiles before display",
                defaultValue = "false")
        private boolean refresh;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                var validation = workspace.validate();
                if (validation.isPresent()) {
                    AnsiOutput.printError(validation.get());
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                Connection conn = db.getConnection();
                String wsPath = workspaceRoot.toString();

                if (refresh) {
                    CodeGraphExtractor extractor = new CodeGraphExtractor();
                    extractor.extractAndPersist(workspaceRoot, conn);
                    ModuleProfileComputer computer = new ModuleProfileComputer(new CodeGraphRepository());
                    computer.computeAndPersist(wsPath, conn);
                }

                List<ModuleProfile> profiles = loadProfiles(conn, wsPath);

                if (profiles.isEmpty()) {
                    // Check if code_dependencies has data -- if so, auto-compute profiles
                    CodeGraphRepository autoRepo = new CodeGraphRepository();
                    if (autoRepo.isPopulated(conn, wsPath)) {
                        System.out.println("  (profiles auto-computed from existing dependency graph)");
                        ModuleProfileComputer autoComputer = new ModuleProfileComputer(autoRepo);
                        autoComputer.computeAndPersist(wsPath, conn);
                        profiles = loadProfiles(conn, wsPath);
                    }
                }

                if (profiles.isEmpty()) {
                    System.out.println();
                    System.out.println("No module profiles found. Run: synthesis code-graph extract");
                    System.out.println();
                    return 0;
                }

                // Apply module filter
                if (moduleFilter != null && !moduleFilter.isBlank()) {
                    String filter = moduleFilter.toLowerCase(Locale.ROOT);
                    profiles = profiles.stream()
                            .filter(p -> p.modulePath.toLowerCase(Locale.ROOT).contains(filter)
                                    || p.packageName.toLowerCase(Locale.ROOT).contains(filter))
                            .toList();
                }

                // Sort
                if (sortByInstability) {
                    profiles = new ArrayList<>(profiles);
                    profiles.sort(Comparator.comparingDouble((ModuleProfile p) -> p.instability).reversed());
                }

                if ("json".equalsIgnoreCase(format)) {
                    printJson(profiles);
                } else {
                    printText(profiles);
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Code graph describe failed: " + e.getMessage());
                return 1;
            }
        }

        private void printText(List<ModuleProfile> profiles) {
            System.out.println();
            System.out.println("Module Profiles (" + profiles.size() + " packages)");
            System.out.println();

            for (ModuleProfile p : profiles) {
                System.out.println("  " + p.modulePath);
                System.out.println("    Purpose:     " + p.purpose);
                System.out.print("    Fan-in:      " + p.fanIn
                        + "   Fan-out: " + p.fanOut
                        + "   Instability: " + String.format("%.2f", p.instability));

                // Add contextual annotation
                if (p.instability > 0.9 && isCli(p.packageName)) {
                    System.out.println(" (expected for CLI)");
                } else if (p.instability < 0.2) {
                    System.out.println(" \u2713");
                } else {
                    System.out.println();
                }

                System.out.println("    Files:       " + p.totalFiles);
                System.out.println("    Confidence:  " + String.format("%.2f", p.confidence));
                System.out.println();
            }
        }

        private void printJson(List<ModuleProfile> profiles) {
            System.out.println("[");
            for (int i = 0; i < profiles.size(); i++) {
                ModuleProfile p = profiles.get(i);
                System.out.println("  {");
                System.out.println("    \"modulePath\": \"" + escape(p.modulePath) + "\",");
                System.out.println("    \"packageName\": \"" + escape(p.packageName) + "\",");
                System.out.println("    \"purpose\": \"" + escape(p.purpose) + "\",");
                System.out.println("    \"fanIn\": " + p.fanIn + ",");
                System.out.println("    \"fanOut\": " + p.fanOut + ",");
                System.out.println("    \"instability\": " + String.format("%.2f", p.instability) + ",");
                System.out.println("    \"totalFiles\": " + p.totalFiles + ",");
                System.out.println("    \"confidence\": " + String.format("%.2f", p.confidence));
                System.out.println("  }" + (i < profiles.size() - 1 ? "," : ""));
            }
            System.out.println("]");
        }

        private List<ModuleProfile> loadProfiles(Connection conn, String wsPath) throws Exception {
            List<ModuleProfile> profiles = new ArrayList<>();
            String sql = """
                SELECT module_path, package_name, inferred_purpose,
                       fan_in, fan_out, instability, total_files, confidence
                FROM module_profiles
                WHERE workspace_path = ?
                ORDER BY package_name
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, wsPath);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        profiles.add(new ModuleProfile(
                                rs.getString("module_path"),
                                rs.getString("package_name"),
                                rs.getString("inferred_purpose"),
                                rs.getInt("fan_in"),
                                rs.getInt("fan_out"),
                                rs.getDouble("instability"),
                                rs.getInt("total_files"),
                                rs.getDouble("confidence")
                        ));
                    }
                }
            }
            return profiles;
        }

        private static boolean isCli(String packageName) {
            return packageName != null
                    && (packageName.endsWith(".cli") || packageName.endsWith(".command"));
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: health
    // -----------------------------------------------------------------------

    /**
     * Detects and displays code health signals from the code knowledge graph.
     *
     * <p>Signals range from circular dependencies (HIGH) to documentation gaps (LOW).
     * Use {@code --errors-only} to show only HIGH-severity signals.
     *
     * @since v1.12.2 (CKG-2.03)
     */
    @Command(name = "health",
            description = "Detect code health signals (circular deps, hotspots, etc.)",
            mixinStandardHelpOptions = true)
    static class HealthSub implements Callable<Integer> {

        @ParentCommand
        private CodeGraphCommand parent;

        @Option(names = {"--errors-only"},
                description = "Show only HIGH severity signals",
                defaultValue = "false")
        private boolean errorsOnly;

        @Option(names = {"--format"},
                description = "Output format: text or json (default: text)",
                defaultValue = "text")
        private String format;

        @Option(names = {"--refresh"},
                description = "Re-extract dependencies and recompute profiles before analysis",
                defaultValue = "false")
        private boolean refresh;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                var validation = workspace.validate();
                if (validation.isPresent()) {
                    AnsiOutput.printError(validation.get());
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                Connection conn = db.getConnection();
                String wsPath = workspaceRoot.toString();

                if (refresh) {
                    CodeGraphExtractor extractor = new CodeGraphExtractor();
                    extractor.extractAndPersist(workspaceRoot, conn);
                    ModuleProfileComputer computer = new ModuleProfileComputer(new CodeGraphRepository());
                    computer.computeAndPersist(wsPath, conn);
                }

                // Check if profiles exist
                int profileCount = countProfiles(conn, wsPath);
                if (profileCount == 0) {
                    System.out.println();
                    System.out.println("No module profiles found. Run: synthesis code-graph extract && synthesis code-graph health --refresh");
                    System.out.println();
                    return 0;
                }

                CodeHealthAnalyzer analyzer = new CodeHealthAnalyzer();
                List<CodeHealthSignal> signals = analyzer.analyze(wsPath, conn);

                if (errorsOnly) {
                    signals = signals.stream()
                            .filter(s -> "HIGH".equals(s.severity()))
                            .toList();
                }

                if ("json".equalsIgnoreCase(format)) {
                    printJson(signals);
                } else {
                    printText(signals);
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Code graph health analysis failed: " + e.getMessage());
                return 1;
            }
        }

        private void printText(List<CodeHealthSignal> signals) {
            System.out.println();
            if (signals.isEmpty()) {
                System.out.println("Code Health: No issues detected");
                System.out.println();
                return;
            }

            System.out.println("Code Health Signals (" + signals.size() + " issues)");
            System.out.println();

            for (CodeHealthSignal s : signals) {
                System.out.println("  [" + s.severity() + "] " + s.signalId()
                        + " -- " + s.modulePath());
                System.out.println("    " + s.description());
                System.out.println("    Suggestion: " + s.suggestion());
                System.out.println();
            }
        }

        private void printJson(List<CodeHealthSignal> signals) {
            System.out.println("[");
            for (int i = 0; i < signals.size(); i++) {
                CodeHealthSignal s = signals.get(i);
                System.out.println("  {");
                System.out.println("    \"signalId\": \"" + s.signalId() + "\",");
                System.out.println("    \"severity\": \"" + s.severity() + "\",");
                System.out.println("    \"modulePath\": \"" + escape(s.modulePath()) + "\",");
                System.out.println("    \"description\": \"" + escape(s.description()) + "\",");
                System.out.println("    \"suggestion\": \"" + escape(s.suggestion()) + "\"");
                System.out.println("  }" + (i < signals.size() - 1 ? "," : ""));
            }
            System.out.println("]");
        }

        private int countProfiles(Connection conn, String wsPath) throws Exception {
            String sql = "SELECT COUNT(*) FROM module_profiles WHERE workspace_path = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, wsPath);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: gaps
    // -----------------------------------------------------------------------

    /**
     * Shows quality gaps detected in the code knowledge graph: missing tests,
     * interfaces, documentation, etc. Optionally shows completeness scores.
     *
     * <p>Use {@code --refresh} to re-detect gaps before display.
     * Use {@code --score} to show completeness scores per module.
     *
     * @since v1.12.2 (CKG-3.03)
     */
    @Command(name = "gaps",
            description = "Show quality gaps and completeness scores per module",
            mixinStandardHelpOptions = true)
    static class GapsSub implements Callable<Integer> {

        @ParentCommand
        private CodeGraphCommand parent;

        @Option(names = {"--type"},
                description = "Filter by gap type (e.g., MISSING_TESTS)")
        private String typeFilter;

        @Option(names = {"--severity"},
                description = "Filter by severity (HIGH, MEDIUM, LOW)")
        private String severityFilter;

        @Option(names = {"--module"},
                description = "Filter by module name substring")
        private String moduleFilter;

        @Option(names = {"--format"},
                description = "Output format: text or json (default: text)",
                defaultValue = "text")
        private String format;

        @Option(names = {"--refresh"},
                description = "Re-detect gaps before display",
                defaultValue = "false")
        private boolean refresh;

        @Option(names = {"--score"},
                description = "Show completeness score per module",
                defaultValue = "false")
        private boolean showScore;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                var validation = workspace.validate();
                if (validation.isPresent()) {
                    AnsiOutput.printError(validation.get());
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                Connection conn = db.getConnection();
                String wsPath = workspaceRoot.toString();

                CodeGraphRepository repo = new CodeGraphRepository();

                if (refresh) {
                    // Re-extract, recompute profiles, then detect gaps
                    CodeGraphExtractor extractor = new CodeGraphExtractor();
                    extractor.extractAndPersist(workspaceRoot, conn);
                    ModuleProfileComputer computer = new ModuleProfileComputer(repo);
                    computer.computeAndPersist(wsPath, conn);

                    QualityGapDetector detector = new QualityGapDetector(repo);
                    detector.detectAndPersist(wsPath, workspaceRoot, conn);

                    // Compute completeness scores
                    List<QualityGap> allGaps = repo.getQualityGaps(conn, wsPath);
                    Map<String, List<QualityGap>> gapsByModule = groupByModule(allGaps);
                    CompletenessScorer scorer = new CompletenessScorer();
                    scorer.computeAndPersistAll(wsPath, conn, gapsByModule);
                }

                // Load gaps with optional filters
                List<QualityGap> gaps;
                if (typeFilter != null && !typeFilter.isBlank()) {
                    gaps = repo.getQualityGapsByType(conn, wsPath, typeFilter.toUpperCase(Locale.ROOT));
                } else if (severityFilter != null && !severityFilter.isBlank()) {
                    gaps = repo.getQualityGapsBySeverity(conn, wsPath, severityFilter.toUpperCase(Locale.ROOT));
                } else {
                    gaps = repo.getQualityGaps(conn, wsPath);
                }

                // Apply module filter
                if (moduleFilter != null && !moduleFilter.isBlank()) {
                    String filter = moduleFilter.toLowerCase(Locale.ROOT);
                    gaps = gaps.stream()
                            .filter(g -> g.modulePath().toLowerCase(Locale.ROOT).contains(filter))
                            .toList();
                }

                if ("json".equalsIgnoreCase(format)) {
                    printGapsJson(gaps, showScore);
                } else {
                    printGapsText(gaps, showScore);
                }

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Code graph gaps analysis failed: " + e.getMessage());
                return 1;
            }
        }

        private void printGapsText(List<QualityGap> gaps, boolean showScore) {
            System.out.println();
            if (gaps.isEmpty()) {
                System.out.println("No quality gaps detected. All modules look healthy.");
                System.out.println();
                return;
            }

            // Group by module
            Map<String, List<QualityGap>> byModule = groupByModule(gaps);

            int totalGaps = gaps.size();
            int totalModules = byModule.size();
            System.out.println("Quality Gaps (" + totalGaps + " gap"
                    + (totalGaps != 1 ? "s" : "") + " across " + totalModules + " module"
                    + (totalModules != 1 ? "s" : "") + ")");
            System.out.println();

            CompletenessScorer scorer = showScore ? new CompletenessScorer() : null;

            for (Map.Entry<String, List<QualityGap>> entry : byModule.entrySet()) {
                String modulePath = entry.getKey();
                List<QualityGap> moduleGaps = entry.getValue();

                if (showScore && scorer != null) {
                    double score = scorer.score(moduleGaps);
                    System.out.println("  " + modulePath + "  [score: " + String.format("%.2f", score) + "]");
                } else {
                    System.out.println("  " + modulePath);
                }

                for (QualityGap gap : moduleGaps) {
                    System.out.println("    [" + gap.severity() + "] " + gap.gapType());
                    System.out.println("      " + gap.description());
                    if (gap.suggestion() != null && !gap.suggestion().isBlank()) {
                        System.out.println("      -> " + gap.suggestion());
                    }
                }
                System.out.println();
            }
        }

        private void printGapsJson(List<QualityGap> gaps, boolean showScore) {
            CompletenessScorer scorer = showScore ? new CompletenessScorer() : null;
            Map<String, List<QualityGap>> byModule = groupByModule(gaps);

            System.out.println("[");
            int idx = 0;
            for (QualityGap gap : gaps) {
                System.out.println("  {");
                System.out.println("    \"modulePath\": \"" + escape(gap.modulePath()) + "\",");
                System.out.println("    \"gapType\": \"" + escape(gap.gapType()) + "\",");
                System.out.println("    \"severity\": \"" + gap.severity() + "\",");
                System.out.println("    \"description\": \"" + escape(gap.description()) + "\",");
                if (gap.filePath() != null) {
                    System.out.println("    \"filePath\": \"" + escape(gap.filePath()) + "\",");
                }
                System.out.println("    \"suggestion\": \"" + escape(gap.suggestion()) + "\"");
                System.out.println("  }" + (idx < gaps.size() - 1 ? "," : ""));
                idx++;
            }
            System.out.println("]");
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Groups quality gaps by module path using a {@link LinkedHashMap} to preserve
     * insertion order (which is already sorted by severity).
     */
    static Map<String, List<QualityGap>> groupByModule(List<QualityGap> gaps) {
        Map<String, List<QualityGap>> byModule = new LinkedHashMap<>();
        for (QualityGap gap : gaps) {
            byModule.computeIfAbsent(gap.modulePath(), k -> new ArrayList<>()).add(gap);
        }
        return byModule;
    }

    // -----------------------------------------------------------------------
    // Shared inner record for describe output
    // -----------------------------------------------------------------------

    /**
     * Internal record for module profile display data.
     */
    record ModuleProfile(
            String modulePath,
            String packageName,
            String purpose,
            int fanIn,
            int fanOut,
            double instability,
            int totalFiles,
            double confidence
    ) {}
}
