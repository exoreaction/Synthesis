package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.RelationService;
import io.exoreaction.synthesis.graph.RelationService.RelationshipMap;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Transitive change impact analysis command.
 *
 * <p>Walks the dependency graph transitively (BFS up the caller graph) and
 * returns the full "blast radius" -- the set of files that would be affected
 * if the given file changes.
 */
@Command(
        name = "impact",
        description = "Transitive change impact analysis -- show full blast radius for a file",
        mixinStandardHelpOptions = true
)
public class ImpactCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(index = "0", description = "Target file or class name to analyze")
    private String targetFile;

    @Option(names = {"--depth"}, description = "Max transitive depth (default: 3)", defaultValue = "3")
    private int depth;

    @Option(names = {"--format"}, description = "Output format: text, json (default: text)", defaultValue = "text")
    private String format;

    @Option(names = {"--risk"}, description = "Show risk assessment", defaultValue = "false")
    private boolean showRisk;

    @Option(names = {"--refresh"}, description = "Force re-extraction from source files (ignore persisted graph)", defaultValue = "false")
    private boolean refresh;

    @Option(names = {"--no-cochange"}, description = "Skip co-change coupling section", defaultValue = "false")
    private boolean noCochange;

    private final RelationService relationService = new RelationService();

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

            List<SearchResult> allFiles;
            SearchResult target;

            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                // #431: the argument is a path/filename, not Lucene query syntax --
                // unescaped slashes are parsed as regex delimiters and corrupt the query
                List<SearchResult> targetResults = index.searchLiteral(targetFile, 1000);
                target = relationService.findBestMatch(targetResults, targetFile);
                if (target == null) {
                    AnsiOutput.printError("File not found: " + targetFile);
                    AnsiOutput.printInfo("Try 'synthesis search " + targetFile + "' to find it.");
                    return 1;
                }
                allFiles = index.listAll(null, 10000);
            }

            Map<String, Integer> impactSet;

            // Try persisted graph first (BFS on SQLite -- instant for large codebases)
            if (!refresh && tryPersistedGraph(workspaceRoot)) {
                impactSet = computeImpactFromGraph(target, workspaceRoot);
            } else {
                // Fall back to live file-reading approach
                impactSet = computeImpact(target, allFiles, workspaceRoot);
            }

            boolean cliReachable = impactSet.keySet().stream().anyMatch(p -> p.contains("/cli/"));
            boolean mcpReachable = impactSet.keySet().stream().anyMatch(p -> p.contains("/mcp/"));
            boolean lspReachable = impactSet.keySet().stream().anyMatch(p -> p.contains("/lsp/"));

            if ("json".equals(format)) {
                printJson(target, impactSet, cliReachable, mcpReachable, lspReachable);
            } else {
                printText(target, impactSet, cliReachable, mcpReachable, lspReachable);
                if (!noCochange) {
                    printCoChange(workspaceRoot.toString(), target.relativePath());
                }
            }
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Impact analysis failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Checks if the persisted code knowledge graph is populated for this workspace.
     */
    private boolean tryPersistedGraph(Path workspaceRoot) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            CodeGraphRepository repo = new CodeGraphRepository();
            return repo.isPopulated(conn, workspaceRoot.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * BFS traversal on the persisted code_dependencies table.
     * Much faster than reading file contents -- queries SQLite indexes.
     */
    Map<String, Integer> computeImpactFromGraph(SearchResult target,
                                                         Path workspaceRoot) throws Exception {
        SynthesisDatabase db = SynthesisDatabase.getDefault();
        Connection conn = db.getConnection();
        CodeGraphRepository repo = new CodeGraphRepository();
        String wsPath = workspaceRoot.toString();

        Map<String, Integer> impactSet = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(target.relativePath());
        impactSet.put(target.relativePath(), 0);

        for (int d = 1; d <= depth && !queue.isEmpty(); d++) {
            List<String> currentLevel = new ArrayList<>(queue);
            queue.clear();

            for (String currentPath : currentLevel) {
                // Find all files that import/reference this file
                List<CodeDependency> incoming = repo.getIncomingForFile(conn, wsPath, currentPath);
                for (CodeDependency dep : incoming) {
                    if (!impactSet.containsKey(dep.sourceFile())) {
                        impactSet.put(dep.sourceFile(), d);
                        queue.add(dep.sourceFile());
                    }
                }
            }
        }

        impactSet.remove(target.relativePath());
        return impactSet;
    }

    /**
     * BFS traversal up the caller graph from the target file.
     *
     * @param target        the file to analyze
     * @param allFiles      all indexed files (used for incoming-ref analysis)
     * @param workspaceRoot workspace root for file content reading
     * @return map of impacted file relative paths to the depth at which they were found
     */
    Map<String, Integer> computeImpact(SearchResult target, List<SearchResult> allFiles,
                                               Path workspaceRoot) {
        Map<String, Integer> impactSet = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(target.relativePath());
        impactSet.put(target.relativePath(), 0);

        for (int d = 1; d <= depth && !queue.isEmpty(); d++) {
            List<String> currentLevel = new ArrayList<>(queue);
            queue.clear();

            for (String currentPath : currentLevel) {
                SearchResult current = findByPath(allFiles, currentPath);
                if (current == null) continue;

                RelationshipMap map = new RelationshipMap(current.relativePath());
                relationService.analyzeIncomingRefs(current, allFiles, workspaceRoot, map);

                for (String incomingPath : map.incoming().keySet()) {
                    if (!impactSet.containsKey(incomingPath)) {
                        impactSet.put(incomingPath, d);
                        queue.add(incomingPath);
                    }
                }
            }
        }

        impactSet.remove(target.relativePath());
        return impactSet;
    }

    private SearchResult findByPath(List<SearchResult> all, String path) {
        return all.stream()
                .filter(r -> r.relativePath().equals(path))
                .findFirst()
                .orElse(null);
    }

    private void printText(SearchResult target, Map<String, Integer> impact,
                           boolean cli, boolean mcp, boolean lsp) {
        System.out.println();
        System.out.println("Change impact: " + target.relativePath());
        System.out.println();

        if (impact.isEmpty()) {
            System.out.println("  No callers found -- this file is not referenced by any indexed file.");
            System.out.println();
            return;
        }

        Map<Integer, List<String>> byDepth = new TreeMap<>();
        for (Map.Entry<String, Integer> e : impact.entrySet()) {
            byDepth.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        for (Map.Entry<Integer, List<String>> e : byDepth.entrySet()) {
            String label = e.getKey() == 1 ? "Direct callers" : "Transitive impact (depth " + e.getKey() + ")";
            System.out.println("  " + label + " (" + e.getValue().size() + " file" + (e.getValue().size() == 1 ? "" : "s") + "):");
            e.getValue().stream().sorted().forEach(p -> System.out.println("    - " + p));
            System.out.println();
        }

        System.out.println("  Total impact: " + impact.size() + " file(s) across " + depth + " level(s)");

        if (showRisk || cli || mcp || lsp) {
            System.out.println();
            String riskLevel = (impact.size() > 20 || cli) ? "HIGH" : (impact.size() > 5) ? "MEDIUM" : "LOW";
            System.out.println("  Risk: " + riskLevel);
            if (cli) System.out.println("  WARNING: CLI entry-point reachable (full application affected)");
            if (mcp) System.out.println("  WARNING: MCP layer reachable");
            if (lsp) System.out.println("  WARNING: LSP layer reachable");
        }
        System.out.println();
    }

    private void printCoChange(String workspacePath, String targetRelPath) {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();

            String sql = """
                    SELECT file_b, coupling_score, cochange_count, last_cochange_at
                    FROM git_cochange
                    WHERE workspace_path = ? AND file_a = ?
                    ORDER BY coupling_score DESC
                    LIMIT 10
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setString(2, targetRelPath);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> rows = new ArrayList<>();
                    while (rs.next()) {
                        long lastAt = rs.getLong("last_cochange_at");
                        long daysSince = ChronoUnit.DAYS.between(
                                Instant.ofEpochSecond(lastAt), Instant.now());
                        rows.add(String.format("    %-60s  score=%.2f  co-changed %dx  last %dd ago",
                                rs.getString("file_b"),
                                rs.getDouble("coupling_score"),
                                rs.getInt("cochange_count"),
                                daysSince));
                    }
                    if (!rows.isEmpty()) {
                        System.out.println("  Co-change partners (files that move together):");
                        rows.forEach(System.out::println);
                        System.out.println("  Run 'synthesis hotspots --refresh' to update co-change data.");
                        System.out.println();
                    }
                }
            }
        } catch (Exception e) {
            // Co-change data is optional -- silently skip if unavailable
        }
    }

    private void printJson(SearchResult target, Map<String, Integer> impact,
                           boolean cli, boolean mcp, boolean lsp) {
        System.out.println("{");
        System.out.println("  \"target\": \"" + target.relativePath() + "\",");
        System.out.println("  \"totalImpact\": " + impact.size() + ",");
        System.out.println("  \"entryPointsReachable\": {");
        System.out.println("    \"cli\": " + cli + ",");
        System.out.println("    \"mcp\": " + mcp + ",");
        System.out.println("    \"lsp\": " + lsp);
        System.out.println("  },");
        System.out.println("  \"files\": [");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(impact.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            String comma = i < entries.size() - 1 ? "," : "";
            System.out.println("    {\"path\": \"" + entries.get(i).getKey()
                    + "\", \"depth\": " + entries.get(i).getValue() + "}" + comma);
        }
        System.out.println("  ]");
        System.out.println("}");
    }
}
