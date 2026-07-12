package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.*;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.RelationService.RelationshipMap;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Relationship mapping command. Thin CLI wrapper delegating to
 * {@link RelationService} in the {@code graph} package.
 */
@Command(name = "relate", description = "Show file relationships, imports, and dependencies", mixinStandardHelpOptions = true)
public class RelateCommand implements Callable<Integer> {
    @ParentCommand private SynthesisApp parent;
    @Parameters(index = "0", description = "File name or path to analyze relationships for") private String targetFile;
    @Option(names = {"--mermaid"}, description = "Output as Mermaid diagram", defaultValue = "false") private boolean mermaid;
    @Option(names = {"--depth"}, description = "How many levels of relationships to follow (default: 1)", defaultValue = "1") private int depth;
    @Option(names = {"-v", "--verbose"}, description = "Show detailed reference information", defaultValue = "false") private boolean verbose;
    @Option(names = {"--tests"}, description = "Show test classes that cover this file", defaultValue = "false") private boolean showTests;
    @Option(names = {"--refresh"}, description = "Force re-extraction from source files (ignore persisted graph)", defaultValue = "false") private boolean refresh;
    @Option(names = {"--format"}, description = "Output format: text, json (default: text)", defaultValue = "text") private String format;

    private final RelationService relationService = new RelationService();
    private final CrossFormatLinker crossFormatLinker = new CrossFormatLinker();

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) { AnsiOutput.printError(validation.get()); return 1; }

            List<SearchResult> targetResults;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                // #431: the argument is a path/filename, not Lucene query syntax --
                // unescaped slashes are parsed as regex delimiters and corrupt the query
                targetResults = index.searchLiteral(targetFile, 10);
            }
            SearchResult target = relationService.findBestMatch(targetResults, targetFile);
            if (target == null) { AnsiOutput.printError("File not found in index: " + targetFile); AnsiOutput.printInfo("Try 'synthesis search " + targetFile + "' to find it."); return 1; }

            RelationshipMap relationshipMap;

            // Try persisted graph first (unless --refresh forces re-extraction)
            if (!refresh && !mermaid && tryPersistedGraph(workspaceRoot, target)) {
                relationshipMap = buildFromPersistedGraph(workspaceRoot, target);
            } else {
                // Fall back to live extraction
                relationshipMap = buildFromLiveExtraction(target, workspaceRoot, workspace);
            }

            if ("json".equals(format)) {
                printJson(relationshipMap, target);
            } else if (mermaid) {
                System.out.println(relationService.generateMermaid(relationshipMap));
            } else {
                printRelationships(relationshipMap, target);
                printKnowledgeEnrichment(target.relativePath(), workspace);
            }

            if (crossFormatLinker.isSqlFile(target) || crossFormatLinker.isYamlFile(target)) {
                List<SearchResult> allFiles;
                try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) { allFiles = index.listAll(null, 5000); }
                printCrossFormatLinks(target, allFiles, workspaceRoot);
            }
            if (showTests) {
                TestCoverageAnalyzer tca = new TestCoverageAnalyzer();
                List<SearchResult> af;
                try (var si = SearchIndex.openReadOnly(workspace.getIndexPath())) { af = si.listAll(null, 5000); }
                printTestCoverage(tca.findTests(target, af, workspaceRoot));
            }
            return 0;
        } catch (Exception e) { AnsiOutput.printError("Relate failed: " + e.getMessage()); return 1; }
    }

    /**
     * Checks if the persisted code knowledge graph is populated for this workspace.
     */
    private boolean tryPersistedGraph(Path workspaceRoot, SearchResult target) {
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
     * Builds a RelationshipMap from the persisted code_dependencies table.
     * Much faster than live extraction -- queries SQLite instead of reading file contents.
     */
    private RelationshipMap buildFromPersistedGraph(Path workspaceRoot, SearchResult target) throws Exception {
        SynthesisDatabase db = SynthesisDatabase.getDefault();
        Connection conn = db.getConnection();
        CodeGraphRepository repo = new CodeGraphRepository();
        String wsPath = workspaceRoot.toString();
        String relPath = target.relativePath();

        RelationshipMap map = new RelationshipMap(relPath);

        // Outgoing: dependencies FROM this file
        List<CodeDependency> outgoing = repo.getDependenciesFrom(conn, wsPath, relPath);
        for (CodeDependency dep : outgoing) {
            String targetRef = dep.targetFile() != null ? dep.targetFile()
                    : dep.targetClass() + " (" + dep.dependencyType() + ", external)";
            map.addOutgoing(targetRef, dep.dependencyType());
        }

        // Incoming: files that depend ON this file
        List<CodeDependency> incoming = repo.getIncomingForFile(conn, wsPath, relPath);
        for (CodeDependency dep : incoming) {
            map.addIncoming(dep.sourceFile(), dep.dependencyType());
        }

        return map;
    }

    /**
     * Original live extraction path -- reads file contents to discover relationships.
     */
    private RelationshipMap buildFromLiveExtraction(SearchResult target, Path workspaceRoot,
                                                     WorkspaceManager workspace) throws Exception {
        RelationshipMap relationshipMap = new RelationshipMap(target.relativePath());
        List<SearchResult> allFiles;
        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) { allFiles = index.listAll(null, 5000); }
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);

        relationService.analyzeOutgoingRefs(target, workspaceRoot, relationshipMap, fileNameIndex);
        relationService.analyzeIncomingRefs(target, allFiles, workspaceRoot, relationshipMap);

        if (depth > 1) {
            Set<String> visited = new HashSet<>();
            visited.add(target.relativePath());
            deepenRelationships(relationshipMap, allFiles, workspaceRoot, fileNameIndex, visited, depth - 1);
        }

        return relationshipMap;
    }

    private void deepenRelationships(RelationshipMap map, List<SearchResult> allFiles, Path workspaceRoot, Map<String, List<String>> fileNameIndex, Set<String> visited, int remainingDepth) {
        if (remainingDepth <= 0) return;
        Set<String> toVisit = new HashSet<>();
        toVisit.addAll(map.outgoing().keySet());
        toVisit.addAll(map.incoming().keySet());
        toVisit.removeAll(visited);
        for (String relPath : toVisit) {
            visited.add(relPath);
            SearchResult file = allFiles.stream().filter(f -> f.relativePath().equals(relPath)).findFirst().orElse(null);
            if (file != null) relationService.analyzeIncomingRefs(file, allFiles, workspaceRoot, map);
        }
    }

    private void printJson(RelationshipMap map, SearchResult target) {
        System.out.println("{");
        System.out.println("  \"target\": \"" + map.targetFile() + "\",");
        System.out.println("  \"outgoing\": [");
        List<Map.Entry<String, String>> outList = new ArrayList<>(map.outgoing().entrySet());
        for (int i = 0; i < outList.size(); i++) {
            String comma = i < outList.size() - 1 ? "," : "";
            System.out.println("    {\"file\": \"" + outList.get(i).getKey()
                    + "\", \"type\": \"" + outList.get(i).getValue() + "\"}" + comma);
        }
        System.out.println("  ],");
        System.out.println("  \"incoming\": [");
        List<Map.Entry<String, String>> inList = new ArrayList<>(map.incoming().entrySet());
        for (int i = 0; i < inList.size(); i++) {
            String comma = i < inList.size() - 1 ? "," : "";
            System.out.println("    {\"file\": \"" + inList.get(i).getKey()
                    + "\", \"type\": \"" + inList.get(i).getValue() + "\"}" + comma);
        }
        System.out.println("  ],");
        System.out.println("  \"totalConnections\": " + (map.outgoing().size() + map.incoming().size()));
        System.out.println("}");
    }

    private void printRelationships(RelationshipMap map, SearchResult target) {
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Relationships for: " + target.relativePath()));
        if (target.language() != null) {
            System.out.println("  " + AnsiOutput.dim("Language: " + target.language() + " | Type: " + target.fileType() + " | Size: " + FileUtils.formatSize(target.sizeBytes())));
        }
        System.out.println();
        if (!map.outgoing().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold(AnsiOutput.cyan("Imports/References (outgoing):")) + " " + map.outgoing().size() + " files");
            for (var entry : map.outgoing().entrySet()) System.out.println("    " + AnsiOutput.green("->") + " " + entry.getKey() + AnsiOutput.dim(" (" + entry.getValue() + ")"));
            System.out.println();
        } else { System.out.println("  " + AnsiOutput.dim("No outgoing references found.")); System.out.println(); }
        if (!map.incoming().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold(AnsiOutput.magenta("Referenced by (incoming):")) + " " + map.incoming().size() + " files");
            for (var entry : map.incoming().entrySet()) System.out.println("    " + AnsiOutput.yellow("<-") + " " + entry.getKey() + AnsiOutput.dim(" (" + entry.getValue() + ")"));
            System.out.println();
        } else { System.out.println("  " + AnsiOutput.dim("No incoming references found.")); System.out.println(); }
        int total = map.outgoing().size() + map.incoming().size();
        if (total == 0) System.out.println("  " + AnsiOutput.yellow("This file appears to be orphaned (no references found)."));
        else System.out.println("  " + AnsiOutput.bold("Total connections: " + total));
        System.out.println();
    }

    private void printKnowledgeEnrichment(String sourcePath, io.exoreaction.synthesis.core.WorkspaceManager workspace) {
        try {
            io.exoreaction.synthesis.db.SynthesisDatabase db =
                io.exoreaction.synthesis.db.SynthesisDatabase.getDefault();
            KnowledgeEnricher enricher = new KnowledgeEnricher();
            KnowledgeEnricher.EnrichmentResult result =
                enricher.enrichForSource(sourcePath, db.getConnection());

            System.out.println("  " + AnsiOutput.bold(AnsiOutput.cyan("Documentation:")));
            System.out.println();
            if (result.hasGap()) {
                System.out.println("  " + AnsiOutput.yellow("No skill/doc coverage found.") +
                    " Consider creating a skill for this file.");
            } else {
                System.out.println("  Overall confidence: " +
                    AnsiOutput.bold(confidenceColor(result.overallConfidence())));
                System.out.println();
                System.out.println(enricher.formatForCli(result));
            }
            System.out.println();
        } catch (Exception e) {
            // Knowledge enrichment is best-effort; never fail relate
        }
    }

    private String confidenceColor(String confidence) {
        return switch (confidence) {
            case "HIGH"   -> AnsiOutput.green("HIGH");
            case "MEDIUM" -> AnsiOutput.yellow("MEDIUM");
            case "LOW"    -> AnsiOutput.yellow("LOW");
            default       -> AnsiOutput.red(confidence);
        };
    }

    private void printCrossFormatLinks(SearchResult target, List<SearchResult> allFiles, Path workspaceRoot) {
        try {
            List<CrossFormatLinker.CrossFormatLink> links;
            String sectionTitle;
            if (crossFormatLinker.isSqlFile(target)) {
                links = crossFormatLinker.findSqlToJavaLinks(target, allFiles, workspaceRoot);
                sectionTitle = "SQL Migration Cross-Format Links";
            } else {
                links = crossFormatLinker.findYamlToJavaLinks(target, allFiles, workspaceRoot);
                sectionTitle = "YAML Config Cross-Format Links";
            }
            System.out.println();
            if (links.isEmpty()) {
                System.out.println("  " + AnsiOutput.dim("No cross-format Java references found."));
            } else {
                System.out.println("  " + AnsiOutput.bold(AnsiOutput.cyan(sectionTitle + ":")) + " " + links.size() + " file(s)");
                System.out.println();
                for (CrossFormatLinker.CrossFormatLink link : links) {
                    System.out.println("    " + AnsiOutput.green("~>") + " " + link.targetPath() + AnsiOutput.dim(" [" + link.linkType() + ": " + link.entityName() + "]"));
                }
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println("  " + AnsiOutput.dim("Cross-format analysis unavailable: " + e.getMessage()));
        }
    }

    private void printTestCoverage(TestCoverageAnalyzer.TestCoverageResult cov) {
        System.out.println();
        if (cov.testClasses().isEmpty()) {
            System.out.println("  " + AnsiOutput.yellow("No test classes found."));
            String hint = cov.sourceFile().replaceAll(".*/", "").replace(".java", "Test.java");
            System.out.println("  " + AnsiOutput.dim("Tip: create " + hint));
        } else {
            int n = cov.testClasses().size();
            int m = cov.testMethodCount();
            System.out.println("  " + AnsiOutput.bold(AnsiOutput.cyan("Test Coverage:")));
            System.out.println("  " + n + " class(es), " + m + " @Test method(s)");
            System.out.println();
            for (TestCoverageAnalyzer.TestClass tc : cov.testClasses()) {
                System.out.println("    OK " + tc.relativePath());
            }
        }
        System.out.println();
    }
}
