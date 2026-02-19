package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.RelationService;
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

    private final RelationService relationService = new RelationService();

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) { AnsiOutput.printError(validation.get()); return 1; }

            List<SearchResult> targetResults;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                targetResults = index.search(targetFile, 10);
            }
            SearchResult target = relationService.findBestMatch(targetResults, targetFile);
            if (target == null) { AnsiOutput.printError("File not found in index: " + targetFile); AnsiOutput.printInfo("Try 'synthesis search " + targetFile + "' to find it."); return 1; }

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

            if (mermaid) { System.out.println(relationService.generateMermaid(relationshipMap)); }
            else { printRelationships(relationshipMap, target); }
            return 0;
        } catch (Exception e) { AnsiOutput.printError("Relate failed: " + e.getMessage()); return 1; }
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
}
