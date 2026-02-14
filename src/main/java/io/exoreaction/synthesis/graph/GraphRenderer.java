package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.GraphBuilder.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Renders {@link FileGraph} data into various output formats:
 * <ul>
 *   <li>DOT (Graphviz format)</li>
 *   <li>Mermaid markdown</li>
 *   <li>PNG/SVG via Graphviz command-line tool</li>
 * </ul>
 *
 * <p>Falls back to Mermaid output if Graphviz is not installed.
 */
public class GraphRenderer {

    /** Output format enumeration. */
    public enum Format {
        PNG, SVG, MERMAID, DOT
    }

    /**
     * Color scheme for different file types.
     */
    private static final Map<String, String> TYPE_COLORS = Map.of(
            "CODE", "#4A90D9",       // blue
            "MARKDOWN", "#5CB85C",   // green
            "YAML", "#9B59B6",       // purple
            "JSON", "#00BCD4",       // cyan
            "CONFIG", "#F0AD4E",     // yellow/orange
            "PDF", "#D9534F",        // red
            "MODULE", "#2C3E50",     // dark blue-gray
            "REPOSITORY", "#E74C3C"  // red
    );

    private static final String DEFAULT_COLOR = "#95A5A6";  // gray

    /**
     * Renders a graph to a file in the specified format.
     *
     * @param graph  the graph to render
     * @param format output format
     * @param output output file path
     * @return true if rendering succeeded
     */
    public boolean render(FileGraph graph, Format format, Path output) throws IOException {
        return switch (format) {
            case DOT -> {
                Files.writeString(output, toDot(graph));
                yield true;
            }
            case MERMAID -> {
                Files.writeString(output, toMermaid(graph));
                yield true;
            }
            case PNG, SVG -> renderWithGraphviz(graph, format, output);
        };
    }

    /**
     * Converts a graph to Graphviz DOT format.
     */
    public String toDot(FileGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph \"").append(escapeDot(graph.title())).append("\" {\n");
        sb.append("    rankdir=LR;\n");
        sb.append("    node [shape=box, style=\"filled,rounded\", fontname=\"Helvetica\", fontsize=10];\n");
        sb.append("    edge [fontname=\"Helvetica\", fontsize=8];\n");
        sb.append("    graph [fontname=\"Helvetica\", label=\"").append(escapeDot(graph.title()))
                .append("\", labelloc=t, fontsize=14];\n");
        sb.append("    bgcolor=\"transparent\";\n\n");

        // Group nodes by directory (subgraph clusters)
        Map<String, List<GraphNode>> byDir = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            byDir.computeIfAbsent(node.directory(), k -> new ArrayList<>()).add(node);
        }

        int clusterIdx = 0;
        for (Map.Entry<String, List<GraphNode>> entry : byDir.entrySet()) {
            if (byDir.size() > 1) {
                sb.append("    subgraph cluster_").append(clusterIdx++).append(" {\n");
                sb.append("        label=\"").append(escapeDot(entry.getKey())).append("\";\n");
                sb.append("        style=dashed;\n");
                sb.append("        color=\"#BDC3C7\";\n");
            }

            for (GraphNode node : entry.getValue()) {
                String color = TYPE_COLORS.getOrDefault(node.fileType(), DEFAULT_COLOR);
                String fontColor = isDarkColor(color) ? "white" : "#2C3E50";
                sb.append("        \"").append(escapeDot(node.id())).append("\" [")
                        .append("label=\"").append(escapeDot(node.label())).append("\", ")
                        .append("fillcolor=\"").append(color).append("\", ")
                        .append("fontcolor=\"").append(fontColor).append("\"")
                        .append("];\n");
            }

            if (byDir.size() > 1) {
                sb.append("    }\n\n");
            }
        }

        // Edges
        for (GraphEdge edge : graph.edges()) {
            String style = edge.type().equals("circular") ? "color=red, style=bold" : "color=\"#7F8C8D\"";
            String penwidth = edge.weight() > 1 ? ", penwidth=" + Math.min(edge.weight(), 5) : "";
            sb.append("    \"").append(escapeDot(edge.sourceId())).append("\" -> \"")
                    .append(escapeDot(edge.targetId())).append("\" [")
                    .append(style).append(penwidth).append("];\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Converts a graph to Mermaid diagram format.
     */
    public String toMermaid(FileGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\ngraph LR\n");

        // Nodes
        for (GraphNode node : graph.nodes()) {
            String id = sanitizeId(node.id());
            sb.append("    ").append(id).append("[\"").append(node.label()).append("\"]\n");

            // Style based on type
            String color = TYPE_COLORS.getOrDefault(node.fileType(), DEFAULT_COLOR);
            sb.append("    style ").append(id).append(" fill:").append(color)
                    .append(",stroke:#333,stroke-width:1px,color:white\n");
        }

        sb.append("\n");

        // Edges
        for (GraphEdge edge : graph.edges()) {
            String sourceId = sanitizeId(edge.sourceId());
            String targetId = sanitizeId(edge.targetId());
            if (edge.weight() > 1) {
                sb.append("    ").append(sourceId).append(" ==>|").append(edge.weight())
                        .append("| ").append(targetId).append("\n");
            } else {
                sb.append("    ").append(sourceId).append(" --> ").append(targetId).append("\n");
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    /**
     * Renders the graph to PNG or SVG using the Graphviz `dot` command.
     * Falls back to Mermaid if Graphviz is not installed.
     *
     * @return true if Graphviz rendered successfully, false if fell back to Mermaid
     */
    private boolean renderWithGraphviz(FileGraph graph, Format format, Path output) throws IOException {
        if (!isGraphvizAvailable()) {
            // Fallback to Mermaid markdown
            Path mermaidOutput = output.resolveSibling(
                    output.getFileName().toString().replaceAll("\\.(png|svg)$", ".md"));
            Files.writeString(mermaidOutput, toMermaid(graph));
            return false; // Signal that we fell back
        }

        // Write DOT to temp file
        Path dotFile = Files.createTempFile("synthesis-graph-", ".dot");
        try {
            Files.writeString(dotFile, toDot(graph));

            String formatFlag = format == Format.PNG ? "png" : "svg";
            ProcessBuilder pb = new ProcessBuilder(
                    "dot", "-T" + formatFlag, dotFile.toString(), "-o", output.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            return exitCode == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            Files.deleteIfExists(dotFile);
        }
    }

    /**
     * Checks if Graphviz is installed and available.
     */
    public static boolean isGraphvizAvailable() {
        try {
            Process process = new ProcessBuilder("dot", "-V")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String escapeDot(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private boolean isDarkColor(String hexColor) {
        if (hexColor.startsWith("#") && hexColor.length() == 7) {
            int r = Integer.parseInt(hexColor.substring(1, 3), 16);
            int g = Integer.parseInt(hexColor.substring(3, 5), 16);
            int b = Integer.parseInt(hexColor.substring(5, 7), 16);
            double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
            return luminance < 0.5;
        }
        return false;
    }
}
