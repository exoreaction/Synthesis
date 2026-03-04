package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CrossWorkspaceResolver;
import io.exoreaction.synthesis.kcp.KcpRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** Markdown link pattern: [text](target) -- shared with GraphBuilder. */
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)", Pattern.MULTILINE);

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--format", "-f"},
            description = "Output format: ascii, mermaid, json (default: ascii)",
            defaultValue = "ascii")
    private String format;

    @Option(names = {"--entity", "-e"},
            description = "Show entity-centric view (all directories mentioning this entity)")
    private String entity;

    @Option(names = {"--scope", "-s"},
            description = "Filter nodes to a subtree (e.g. eXOReaction/ or eXOReaction)")
    private String scope;

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

            // Fallback: extract cross-reference edges from markdown links (#276)
            List<KnowledgeEdge> crossRefEdges = collectCrossReferenceEdges(workspaceRoot, nodes);
            if (!crossRefEdges.isEmpty()) {
                edges = new ArrayList<>(edges);
                edges.addAll(crossRefEdges);
            }

            // Entity-based implicit edges (Feature B)
            List<KnowledgeEdge> entityEdges = collectEntityEdges(nodes);
            if (!entityEdges.isEmpty()) {
                if (edges instanceof ArrayList) {
                    edges.addAll(entityEdges);
                } else {
                    edges = new ArrayList<>(edges);
                    edges.addAll(entityEdges);
                }
            }

            // Declared edges from related: field in .synthesis.md (Feature C)
            List<KnowledgeEdge> declaredEdges = collectDeclaredEdges(workspaceRoot, nodes);
            if (!declaredEdges.isEmpty()) {
                if (edges instanceof ArrayList) {
                    edges.addAll(declaredEdges);
                } else {
                    edges = new ArrayList<>(edges);
                    edges.addAll(declaredEdges);
                }
            }

            // Cross-workspace edges from srcPath / clientRepos config (Issue #281)
            List<KnowledgeEdge> crossWsEdges = collectCrossWorkspaceEdges(workspaceRoot, nodes);
            if (!crossWsEdges.isEmpty()) {
                if (edges instanceof ArrayList) {
                    edges.addAll(crossWsEdges);
                } else {
                    edges = new ArrayList<>(edges);
                    edges.addAll(crossWsEdges);
                }
            }

            // Filter by entity if specified
            if (entity != null && !entity.isBlank()) {
                nodes = filterByEntity(nodes, entity);
                if (nodes.isEmpty()) {
                    out.println("No directories found mentioning entity: " + entity);
                    return 0;
                }
            }

            // Collect KCP units and unit-to-unit relationships from DB (Phase 5)
            List<KcpUnitNode> kcpUnits = collectKcpUnits(workspaceRoot);
            List<KcpUnitEdge> kcpEdges = collectKcpRelEdges(workspaceRoot);

            // Render
            String output = switch (format.toLowerCase()) {
                case "mermaid" -> renderMermaid(nodes, edges, kcpUnits, kcpEdges, workspaceRoot);
                case "json" -> renderJson(nodes, edges, kcpUnits, kcpEdges, workspaceRoot);
                default -> renderAscii(nodes, edges, kcpUnits, kcpEdges, workspaceRoot);
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
        List<KnowledgeNode> rawNodes = new ArrayList<>();

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

                        rawNodes.add(new KnowledgeNode(
                                relPath, topics, entities,
                                centroid.isEmpty() ? 0.0 : centroid.confidence(),
                                centroid.contributingFiles(),
                                centroid.virtualMembers(),
                                status));
                    });
        }

        rawNodes.sort(Comparator.comparing(KnowledgeNode::path));

        // Apply scope filter if set
        if (scope != null && !scope.isBlank()) {
            String normalizedScope = scope.endsWith("/")
                    ? scope.substring(0, scope.length() - 1)
                    : scope;
            return rawNodes.stream()
                    .filter(n -> n.path().equals(normalizedScope)
                            || n.path().startsWith(normalizedScope + "/"))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        return rawNodes;
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

    /**
     * Scans markdown files in each node directory for cross-references to other
     * node directories. This provides edges for documentation workspaces where
     * the virtual_memberships table is empty (#276).
     *
     * <p>Uses the same {@code [text](link)} pattern as {@code GraphBuilder}.
     * Resolves relative links (e.g., {@code ../beta/overview.md}) to directory
     * paths and creates edges between directories that reference each other.
     *
     * @param workspaceRoot the workspace root directory
     * @param nodes         the collected knowledge nodes (directories)
     * @return list of cross-reference edges
     */
    List<KnowledgeEdge> collectCrossReferenceEdges(Path workspaceRoot,
                                                     List<KnowledgeNode> nodes) {
        List<KnowledgeEdge> edges = new ArrayList<>();
        Set<String> nodePaths = new HashSet<>();
        for (KnowledgeNode node : nodes) {
            nodePaths.add(node.path());
        }

        for (KnowledgeNode node : nodes) {
            Path nodeDir = workspaceRoot.resolve(node.path());
            if (!Files.isDirectory(nodeDir)) continue;

            // Scan markdown files recursively in this directory (#276 follow-up)
            try (Stream<Path> files = Files.walk(nodeDir)) {
                files.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().endsWith(".md"))
                     .filter(p -> !p.getFileName().toString().equals(".synthesis.md"))
                     .forEach(mdFile -> {
                         try {
                             String content = Files.readString(mdFile);
                             Matcher m = MARKDOWN_LINK.matcher(content);
                             while (m.find()) {
                                 String link = m.group(2);
                                 // Skip external links and anchors
                                 if (link.startsWith("http") || link.startsWith("#")) continue;

                                 // Resolve the link relative to the markdown file's directory
                                 try {
                                     Path resolved = mdFile.getParent().resolve(link).normalize();
                                     // If the link points to a file, use its parent directory
                                     Path targetDir = Files.isDirectory(resolved) ? resolved : resolved.getParent();
                                     if (targetDir == null) continue;

                                     // Find the closest ancestor that is a known node
                                     Path current = targetDir;
                                     while (current != null && current.startsWith(workspaceRoot)
                                            && !current.equals(workspaceRoot)) {
                                         String relPath = workspaceRoot.relativize(current).toString();
                                         if (nodePaths.contains(relPath) && !relPath.equals(node.path())) {
                                             String relFile = workspaceRoot.relativize(mdFile).toString();
                                             edges.add(new KnowledgeEdge(
                                                     relFile, relPath,
                                                     "cross-reference", 0.5));
                                             break;
                                         }
                                         current = current.getParent();
                                     }
                                 } catch (Exception ignored) {
                                     // Invalid path, skip
                                 }
                             }
                         } catch (IOException ignored) {
                             // Unreadable file, skip
                         }
                     });
            } catch (IOException ignored) {
                // Can't list directory, skip
            }
        }

        // Deduplicate edges (same source file -> same target dir)
        Map<String, KnowledgeEdge> deduped = new LinkedHashMap<>();
        for (KnowledgeEdge edge : edges) {
            deduped.putIfAbsent(edge.filePath() + "|" + edge.directoryPath(), edge);
        }
        return new ArrayList<>(deduped.values());
    }

    /** Generic entity names to exclude from entity-match edges (too noisy). */
    private static final Set<String> GENERIC_ENTITIES = Set.of(
            "media type", "ai summary", "ai description", "ai title",
            "microsoft office word", "intel mac os"
    );

    /**
     * Creates implicit edges between nodes that share entity names from their
     * centroid {@code entities:} lists. Generic/noise entities are excluded.
     * Confidence scales with the number of shared entities, capped at 0.8.
     *
     * @param nodes the collected knowledge nodes
     * @return list of entity-match edges
     */
    List<KnowledgeEdge> collectEntityEdges(List<KnowledgeNode> nodes) {
        List<KnowledgeEdge> edges = new ArrayList<>();

        // Build map: entity (lowercase) -> list of node paths that mention it
        Map<String, List<String>> entityToNodes = new HashMap<>();
        for (KnowledgeNode node : nodes) {
            for (String ent : node.entities()) {
                String key = ent.toLowerCase();
                if (GENERIC_ENTITIES.contains(key)) continue;
                entityToNodes.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(node.path());
            }
        }

        // For each pair of nodes, count shared entities
        Map<String, Integer> pairSharedCount = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : entityToNodes.entrySet()) {
            List<String> paths = entry.getValue();
            if (paths.size() < 2) continue;
            for (int i = 0; i < paths.size(); i++) {
                for (int j = i + 1; j < paths.size(); j++) {
                    String pairKey = paths.get(i) + "|" + paths.get(j);
                    pairSharedCount.merge(pairKey, 1, Integer::sum);
                }
            }
        }

        // Find the maximum shared count for scaling
        int maxShared = pairSharedCount.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1);

        // Create edges with confidence proportional to shared count, capped at 0.8
        for (Map.Entry<String, Integer> entry : pairSharedCount.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            int shared = entry.getValue();
            double confidence = Math.min(0.8, (double) shared / Math.max(maxShared, 1) * 0.8);
            // Ensure minimum confidence for any match
            if (confidence < 0.1) confidence = 0.1;
            edges.add(new KnowledgeEdge(parts[0], parts[1], "entity-match", confidence));
        }

        return edges;
    }

    /** Pattern for parsing list items in YAML: {@code - "value"} or {@code - value}. */
    private static final Pattern YAML_LIST_ITEM =
            Pattern.compile("^\\s+-\\s+\"?([^\"]+?)\"?\\s*$");

    /**
     * Creates declared edges from the {@code synthesis.related} field in
     * {@code .synthesis.md} files. Each declared path that exists as a known
     * node produces an edge with type {@code "declared"} and confidence 1.0.
     * Missing target nodes are silently skipped (logged as warning in production).
     *
     * @param workspaceRoot the workspace root directory
     * @param nodes         the collected knowledge nodes
     * @return list of declared edges
     */
    List<KnowledgeEdge> collectDeclaredEdges(Path workspaceRoot,
                                              List<KnowledgeNode> nodes) {
        List<KnowledgeEdge> edges = new ArrayList<>();
        Set<String> nodePaths = new HashSet<>();
        for (KnowledgeNode node : nodes) {
            nodePaths.add(node.path());
        }

        for (KnowledgeNode node : nodes) {
            Path synthesisFile = workspaceRoot.resolve(node.path()).resolve(".synthesis.md");
            if (!Files.exists(synthesisFile)) continue;

            List<String> related = parseRelatedField(synthesisFile);
            for (String target : related) {
                if (nodePaths.contains(target) && !target.equals(node.path())) {
                    edges.add(new KnowledgeEdge(
                            node.path(), target, "declared", 1.0));
                }
                // If target doesn't exist as a node, silently skip
            }
        }

        return edges;
    }

    /**
     * Creates cross-workspace edges from {@code srcPath} and {@code clientRepos}
     * declarations in the workspace configuration (Issue #281).
     *
     * <p>For each knowledge node, asks {@link CrossWorkspaceResolver} whether any
     * declared src path matches the node's relative path. Matching nodes produce a
     * {@link KnowledgeEdge} with:
     * <ul>
     *   <li>{@code filePath} = the docs dir path (source node key)</li>
     *   <li>{@code directoryPath} = the absolute src workspace path (target)</li>
     *   <li>{@code relationship} = {@code "src"}</li>
     *   <li>{@code bidStrength} = 0.9 (clientRepo) or 0.8 (subWorkspace)</li>
     * </ul>
     *
     * <p>Returns an empty list if no sub-workspace has {@code srcPath} declared,
     * or if the config file cannot be loaded.
     *
     * @param workspaceRoot the workspace root directory
     * @param nodes         the collected knowledge nodes
     * @return deduplicated list of cross-workspace edges
     */
    List<KnowledgeEdge> collectCrossWorkspaceEdges(Path workspaceRoot,
                                                    List<KnowledgeNode> nodes) {
        SynthesisConfig config;
        try {
            config = ConfigLoader.load(workspaceRoot);
        } catch (IOException e) {
            return List.of(); // config unavailable — skip silently
        }

        // Early exit: no sub-workspace has srcPath or clientRepos declared
        boolean anyDeclared = config.getSubWorkspaces().stream()
                .anyMatch(sub -> !sub.getSrcPath().isBlank()
                        || !sub.getClientRepos().isEmpty());
        if (!anyDeclared) return List.of();

        CrossWorkspaceResolver resolver = new CrossWorkspaceResolver();
        Map<String, KnowledgeEdge> deduped = new LinkedHashMap<>();

        for (KnowledgeNode node : nodes) {
            List<CrossWorkspaceResolver.CrossWorkspaceLink> links =
                    resolver.resolve(config, node.path());
            for (CrossWorkspaceResolver.CrossWorkspaceLink link : links) {
                String key = link.docsRelPath() + "|" + link.srcAbsPath();
                deduped.putIfAbsent(key, new KnowledgeEdge(
                        link.docsRelPath(),
                        link.srcAbsPath(),
                        "src",
                        link.confidence()));
            }
        }

        return new ArrayList<>(deduped.values());
    }

    /**
     * Parses the {@code related:} list from a {@code .synthesis.md} file's
     * YAML front matter. Returns an empty list if the field is absent.
     */
    private List<String> parseRelatedField(Path synthesisFile) {
        List<String> related = new ArrayList<>();
        try {
            String content = Files.readString(synthesisFile);
            // Extract YAML front matter
            String trimmed = content.stripLeading();
            if (!trimmed.startsWith("---")) return related;
            int firstDelim = trimmed.indexOf("---");
            int secondDelim = trimmed.indexOf("---", firstDelim + 3);
            if (secondDelim < 0) return related;
            String yaml = trimmed.substring(firstDelim + 3, secondDelim);

            boolean inRelated = false;
            for (String line : yaml.split("\n")) {
                String stripped = line.stripTrailing();
                String trimmedLine = stripped.stripLeading();
                int indent = stripped.length() - trimmedLine.length();

                // Detect entering related block (at indent <= 2, under synthesis:)
                if (trimmedLine.startsWith("related:") && indent <= 2) {
                    inRelated = true;
                    continue;
                }

                // Exit related on a same-level or higher-level key
                if (inRelated && indent <= 2 && !trimmedLine.isEmpty()
                        && !trimmedLine.startsWith("-") && !trimmedLine.startsWith("#")) {
                    inRelated = false;
                }

                if (!inRelated) continue;

                // Parse list items
                if (trimmedLine.startsWith("- ")) {
                    Matcher m = YAML_LIST_ITEM.matcher(stripped);
                    if (m.matches()) {
                        related.add(m.group(1));
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable file, return empty
        }
        return related;
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

    /** Backward-compat overload (no KCP data). Used by tests. */
    String renderAscii(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges, Path workspaceRoot) {
        return renderAscii(nodes, edges, List.of(), List.of(), workspaceRoot);
    }

    String renderAscii(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges,
                        List<KcpUnitNode> kcpUnits, List<KcpUnitEdge> kcpEdges,
                        Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();

        boolean scoped = (scope != null && !scope.isBlank());
        String normalizedScope = scoped
                ? (scope.endsWith("/") ? scope.substring(0, scope.length() - 1) : scope)
                : null;

        if (scoped) {
            sb.append("Knowledge Graph: ").append(workspaceRoot).append(" [scope: ")
              .append(normalizedScope).append("]\n");
        } else {
            sb.append("Knowledge Graph: ").append(workspaceRoot).append("\n");
        }
        sb.append("=".repeat(60)).append("\n\n");

        if (scoped) {
            // Build the set of in-scope node paths
            Set<String> scopedPaths = new HashSet<>();
            for (KnowledgeNode node : nodes) {
                scopedPaths.add(node.path());
            }

            // Partition edges into internal (both endpoints in scope) and external
            long internalEdges = 0;
            long externalEdges = 0;
            for (KnowledgeEdge edge : edges) {
                // Determine source dir of edge (parent of filePath, or filePath itself for entity edges)
                String srcDir = edge.filePath().contains("/")
                        ? edge.filePath().substring(0, edge.filePath().lastIndexOf('/'))
                        : edge.filePath();
                boolean srcInScope = scopedPaths.contains(srcDir)
                        || scopedPaths.contains(edge.filePath());
                boolean tgtInScope = scopedPaths.contains(edge.directoryPath());
                if (srcInScope && tgtInScope) {
                    internalEdges++;
                } else {
                    externalEdges++;
                }
            }

            int n = nodes.size();
            double maxPossibleEdges = (n < 2) ? 1.0 : (double) n * (n - 1) / 2.0;
            double tightness = (n < 2) ? 0.0 : (double) internalEdges / maxPossibleEdges;

            sb.append(String.format("Directories: %d  |  Internal links: %d  |  External links: %d%n%n",
                    n, internalEdges, externalEdges));
            sb.append(String.format("Tightness: %.2f%n%n", tightness));
        } else {
            sb.append(String.format("Directories: %d  |  Virtual links: %d%n%n",
                    nodes.size(), edges.size()));
        }

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

        // Hint when most nodes show [??] (unknown health status) (#276)
        if (!nodes.isEmpty()) {
            long unknownCount = nodes.stream()
                    .filter(n -> "unknown".equals(n.status()))
                    .count();
            if (unknownCount > nodes.size() / 2) {
                sb.append("\nHint: ").append(unknownCount).append("/").append(nodes.size())
                  .append(" directories show [??] (unknown health).\n");
                sb.append("  Run 'synthesis sync --enrich-centroids' to compute health status.\n");
            }
        }

        // Global breakdown: per-top-level-dir tightness table (only when no scope)
        if (!scoped && !nodes.isEmpty()) {
            sb.append(renderSubworkspaceBreakdown(nodes, edges));
        }

        // KCP knowledge units section
        if (!kcpUnits.isEmpty()) {
            sb.append(renderKcpAsciiSection(kcpUnits, kcpEdges));
        }

        // Cross-workspace links section — one line per unique src target (shortest docs path)
        Map<String, String> srcTargetToDocsPath = new java.util.TreeMap<>();
        for (KnowledgeEdge edge : edges) {
            if (!"src".equals(edge.relationship())) continue;
            String target = edge.directoryPath();
            String current = srcTargetToDocsPath.get(target);
            if (current == null || edge.filePath().length() < current.length()) {
                srcTargetToDocsPath.put(target, edge.filePath());
            }
        }
        if (!srcTargetToDocsPath.isEmpty()) {
            sb.append("\nCross-workspace links:\n");
            sb.append("-".repeat(40)).append("\n");
            srcTargetToDocsPath.forEach((target, docsPath) ->
                    sb.append(String.format("  %-30s ──[src]──> %s%n",
                            docsPath + "/", target)));
        }

        return sb.toString();
    }

    /**
     * Renders a per-top-level-directory tightness breakdown table.
     * Groups nodes by their first path segment (immediate child of workspace root),
     * then computes internal edge count and tightness for each group.
     */
    private String renderSubworkspaceBreakdown(List<KnowledgeNode> nodes,
                                                List<KnowledgeEdge> edges) {
        // Group nodes by first path segment
        Map<String, List<KnowledgeNode>> byTopLevel = new java.util.TreeMap<>();
        for (KnowledgeNode node : nodes) {
            String topLevel = node.path().contains("/")
                    ? node.path().substring(0, node.path().indexOf('/'))
                    : node.path();
            byTopLevel.computeIfAbsent(topLevel, k -> new ArrayList<>()).add(node);
        }

        if (byTopLevel.size() < 2) {
            // Only one top-level dir — not interesting to show breakdown
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nSub-workspace tightness:\n");
        sb.append("-".repeat(60)).append("\n");

        for (Map.Entry<String, List<KnowledgeNode>> entry : byTopLevel.entrySet()) {
            String dir = entry.getKey();
            List<KnowledgeNode> dirNodes = entry.getValue();
            Set<String> dirPaths = new HashSet<>();
            for (KnowledgeNode n : dirNodes) {
                dirPaths.add(n.path());
            }

            // Count internal edges for this dir
            long internalEdges = 0;
            for (KnowledgeEdge edge : edges) {
                String srcDir = edge.filePath().contains("/")
                        ? edge.filePath().substring(0, edge.filePath().lastIndexOf('/'))
                        : edge.filePath();
                boolean srcIn = dirPaths.contains(srcDir) || dirPaths.contains(edge.filePath());
                boolean tgtIn = dirPaths.contains(edge.directoryPath());
                if (srcIn && tgtIn) internalEdges++;
            }

            int n = dirNodes.size();
            double tightness = (n < 2) ? 0.0
                    : (double) internalEdges / ((double) n * (n - 1) / 2.0);

            sb.append(String.format("  %-20s %4d dirs   %5d internal   %.2f%n",
                    dir + "/", n, internalEdges, tightness));
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

    /** Backward-compat overload (no KCP data). Used by tests. */
    String renderMermaid(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges, Path workspaceRoot) {
        return renderMermaid(nodes, edges, List.of(), List.of(), workspaceRoot);
    }

    String renderMermaid(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges,
                          List<KcpUnitNode> kcpUnits, List<KcpUnitEdge> kcpEdges,
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

        // External src nodes (stadium shape) for cross-workspace edges (Issue #281)
        for (KnowledgeEdge edge : edges) {
            if (!"src".equals(edge.relationship())) continue;
            String srcPath = edge.directoryPath();
            if (nodeIds.containsKey(srcPath)) continue; // already present
            String extId = "ext" + (idCounter++);
            nodeIds.put(srcPath, extId);
            String label = srcPath.contains("/")
                    ? srcPath.substring(srcPath.lastIndexOf('/') + 1)
                    : srcPath;
            sb.append("    ").append(extId).append("([").append(label).append("])\n");
        }

        // Edges: virtual memberships + cross-workspace src edges
        for (KnowledgeEdge edge : edges) {
            if ("src".equals(edge.relationship())) {
                // Cross-workspace edge: docs dir → external src node, double arrow
                String sourceId = nodeIds.get(edge.filePath());
                String targetId = nodeIds.get(edge.directoryPath());
                if (sourceId != null && targetId != null) {
                    sb.append(String.format("    %s ==>|src| %s%n", sourceId, targetId));
                }
                continue;
            }
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

        // KCP unit nodes — rounded pill shape, prefixed with project name
        int kcpCounter = 0;
        Map<String, String> kcpNodeIds = new HashMap<>();
        for (KcpUnitNode unit : kcpUnits) {
            String id = "kcp" + (kcpCounter++);
            String key = unit.manifestFile() + "::" + unit.unitId();
            kcpNodeIds.put(key, id);

            String intent = unit.intent() != null ? unit.intent() : unit.unitId();
            if (intent.length() > 40) intent = intent.substring(0, 40) + "…";
            String label = unit.project() + "/" + unit.unitId() + "\\n" + intent;
            sb.append("    ").append(id).append("(\"").append(label).append("\")\n");
        }

        // KCP unit → directory edges (unit points to its file's parent dir)
        for (KcpUnitNode unit : kcpUnits) {
            if (unit.path() == null) continue;
            String fileDir = unit.path().contains("/")
                    ? unit.path().substring(0, unit.path().lastIndexOf('/'))
                    : "";
            String dirId = nodeIds.get(fileDir);
            String unitId = kcpNodeIds.get(unit.manifestFile() + "::" + unit.unitId());
            if (dirId != null && unitId != null) {
                sb.append(String.format("    %s -.->|kcp-unit| %s%n", unitId, dirId));
            }
        }

        // KCP unit → unit relationship edges
        for (KcpUnitEdge rel : kcpEdges) {
            String fromId = kcpNodeIds.get(rel.manifestFile() + "::" + rel.fromUnit());
            String toId   = kcpNodeIds.get(rel.manifestFile() + "::" + rel.toUnit());
            if (fromId != null && toId != null) {
                String label = rel.type() != null ? rel.type() : "related";
                sb.append(String.format("    %s -->|%s| %s%n", fromId, label, toId));
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    /** Backward-compat overload (no KCP data). Used by tests. */
    String renderJson(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges, Path workspaceRoot) {
        return renderJson(nodes, edges, List.of(), List.of(), workspaceRoot);
    }

    String renderJson(List<KnowledgeNode> nodes, List<KnowledgeEdge> edges,
                       List<KcpUnitNode> kcpUnits, List<KcpUnitEdge> kcpEdges,
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
        sb.append("  ],\n");

        // KCP units
        sb.append("  \"kcpUnits\": [\n");
        for (int i = 0; i < kcpUnits.size(); i++) {
            KcpUnitNode unit = kcpUnits.get(i);
            sb.append("    {\n");
            sb.append("      \"unitId\": \"").append(escapeJson(unit.unitId())).append("\",\n");
            sb.append("      \"project\": \"").append(escapeJson(unit.project())).append("\",\n");
            sb.append("      \"manifestFile\": \"").append(escapeJson(unit.manifestFile())).append("\",\n");
            sb.append("      \"path\": \"").append(escapeJson(unit.path() != null ? unit.path() : "")).append("\",\n");
            sb.append("      \"intent\": \"").append(escapeJson(unit.intent() != null ? unit.intent() : "")).append("\",\n");
            sb.append("      \"scope\": \"").append(escapeJson(unit.scope() != null ? unit.scope() : "")).append("\",\n");
            sb.append("      \"triggers\": ").append(jsonArray(unit.triggers())).append("\n");
            sb.append("    }");
            if (i < kcpUnits.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // KCP unit relationships
        sb.append("  \"kcpRelationships\": [\n");
        for (int i = 0; i < kcpEdges.size(); i++) {
            KcpUnitEdge rel = kcpEdges.get(i);
            sb.append("    {\n");
            sb.append("      \"from\": \"").append(escapeJson(rel.fromUnit())).append("\",\n");
            sb.append("      \"to\": \"").append(escapeJson(rel.toUnit())).append("\",\n");
            sb.append("      \"type\": \"").append(escapeJson(rel.type() != null ? rel.type() : "")).append("\",\n");
            sb.append("      \"manifestFile\": \"").append(escapeJson(rel.manifestFile())).append("\"\n");
            sb.append("    }");
            if (i < kcpEdges.size() - 1) sb.append(",");
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

    /**
     * Collects KCP knowledge unit nodes from the database for this workspace.
     * Returns an empty list if the database does not exist or tables are absent.
     */
    List<KcpUnitNode> collectKcpUnits(Path workspaceRoot) {
        List<KcpUnitNode> units = new ArrayList<>();
        Path dbPath = workspaceRoot.resolve(".synthesis/synthesis.db");
        if (!Files.exists(dbPath)) return units;

        String normalizedScope = (scope != null && !scope.isBlank())
                ? (scope.endsWith("/") ? scope.substring(0, scope.length() - 1) : scope)
                : null;

        try (SynthesisDatabase db = new SynthesisDatabase(dbPath)) {
            java.sql.Connection conn = db.getConnection();
            String sql = "SELECT u.unit_id, u.manifest_file, m.project, u.path, "
                    + "u.intent, u.scope, u.triggers_json "
                    + "FROM kcp_units u "
                    + "JOIN kcp_manifests m ON m.workspace_path = u.workspace_path "
                    + "  AND m.file_path = u.manifest_file "
                    + "WHERE u.workspace_path = ? "
                    + "ORDER BY m.project, u.unit_id";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspaceRoot.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (normalizedScope != null) {
                            String manifestFile = rs.getString("manifest_file");
                            String scopePrefix = workspaceRoot + "/" + normalizedScope + "/";
                            if (!manifestFile.startsWith(scopePrefix)) continue;
                        }
                        units.add(new KcpUnitNode(
                                rs.getString("unit_id"),
                                rs.getString("manifest_file"),
                                rs.getString("project") != null ? rs.getString("project") : "?",
                                rs.getString("path"),
                                rs.getString("intent"),
                                rs.getString("scope"),
                                parseTriggers(rs.getString("triggers_json"))));
                    }
                }
            }
        } catch (SQLException e) {
            // Return empty list silently (table may not exist yet)
        }

        return units;
    }

    /**
     * Collects KCP unit-to-unit relationship edges from the database.
     * Returns an empty list if the database does not exist or tables are absent.
     */
    List<KcpUnitEdge> collectKcpRelEdges(Path workspaceRoot) {
        List<KcpUnitEdge> rels = new ArrayList<>();
        Path dbPath = workspaceRoot.resolve(".synthesis/synthesis.db");
        if (!Files.exists(dbPath)) return rels;

        String normalizedScope = (scope != null && !scope.isBlank())
                ? (scope.endsWith("/") ? scope.substring(0, scope.length() - 1) : scope)
                : null;

        try (SynthesisDatabase db = new SynthesisDatabase(dbPath)) {
            java.sql.Connection conn = db.getConnection();
            String sql = "SELECT r.from_unit, r.to_unit, r.type, r.manifest_file "
                    + "FROM kcp_relationships r "
                    + "WHERE r.workspace_path = ? "
                    + "ORDER BY r.manifest_file, r.from_unit";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspaceRoot.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (normalizedScope != null) {
                            String manifestFile = rs.getString("manifest_file");
                            String scopePrefix = workspaceRoot + "/" + normalizedScope + "/";
                            if (!manifestFile.startsWith(scopePrefix)) continue;
                        }
                        rels.add(new KcpUnitEdge(
                                rs.getString("from_unit"),
                                rs.getString("to_unit"),
                                rs.getString("type"),
                                rs.getString("manifest_file")));
                    }
                }
            }
        } catch (SQLException e) {
            // Return empty list silently
        }

        return rels;
    }

    /**
     * Renders a text section listing KCP knowledge units and their relationships.
     * Units are grouped by project name.
     */
    private String renderKcpAsciiSection(List<KcpUnitNode> units, List<KcpUnitEdge> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nKCP Knowledge Units:\n");
        sb.append("-".repeat(40)).append("\n");

        // Group by project
        Map<String, List<KcpUnitNode>> byProject = new java.util.LinkedHashMap<>();
        for (KcpUnitNode unit : units) {
            byProject.computeIfAbsent(unit.project(), k -> new ArrayList<>()).add(unit);
        }

        for (Map.Entry<String, List<KcpUnitNode>> entry : byProject.entrySet()) {
            sb.append("  [").append(entry.getKey()).append("]\n");
            for (KcpUnitNode unit : entry.getValue()) {
                String intent = unit.intent() != null ? unit.intent() : "(no intent)";
                if (intent.length() > 60) intent = intent.substring(0, 60) + "\u2026";
                sb.append(String.format("    \u2022 %s: %s%n", unit.unitId(), intent));
                if (unit.path() != null) {
                    sb.append(String.format("      \u2192 %s  [scope: %s]%n",
                            unit.path(), unit.scope() != null ? unit.scope() : "?"));
                }
                if (!unit.triggers().isEmpty()) {
                    sb.append(String.format("      triggers: %s%n",
                            String.join(", ", unit.triggers())));
                }
            }
        }

        if (!edges.isEmpty()) {
            sb.append("\n  Relationships:\n");
            for (KcpUnitEdge edge : edges) {
                String typeLabel = edge.type() != null ? "[" + edge.type() + "]" : "";
                sb.append(String.format("    %s --%s--> %s%n",
                        edge.fromUnit(), typeLabel, edge.toUnit()));
            }
        }

        return sb.toString();
    }

    /** Parses a simple JSON string array like {@code ["a","b","c"]} to a List. */
    static List<String> parseTriggers(String json) {
        if (json == null || json.isBlank()) return List.of();
        String stripped = json.strip();
        if ("[]".equals(stripped)) return List.of();
        stripped = stripped.replaceFirst("^\\[", "").replaceFirst("]$", "");
        List<String> result = new ArrayList<>();
        for (String item : stripped.split(",")) {
            String s = item.strip().replaceAll("^\"|\"$", "").strip();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
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

    void setScope(String scope) {
        this.scope = scope;
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

    record KcpUnitNode(
            String unitId,
            String manifestFile,
            String project,
            String path,
            String intent,
            String scope,
            List<String> triggers
    ) {}

    record KcpUnitEdge(
            String fromUnit,
            String toUnit,
            String type,
            String manifestFile
    ) {}
}
