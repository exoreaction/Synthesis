package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.GraphBuilder;
import io.exoreaction.synthesis.graph.GraphBuilder.*;
import io.exoreaction.synthesis.graph.GraphRenderer;
import io.exoreaction.synthesis.graph.GraphRenderer.Format;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Generates visual representations of knowledge graphs.
 *
 * <p>Supports file relationship graphs, module graphs, and cross-repo
 * dependency graphs in PNG, SVG, Mermaid, and DOT formats.
 *
 * <p>Usage:
 * <pre>
 *   synthesis graph CLAUDE.md --format png --output graph.png
 *   synthesis graph --modules --format svg --output architecture.svg
 *   synthesis graph --cross-repo --format png --output deps.png
 *   synthesis graph --all --depth 2 --format mermaid
 * </pre>
 */
@Command(
        name = "graph",
        description = "Generate visual knowledge graph",
        mixinStandardHelpOptions = true
)
public class GraphCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "Target file to graph relationships for",
            defaultValue = "",
            arity = "0..1"
    )
    private String targetFile;

    @Option(
            names = {"--format", "-f"},
            description = "Output format: png, svg, mermaid, dot (default: png)",
            defaultValue = "png"
    )
    private String format;

    @Option(
            names = {"--output", "-o"},
            description = "Output file path (default: auto-generated)"
    )
    private String output;

    @Option(
            names = {"--depth"},
            description = "How many levels of relationships to include (default: 1)",
            defaultValue = "1"
    )
    private int depth;

    @Option(
            names = {"--modules"},
            description = "Generate module/directory dependency graph",
            defaultValue = "false"
    )
    private boolean modules;

    @Option(
            names = {"--cross-repo"},
            description = "Generate cross-repository dependency graph",
            defaultValue = "false"
    )
    private boolean crossRepo;

    @Option(
            names = {"--all"},
            description = "Include all files in the graph (may be large)",
            defaultValue = "false"
    )
    private boolean all;

    @Option(
            names = {"--repo"},
            description = "Scope graph to a specific repository"
    )
    private String repo;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Graph Generation");

            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Determine format
            Format renderFormat = parseFormat(format);

            // Load files
            List<SearchResult> allFiles;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                if (repo != null && !repo.isBlank()) {
                    allFiles = index.listAll(null, repo, 50000);
                } else {
                    allFiles = index.listAll(null, 50000);
                }
            }

            if (allFiles.isEmpty()) {
                AnsiOutput.printWarning("No files in index. Run 'synthesis scan' first.");
                return 0;
            }

            // Size check
            if (allFiles.size() > 100 && !modules && !crossRepo && !all && targetFile.isEmpty()) {
                AnsiOutput.printWarning("Graph has " + allFiles.size() + " files. "
                        + "Use --modules for overview or specify a target file.");
                AnsiOutput.printInfo("Add --all to force full graph generation.");
                return 0;
            }

            GraphBuilder builder = new GraphBuilder();
            GraphRenderer renderer = new GraphRenderer();
            FileGraph graph;

            // Build the appropriate graph type
            if (modules) {
                graph = builder.buildModuleGraph(allFiles);
            } else if (crossRepo) {
                graph = builder.buildCrossRepoGraph(allFiles);
            } else if (!targetFile.isEmpty()) {
                // Find target in index
                SearchResult target = findTarget(allFiles, targetFile);
                if (target == null) {
                    AnsiOutput.printError("File not found in index: " + targetFile);
                    return 1;
                }
                graph = builder.buildFileGraph(target, allFiles, depth);
            } else {
                // Default: module graph for manageable visualization
                graph = builder.buildModuleGraph(allFiles);
            }

            // Determine output path
            Path outputPath = resolveOutputPath(renderFormat);

            // Report
            AnsiOutput.printInfo("Graph: " + graph.title());
            AnsiOutput.printInfo("Nodes: " + graph.nodes().size());
            AnsiOutput.printInfo("Edges: " + graph.edges().size());

            if (renderFormat == Format.MERMAID) {
                // Print to stdout
                String mermaid = renderer.toMermaid(graph);
                System.out.println();
                System.out.println(mermaid);

                if (output != null) {
                    Files.writeString(outputPath, mermaid);
                    AnsiOutput.printSuccess("Saved to " + outputPath);
                }
            } else if (renderFormat == Format.DOT) {
                String dot = renderer.toDot(graph);
                if (output != null) {
                    Files.writeString(outputPath, dot);
                    AnsiOutput.printSuccess("DOT file saved to " + outputPath);
                } else {
                    System.out.println();
                    System.out.println(dot);
                }
            } else {
                // PNG or SVG
                if (!GraphRenderer.isGraphvizAvailable()) {
                    AnsiOutput.printWarning("Graphviz not installed. Falling back to Mermaid format.");
                    AnsiOutput.printInfo("Install Graphviz: sudo apt install graphviz");
                    System.out.println();

                    // Fallback to mermaid
                    String mermaid = renderer.toMermaid(graph);
                    System.out.println(mermaid);

                    Path mermaidPath = outputPath.resolveSibling(
                            outputPath.getFileName().toString()
                                    .replaceAll("\\.(png|svg)$", ".md"));
                    Files.writeString(mermaidPath, mermaid);
                    AnsiOutput.printInfo("Mermaid saved to " + mermaidPath);
                    return 0;
                }

                boolean success = renderer.render(graph, renderFormat, outputPath);
                if (success) {
                    long fileSize = Files.size(outputPath);
                    AnsiOutput.printSuccess("Graph rendered to " + outputPath +
                            " (" + io.exoreaction.synthesis.util.FileUtils.formatSize(fileSize) + ")");
                } else {
                    AnsiOutput.printError("Graph rendering failed");
                    return 1;
                }
            }

            System.out.println();
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Graph generation failed: " + e.getMessage());
            return 1;
        }
    }

    private Format parseFormat(String fmt) {
        return switch (fmt.toLowerCase()) {
            case "svg" -> Format.SVG;
            case "mermaid", "md" -> Format.MERMAID;
            case "dot" -> Format.DOT;
            default -> Format.PNG;
        };
    }

    private Path resolveOutputPath(Format fmt) {
        if (output != null) {
            return Path.of(output).toAbsolutePath();
        }
        String extension = switch (fmt) {
            case PNG -> ".png";
            case SVG -> ".svg";
            case MERMAID -> ".md";
            case DOT -> ".dot";
        };
        String baseName = !targetFile.isEmpty() ? targetFile.replaceAll("[^a-zA-Z0-9]", "_") :
                (modules ? "modules" : crossRepo ? "cross-repo" : "graph");
        return Path.of(baseName + extension).toAbsolutePath();
    }

    private SearchResult findTarget(List<SearchResult> files, String target) {
        // Exact match
        for (SearchResult f : files) {
            if (f.relativePath().equals(target) || f.relativePath().endsWith("/" + target)) {
                return f;
            }
        }
        // Filename match
        for (SearchResult f : files) {
            if (f.fileName().equals(target)) {
                return f;
            }
        }
        // Partial match
        for (SearchResult f : files) {
            if (f.fileName().contains(target) || f.relativePath().contains(target)) {
                return f;
            }
        }
        return null;
    }
}
