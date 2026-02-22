package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis knowledge-graph} -- visualize the filesystem knowledge graph.
 *
 * <p>Shows directories as nodes (with centroid topics), virtual memberships as edges,
 * and entity relationships as cross-links. Supports Mermaid, ASCII, and JSON output.
 *
 * <p>Usage:
 * <pre>
 *   synthesis knowledge-graph                               # ASCII overview
 *   synthesis knowledge-graph --format mermaid              # Mermaid diagram
 *   synthesis knowledge-graph --entity "GreenField Energy"  # entity-centric view
 *   synthesis knowledge-graph --format json                 # machine-readable
 * </pre>
 *
 * @since v2.0 (P4-05)
 */
@Command(
        name = "knowledge-graph",
        aliases = {"kg"},
        description = "Visualize the filesystem knowledge graph (directories, centroids, memberships)",
        mixinStandardHelpOptions = true
)
public class KnowledgeGraphCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--format", "-f"},
            description = "Output format: ascii, mermaid, json (default: ascii)",
            defaultValue = "ascii")
    private String format;

    @Option(names = {"--entity", "-e"},
            description = "Show entity-centric view (all directories mentioning this entity)")
    private String entity;

    @Option(names = {"--output", "-o"},
            description = "Write output to file instead of stdout")
    private Path outputFile;

    /** Output stream for testability. */
    private PrintStream out = System.out;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            DirectoryIdentityParser parser = new DirectoryIdentityParser();

            // Collect all profiles
            List<KnowledgeNode> nodes = collectNodes(workspaceRoot, parser);

            if (nodes.isEmpty()) {
                out.println("No directories with .synthesis.md files found.");
                out.println("Run 'synthesis sync' first.");
                return 0;
            }

            // Collect virtual memberships if database exists
            List<KnowledgeEdge> edges = collectEdges(workspaceRoot);

            // Filter by entity if specified
            if (entity != null && !entity.isBlank()) {
                nodes = filterByEntity(nodes, entity);
                if (nodes.isEmpty()) {
                    out.println("No directories found mentioning entity: " + entity);
                    return 0;
                }
            }

            // Render
            String output = switch (format.toLowerCase()) {
                case "mermaid" -> renderMermaid(nodes, edges, workspaceRoot);
                case "json" -> renderJson(nodes, edges, workspaceRoot);
                default -> renderAscii(nodes, edges, workspaceRoot);
            };

            if (outputFile != null) {
                Files.writeString(outputFile, output);
                out.println("Knowledge graph written to: " + outputFile);
            } else {
                out.println(output);
            }

            return 0;
        } catch (Exception e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // ---- Collection ----

    List<KnowledgeNode> collectNodes(Path workspaceRoot,
                                       DirectoryIdentityParser parser) throws IOException {
        List<KnowledgeNode> nodes = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        String relPath = workspaceRoot.relativize(dir).toString();

                        DirectoryCentroid centroid = profile.centroid();
                        DirectoryWants wants = profile.wants();
                        DirectoryHealth health = profile.health();

                        List<String> topics = new ArrayList<>();
                        List<String> entities = new ArrayList<>();

                        if (!centroid.isEmpty()) {
                            topics.addAll(centroid.topics());
                            entities.addAll(centroid.entities());
                        } else if (!wants.isEmpty()) {
                            topics.addAll(wants.topics());
                            entities.addAll(wants.entities());
                        }

                        String status = health.isEmpty() ? "unknown" : health.status();

                        nodes.add(new KnowledgeNode(
                                relPath, topics, entities,
                                centroid.isEmpty() ? 0.0 : centroid.confidence(),
                                centroid.contributingFiles(),
                                centroid.virtualMembers(),
                                status));
                    });
        }

        nodes.sort(Comparator.comparing(KnowledgeNode::path));
        return nodes;
    }

    List<KnowledgeEdge> collectEdges(Path workspaceRoot) {
        List<KnowledgeEdge> edges = new ArrayList<>();
        Path dbPath = workspaceRoot.resolve(".synthesis/synthesis.db");
        if (!Files.exists(dbPath)) {
            return edges;
        }

        try (SynthesisDatabase db = new SynthesisDatabase(dbPath)) {
            VirtualMembershipManager vmm = new VirtualMembershipManager(db);
            // Query all virtual memberships for this workspace
            java.sql.Connection conn = db.getConnection();
            String sql = "SELECT file_path, directory_path, relationship, bid_strength "
                    + "FROM virtual_memberships WHERE workspace_path = ? "
                    + "ORDER BY bid_strength DESC";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspaceRoot.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        edges.add(new KnowledgeEdge(
                                rs.getString("file_path"),
                                rs.getString("directory_path"),
                                rs.getString("relationship"),
                                rs.getDouble("bid_strength")));
                    }
                }
            }
        } catch (SQLException e) {
            // Return empty edges silently
        }

        return edges;
    }

    // ---- Filtering ----

    static List<KnowledgeNode> filterByEntity(List<KnowledgeNode> nodes, String entity) {
        String entityLower = entity.toLowerCase();
        return nodes.stream()
                .filter(n -> n.entities.stream()
                        .anyMatch(e -> e.toLowerCase().contains(entityLower)))
                .toList();
    }

    // ---- Rendering ----

    String renderAscii(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges,
                        Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge Graph: ").append(workspaceRoot).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        sb.append(String.format("Directories: %d  |  Virtual links: %d%n%n",
                nodes.size(), edges.size()));

        for (KnowledgeNode node : nodes) {
            sb.append(formatAsciiNode(node));
        }

        if (!edges.isEmpty()) {
            sb.append("\nVirtual Memberships:\n");
            sb.append("-".repeat(40)).append("\n");
            for (KnowledgeEdge edge : edges) {
                sb.append(String.format("  %s -> %s (%.2f, %s)%n",
                        shortenPath(edge.filePath, 30),
                        edge.directoryPath,
                        edge.bidStrength,
                        edge.relationship));
            }
        }

        return sb.toString();
    }

    private String formatAsciiNode(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        String statusIcon = switch (node.status) {
            case "healthy" -> "[OK]";
            case "bootstrapping" -> "[..] ";
            case "starving" -> "[!!]";
            case "drifting" -> "[~>]";
            default -> "[??]";
        };

        sb.append(String.format("  %s %s/%n", statusIcon, node.path));

        if (!node.topics.isEmpty()) {
            String topicsStr = node.topics.size() > 3
                    ? String.join(", ", node.topics.subList(0, 3)) + "..."
                    : String.join(", ", node.topics);
            sb.append(String.format("      Topics: %s%n", topicsStr));
        }

        if (!node.entities.isEmpty()) {
            sb.append(String.format("      Entities: %s%n",
                    String.join(", ", node.entities)));
        }

        if (node.confidence > 0.0) {
            sb.append(String.format("      Confidence: %.2f (%d files, %d virtual)%n",
                    node.confidence, node.contributingFiles, node.virtualMembers));
        }

        sb.append("\n");
        return sb.toString();
    }

    String renderMermaid(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges,
                          Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\ngraph TD\n");

        // Create safe IDs
        Map<String, String> nodeIds = new HashMap<>();
        int idCounter = 0;

        for (KnowledgeNode node : nodes) {
            String id = "dir" + (idCounter++);
            nodeIds.put(node.path, id);

            String label = node.path;
            if (!node.topics.isEmpty()) {
                String topicStr = node.topics.size() > 2
                        ? node.topics.get(0) + ", " + node.topics.get(1) + "..."
                        : String.join(", ", node.topics);
                label += "\\n" + topicStr;
            }

            // Shape based on status
            String shape = switch (node.status) {
                case "healthy" -> "[" + label + "]";
                case "bootstrapping" -> "([" + label + "])";
                case "starving" -> "{{" + label + "}}";
                case "drifting" -> ">" + label + "]";
                default -> "[" + label + "]";
            };

            sb.append("    ").append(id).append(shape).append("\n");
        }

        // Edges: virtual memberships
        for (KnowledgeEdge edge : edges) {
            // Find the source directory (parent directory of the file)
            String fileDir = edge.filePath.contains("/")
                    ? edge.filePath.substring(0, edge.filePath.lastIndexOf('/'))
                    : "";
            String sourceId = nodeIds.get(fileDir);
            String targetId = nodeIds.get(edge.directoryPath);

            if (sourceId != null && targetId != null) {
                sb.append(String.format("    %s -.->|%s| %s%n",
                        sourceId, edge.relationship, targetId));
            }
        }

        // Entity-based cross-links (directories sharing entities)
        Map<String, List<String>> entityToDirs = new HashMap<>();
        for (KnowledgeNode node : nodes) {
            for (String ent : node.entities) {
                entityToDirs.computeIfAbsent(ent.toLowerCase(), k -> new ArrayList<>())
                        .add(node.path);
            }
        }

        for (Map.Entry<String, List<String>> entry : entityToDirs.entrySet()) {
            List<String> dirs = entry.getValue();
            if (dirs.size() > 1) {
                for (int i = 0; i < dirs.size() - 1; i++) {
                    String id1 = nodeIds.get(dirs.get(i));
                    String id2 = nodeIds.get(dirs.get(i + 1));
                    if (id1 != null && id2 != null) {
                        sb.append(String.format("    %s ~~~|%s| %s%n",
                                id1, entry.getKey(), id2));
                    }
                }
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    String renderJson(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges,
                       Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"workspace\": \"").append(escapeJson(workspaceRoot.toString())).append("\",\n");

        // Nodes
        sb.append("  \"directories\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            KnowledgeNode node = nodes.get(i);
            sb.append("    {\n");
            sb.append("      \"path\": \"").append(escapeJson(node.path)).append("\",\n");
            sb.append("      \"topics\": ").append(jsonArray(node.topics)).append(",\n");
            sb.append("      \"entities\": ").append(jsonArray(node.entities)).append(",\n");
            sb.append("      \"confidence\": ").append(String.format("%.2f", node.confidence)).append(",\n");
            sb.append("      \"contributingFiles\": ").append(node.contributingFiles).append(",\n");
            sb.append("      \"virtualMembers\": ").append(node.virtualMembers).append(",\n");
            sb.append("      \"status\": \"").append(node.status).append("\"\n");
            sb.append("    }");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Edges
        sb.append("  \"virtualMemberships\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            KnowledgeEdge edge = edges.get(i);
            sb.append("    {\n");
            sb.append("      \"file\": \"").append(escapeJson(edge.filePath)).append("\",\n");
            sb.append("      \"directory\": \"").append(escapeJson(edge.directoryPath)).append("\",\n");
            sb.append("      \"relationship\": \"").append(escapeJson(edge.relationship)).append("\",\n");
            sb.append("      \"bidStrength\": ").append(String.format("%.2f", edge.bidStrength)).append("\n");
            sb.append("    }");
            if (i < edges.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    // ---- Utilities ----

    static String shortenPath(String path, int maxLen) {
        if (path.length() <= maxLen) return path;
        return "..." + path.substring(path.length() - maxLen + 3);
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    static String jsonArray(List<String> items) {
        if (items.isEmpty()) return "[]";
        return "[" + items.stream()
                .map(s -> "\"" + escapeJson(s) + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("") + "]";
    }

    // ---- Test support ----

    void setParent(SynthesisApp parent) {
        this.parent = parent;
    }

    void setFormat(String format) {
        this.format = format;
    }

    void setEntity(String entity) {
        this.entity = entity;
    }

    void setOut(PrintStream out) {
        this.out = out;
    }

    // ---- Records ----

    record KnowledgeNode(
            String path,
            List<String> topics,
            List<String> entities,
            double confidence,
            int contributingFiles,
            int virtualMembers,
            String status
    ) {}

    record KnowledgeEdge(
            String filePath,
            String directoryPath,
            String relationship,
            double bidStrength
    ) {}
}
