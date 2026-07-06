package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.GraphBuilder;
import io.exoreaction.synthesis.graph.GraphBuilder.FileGraph;
import io.exoreaction.synthesis.graph.GraphBuilder.GraphEdge;
import io.exoreaction.synthesis.graph.GraphBuilder.GraphNode;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.*;

/**
 * Generates an interactive Cytoscape.js graph of Claude Code skills.
 *
 * <p>Parses all {@code *.yaml} skill files in {@code ~/.claude/skills/},
 * extracts tags, trigger phrases, and cross-references, then emits a
 * self-contained HTML file with:
 * <ul>
 *   <li>Compound (collapsible) cluster nodes per primary tag</li>
 *   <li>Skill nodes sized by instruction length</li>
 *   <li>Edges: explicit {@code related_skills} links + instruction-text mentions</li>
 *   <li>Detail panel on click (description, triggers, connected skills)</li>
 *   <li>Search / tag filter</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   synthesis skills-graph                          # generate + open in browser
 *   synthesis skills-graph --output skills.html     # write to specific file
 *   synthesis skills-graph --format json            # JSON data only
 *   synthesis skills-graph --filter mynder          # only show mynder skills
 *   synthesis skills-graph --no-open                # generate without opening
 * </pre>
 */
@Command(
        name = "skills-graph",
        description = {"Generate interactive graph of Claude Code skills (default).",
                "--mode workspace/modules generates a code-dependency graph instead, unrelated to skills."},
        mixinStandardHelpOptions = true
)
public class SkillsGraphCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--skills-dir"},
            description = "Skills directory (default: ~/.claude/skills/)")
    private Path skillsDir;

    @Option(names = {"--format", "-f"},
            description = "Output format: html, json (default: html)",
            defaultValue = "html")
    private String format;

    @Option(names = {"--output", "-o"},
            description = "Output file path (default: temp file, auto-opened)")
    private Path output;

    @Option(names = {"--open"}, negatable = true,
            description = "Open in browser after generating (default: true for html)",
            defaultValue = "true")
    private boolean open;

    @Option(names = {"--filter"},
            description = "Only include skills matching this tag")
    private String filterTag;

    @Option(names = {"--mode", "-m"},
            description = "Graph mode: skills (default), workspace (cross-repo), modules (directory-level)",
            defaultValue = "skills")
    private String mode;

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    record SkillNode(
            String id,
            String version,
            String description,
            List<String> tags,
            List<String> triggerPhrases,
            List<String> relatedSkills,
            String filePath,
            String instructions,
            int instructionLines,
            String cluster
    ) {}

    record SkillEdge(
            String source,
            String target,
            String type,   // explicit | mention
            int weight
    ) {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public Integer call() throws Exception {
        String m = mode.toLowerCase().strip();
        if ("workspace".equals(m) || "modules".equals(m)) {
            return callWorkspace("modules".equals(m));
        }
        return callSkills();
    }

    private Integer callSkills() throws Exception {
        Path dir = skillsDir != null
                ? skillsDir.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), ".claude", "skills");

        if (!Files.isDirectory(dir)) {
            AnsiOutput.printError("Skills directory not found: " + dir);
            return 1;
        }

        AnsiOutput.printInfo("Parsing skills from " + dir + " ...");
        List<SkillNode> nodes = parseSkills(dir);

        // Post-process: merge singleton clusters into cluster-misc
        // (clusters with only 1 skill AND no explicit related_skills pointing within the cluster)
        Map<String, Long> clusterCounts = nodes.stream()
                .collect(Collectors.groupingBy(SkillNode::cluster, Collectors.counting()));
        nodes = nodes.stream().map(n -> {
            if (clusterCounts.getOrDefault(n.cluster(), 0L) == 1L) {
                return new SkillNode(n.id(), n.version(), n.description(), n.tags(),
                    n.triggerPhrases(), n.relatedSkills(), n.filePath(),
                    n.instructions(), n.instructionLines(), "cluster-misc");
            }
            return n;
        }).collect(Collectors.toList());

        if (nodes.isEmpty()) {
            AnsiOutput.printError("No skill YAML files found in " + dir);
            return 1;
        }

        if (filterTag != null && !filterTag.isBlank()) {
            String ft = filterTag.toLowerCase().strip();
            nodes = nodes.stream()
                    .filter(n -> n.tags().stream().anyMatch(t -> t.equalsIgnoreCase(ft)))
                    .collect(Collectors.toList());
            if (nodes.isEmpty()) {
                AnsiOutput.printError("No skills found with tag: " + filterTag);
                return 1;
            }
        }

        AnsiOutput.printInfo("Building edges for " + nodes.size() + " skills ...");
        List<SkillEdge> edges = buildEdges(nodes);

        String fmt = format.toLowerCase().strip();
        String content = "json".equals(fmt) ? toJson(nodes, edges) : toHtml(nodes, edges, dir.toString());

        Path outPath = resolveOutput(fmt);
        Files.writeString(outPath, content);

        AnsiOutput.printSuccess("Generated " + outPath
                + "  (" + nodes.size() + " skills, " + edges.size() + " edges)");

        if (open && "html".equals(fmt)) {
            openInBrowser(outPath);
        } else if (!"html".equals(fmt)) {
            System.out.println(outPath);
        }

        return 0;
    }

    private Integer callWorkspace(boolean moduleMode) throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();
        WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
        var validation = workspace.validate();
        if (validation.isPresent()) {
            AnsiOutput.printError(validation.get());
            return 1;
        }

        AnsiOutput.printInfo("Loading workspace index from " + workspace.getIndexPath() + " ...");
        List<SearchResult> allFiles;
        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
            allFiles = index.listAll(null, 50000);
        }

        if (allFiles.isEmpty()) {
            AnsiOutput.printError("No files in index. Run 'synthesis scan' first.");
            return 1;
        }

        AnsiOutput.printInfo("Building " + (moduleMode ? "module" : "cross-repo") + " graph for "
                + allFiles.size() + " files ...");

        GraphBuilder builder = new GraphBuilder();
        FileGraph fileGraph = moduleMode
                ? builder.buildModuleGraph(allFiles)
                : builder.buildCrossRepoGraph(allFiles);

        List<SkillNode> nodes = fileGraphToSkillNodes(fileGraph);
        List<SkillEdge> edges = fileGraphToSkillEdges(fileGraph, nodes);

        String fmt = format.toLowerCase().strip();
        String subtitle = workspaceRoot.toString();
        String content = "json".equals(fmt) ? toJson(nodes, edges) : toHtml(nodes, edges, subtitle);

        Path outPath = resolveOutput(fmt);
        Files.writeString(outPath, content);

        AnsiOutput.printSuccess("Generated " + outPath
                + "  (" + nodes.size() + " nodes, " + edges.size() + " edges)");

        if (open && "html".equals(fmt)) {
            openInBrowser(outPath);
        } else if (!"html".equals(fmt)) {
            System.out.println(outPath);
        }

        return 0;
    }

    /** Maps a FileGraph's nodes to SkillNode records for HTML generation. */
    private List<SkillNode> fileGraphToSkillNodes(FileGraph fileGraph) {
        return fileGraph.nodes().stream().map(gn -> {
            String cluster = "cluster-" + (gn.repository() != null
                    ? gn.repository().toLowerCase().replaceAll("[^a-z0-9]", "-")
                    : gn.directory() != null
                        ? gn.directory().toLowerCase().replaceAll("[^a-z0-9]", "-")
                        : "other");
            int sizeLine = (int) Math.max(1, Math.min(500, gn.sizeBytes() / 1024));
            List<String> tags = gn.fileType() != null ? List.of(gn.fileType().toLowerCase()) : List.of();
            return new SkillNode(
                    gn.id(),
                    "?",
                    gn.label(),
                    tags,
                    List.of(),
                    List.of(),
                    gn.directory() != null ? gn.directory() : gn.id(),
                    "",
                    sizeLine,
                    cluster
            );
        }).collect(Collectors.toList());
    }

    /** Maps a FileGraph's edges to SkillEdge records. */
    private List<SkillEdge> fileGraphToSkillEdges(FileGraph fileGraph, List<SkillNode> nodes) {
        Set<String> nodeIds = nodes.stream().map(SkillNode::id).collect(Collectors.toSet());
        return fileGraph.edges().stream()
                .filter(e -> nodeIds.contains(e.sourceId()) && nodeIds.contains(e.targetId()))
                .map(e -> new SkillEdge(e.sourceId(), e.targetId(), "explicit", Math.min(e.weight(), 5)))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // YAML parsing
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<SkillNode> parseSkills(Path dir) {
        List<SkillNode> result = new ArrayList<>();
        Yaml yaml = new Yaml();

        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> files = paths
                    .filter(p -> {
                        String s = p.toString();
                        return s.endsWith(".yaml") || s.endsWith(".yml");
                    })
                    .sorted()
                    .collect(Collectors.toList());

            for (Path file : files) {
                try (InputStream is = Files.newInputStream(file)) {
                    Object raw = yaml.load(is);
                    if (!(raw instanceof Map<?, ?> m)) continue;
                    Map<String, Object> data = (Map<String, Object>) m;

                    String id = str(data, "name");
                    if (id == null || id.isBlank()) {
                        id = file.getFileName().toString().replaceAll("\\.ya?ml$", "");
                    }

                    String description = str(data, "description");
                    if (description != null && description.contains("\n")) {
                        description = description.lines().findFirst().orElse(description).strip();
                    }
                    if (description == null) description = id;

                    String instructions = str(data, "instructions");
                    int instrLines = instructions != null ? (int) instructions.lines().count() : 0;

                    List<String> tags = strList(data, "tags");
                    List<String> triggers = strList(data, "trigger_phrases");
                    List<String> related = strList(data, "related_skills");

                    result.add(new SkillNode(
                            id,
                            coalesce(str(data, "version"), "?"),
                            description,
                            tags,
                            triggers,
                            related,
                            file.toAbsolutePath().toString(),
                            instructions != null ? instructions : "",
                            instrLines,
                            primaryCluster(tags, id)
                    ));
                } catch (Exception ignored) {
                    // skip malformed files
                }
            }
        } catch (IOException e) {
            AnsiOutput.printWarning("Error listing skills dir: " + e.getMessage());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Edge building
    // -------------------------------------------------------------------------

    private List<SkillEdge> buildEdges(List<SkillNode> nodes) {
        Set<String> nodeIds = nodes.stream().map(SkillNode::id).collect(Collectors.toSet());
        Map<String, SkillEdge> edgeMap = new LinkedHashMap<>();

        for (SkillNode node : nodes) {
            // 1. Explicit related_skills (weight 3)
            for (String rel : node.relatedSkills()) {
                if (nodeIds.contains(rel) && !rel.equals(node.id())) {
                    addEdge(edgeMap, node.id(), rel, "explicit", 3);
                }
            }

            // 2. Instruction text mentions of other skill IDs (weight 2)
            if (!node.instructions().isBlank()) {
                for (String otherId : nodeIds) {
                    if (!otherId.equals(node.id()) && containsWordBoundary(node.instructions(), otherId)) {
                        addEdge(edgeMap, node.id(), otherId, "mention", 2);
                    }
                }
            }
        }

        return new ArrayList<>(edgeMap.values());
    }

    private void addEdge(Map<String, SkillEdge> edgeMap,
                         String src, String tgt, String type, int weight) {
        String key;
        if ("explicit".equals(type)) {
            key = src + "||" + tgt + "||explicit";
        } else {
            key = (src.compareTo(tgt) < 0 ? src + "||" + tgt : tgt + "||" + src) + "||mention";
        }
        SkillEdge existing = edgeMap.get(key);
        if (existing == null || existing.weight() < weight) {
            edgeMap.put(key, new SkillEdge(src, tgt, type, weight));
        }
    }

    private static boolean containsWordBoundary(String text, String word) {
        if (text == null || word == null || word.isBlank()) return false;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?<![a-zA-Z0-9_-])" + java.util.regex.Pattern.quote(word) + "(?![a-zA-Z0-9_-])");
            return p.matcher(text).find();
        } catch (Exception e) {
            return text.contains(word);
        }
    }

    // -------------------------------------------------------------------------
    // Cluster logic
    // -------------------------------------------------------------------------

    private static final Set<String> GENERIC_TAGS = Set.of(
            "layer-1", "layer-2", "layer-3", "layer-4", "layer-5",
            "java", "context", "reference", "general"
    );

    private static String primaryCluster(List<String> tags, String skillId) {
        // Pass 1: use first non-generic tag (existing logic)
        if (tags != null && !tags.isEmpty()) {
            for (String tag : tags) {
                String t = tag.toLowerCase().strip();
                if (!GENERIC_TAGS.contains(t)) {
                    return "cluster-" + t.replaceAll("[^a-z0-9]", "-");
                }
            }
            // All tags generic — use last tag (most specific)
            String last = tags.get(tags.size() - 1).toLowerCase().strip();
            return "cluster-" + last.replaceAll("[^a-z0-9]", "-");
        }

        // Pass 2: extract prefix from skill ID filename
        if (skillId != null && !skillId.isBlank()) {
            String[] parts = skillId.toLowerCase().split("-");
            if (parts.length >= 1) {
                String first = parts[0];
                // Common action verbs -> dev-actions cluster
                Set<String> ACTION_VERBS = Set.of("add", "fix", "integrate", "migrate", "verify",
                    "prepare", "resolve", "generate", "locate", "modernize", "create",
                    "update", "build", "run", "check", "setup", "configure");
                if (ACTION_VERBS.contains(first)) {
                    return "cluster-dev-actions";
                }
                // Two-segment prefix for known namespaces
                if (parts.length >= 2 && Set.of("jenkins", "expert", "kcp", "company").contains(first)) {
                    return "cluster-" + first + "-" + parts[1];
                }
                return "cluster-" + first;
            }
        }

        return "cluster-other";
    }

    // -------------------------------------------------------------------------
    // JSON output
    // -------------------------------------------------------------------------

    private String toJson(List<SkillNode> nodes, List<SkillEdge> edges) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"meta\": { \"totalSkills\": ").append(nodes.size())
          .append(", \"totalEdges\": ").append(edges.size())
          .append(", \"generated\": ").append(jsonStr(ts)).append(" },\n");
        sb.append("  \"nodes\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            SkillNode n = nodes.get(i);
            sb.append("    { \"id\": ").append(jsonStr(n.id()))
              .append(", \"version\": ").append(jsonStr(n.version()))
              .append(", \"description\": ").append(jsonStr(n.description()))
              .append(", \"tags\": ").append(jsonList(n.tags()))
              .append(", \"triggerPhrases\": ").append(jsonList(n.triggerPhrases()))
              .append(", \"instructionLines\": ").append(n.instructionLines())
              .append(", \"cluster\": ").append(jsonStr(n.cluster()))
              .append(" }");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n  \"edges\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            SkillEdge e = edges.get(i);
            sb.append("    { \"source\": ").append(jsonStr(e.source()))
              .append(", \"target\": ").append(jsonStr(e.target()))
              .append(", \"type\": ").append(jsonStr(e.type()))
              .append(", \"weight\": ").append(e.weight()).append(" }");
            if (i < edges.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // HTML output
    // -------------------------------------------------------------------------

    private String toHtml(List<SkillNode> nodes, List<SkillEdge> edges, String subtitle) {
        // Build Cytoscape elements array (flat — no compound parent nodes)
        StringBuilder elems = new StringBuilder();
        elems.append("[\n");

        // Skill nodes
        for (SkillNode n : nodes) {
            int w = Math.max(14, Math.min(52, n.instructionLines() / 6));
            elems.append("  {\"data\":{\"id\":").append(jsonStr(n.id()))
                 .append(",\"label\":").append(jsonStr(n.id()))
                 .append(",\"cluster\":").append(jsonStr(n.cluster()))
                 .append(",\"version\":").append(jsonStr(n.version()))
                 .append(",\"description\":").append(jsonStr(n.description()))
                 .append(",\"tags\":").append(jsonStr(String.join(", ", n.tags())))
                 .append(",\"triggers\":").append(jsonStr(
                         n.triggerPhrases().stream().limit(5).collect(Collectors.joining(", "))))
                 .append(",\"filePath\":").append(jsonStr(n.filePath()))
                 .append(",\"lines\":").append(n.instructionLines())
                 .append(",\"w\":").append(w)
                 .append("}},\n");
        }

        // Cluster label nodes (positioned at cluster centroids by JS layout)
        Set<String> seenClusters = new LinkedHashSet<>();
        for (SkillNode n : nodes) seenClusters.add(n.cluster());
        for (String cluster : seenClusters) {
            String displayName = cluster.startsWith("cluster-") ? cluster.substring(8) : cluster;
            elems.append("  {\"data\":{\"id\":").append(jsonStr("clabel-" + cluster))
                 .append(",\"label\":").append(jsonStr(displayName))
                 .append(",\"type\":\"cluster-label\"")
                 .append(",\"cluster\":").append(jsonStr(cluster))
                 .append("}},\n");
        }

        // Edges — only render explicit edges (mention edges are too noisy at ~570+)
        // Mention edges are still computed and available in the JSON output
        for (SkillEdge e : edges) {
            if (!"explicit".equals(e.type())) continue;
            elems.append("  {\"data\":{\"source\":").append(jsonStr(e.source()))
                 .append(",\"target\":").append(jsonStr(e.target()))
                 .append(",\"type\":").append(jsonStr(e.type()))
                 .append(",\"weight\":").append(e.weight())
                 .append("}},\n");
        }

        // Remove trailing comma+newline before closing bracket
        String elemsStr = elems.toString();
        int lastComma = elemsStr.lastIndexOf(",\n");
        if (lastComma >= 0) {
            elemsStr = elemsStr.substring(0, lastComma) + "\n" + elemsStr.substring(lastComma + 2);
        }
        elemsStr += "]";

        String generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        StringBuilder html = new StringBuilder(32 * 1024);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        String graphTitle = "workspace".equals(mode) ? "ExoCortex Workspace Graph"
                : "modules".equals(mode) ? "ExoCortex Modules Graph"
                : "ExoCortex Skills Graph";
        html.append("<title>").append(graphTitle).append("</title>\n");
        html.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/cytoscape/3.30.2/cytoscape.min.js\"></script>\n");
        html.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/cytoscape-fcose/2.2.0/cytoscape-fcose.min.js\"></script>\n");
        html.append("<style>\n");
        html.append("*{box-sizing:border-box;margin:0;padding:0}\n");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;");
        html.append("background:#0d1117;color:#e6edf3;display:flex;flex-direction:column;height:100vh;overflow:hidden}\n");
        html.append("#toolbar{background:#161b22;border-bottom:1px solid #30363d;padding:8px 14px;");
        html.append("display:flex;align-items:center;gap:10px;flex-shrink:0;flex-wrap:wrap}\n");
        html.append("#toolbar h1{font-size:13px;font-weight:600;color:#58a6ff;white-space:nowrap}\n");
        html.append("#search{background:#21262d;border:1px solid #30363d;border-radius:6px;");
        html.append("color:#e6edf3;padding:5px 10px;font-size:12px;width:220px}\n");
        html.append("#search:focus{outline:none;border-color:#58a6ff}\n");
        html.append("#search::placeholder{color:#8b949e}\n");
        html.append(".tag-btn{background:#21262d;border:1px solid #30363d;border-radius:12px;");
        html.append("color:#8b949e;padding:3px 9px;font-size:11px;cursor:pointer;transition:all .15s}\n");
        html.append(".tag-btn:hover,.tag-btn.active{background:#388bfd26;border-color:#58a6ff;color:#58a6ff}\n");
        html.append(".tag-btn small{opacity:.7}\n");
        html.append("#reset-btn{margin-left:auto;background:#21262d;border:1px solid #30363d;");
        html.append("border-radius:6px;color:#8b949e;padding:5px 11px;font-size:11px;cursor:pointer}\n");
        html.append("#reset-btn:hover{border-color:#8b949e;color:#e6edf3}\n");
        html.append("#edge-toggle{background:#21262d;border:1px solid #30363d;border-radius:6px;");
        html.append("color:#8b949e;padding:5px 11px;font-size:11px;cursor:pointer}\n");
        html.append("#edge-toggle:hover,#edge-toggle.active{border-color:#3fb950;color:#3fb950}\n");
        html.append("#stats{font-size:11px;color:#8b949e;white-space:nowrap}\n");
        html.append("#main{display:flex;flex:1;overflow:hidden}\n");
        html.append("#cy{flex:1;background:#0d1117;cursor:default}\n");
        html.append("#cy canvas{cursor:pointer}\n");
        html.append("#panel{width:320px;background:#161b22;border-left:1px solid #30363d;");
        html.append("overflow-y:auto;display:none;flex-shrink:0}\n");
        html.append("#panel.visible{display:block}\n");
        html.append("#panel-content{padding:14px}\n");
        html.append("#panel h2{font-size:12px;font-weight:600;color:#58a6ff;margin-bottom:10px;");
        html.append("font-family:monospace;word-break:break-all}\n");
        html.append(".pf{margin-bottom:9px}\n");
        html.append(".pl{font-size:10px;color:#8b949e;text-transform:uppercase;letter-spacing:.5px;margin-bottom:2px}\n");
        html.append(".pv{font-size:12px;color:#e6edf3;line-height:1.5}\n");
        html.append(".pv.mono{font-family:monospace;font-size:11px}\n");
        html.append(".chip{display:inline-block;background:#388bfd26;border:1px solid #388bfd4d;");
        html.append("border-radius:10px;color:#79c0ff;padding:2px 7px;font-size:10px;margin:2px;cursor:pointer}\n");
        html.append(".chip:hover{background:#388bfd40}\n");
        html.append(".rlink{display:block;color:#58a6ff;font-size:11px;font-family:monospace;");
        html.append("padding:2px 0;cursor:pointer}\n");
        html.append(".rlink:hover{text-decoration:underline}\n");
        html.append(".et-explicit{color:#3fb950;font-size:10px}\n");
        html.append(".et-mention{color:#d29922;font-size:10px}\n");
        html.append("#close-panel{float:right;background:none;border:none;color:#8b949e;font-size:15px;cursor:pointer;padding:0}\n");
        html.append("#close-panel:hover{color:#e6edf3}\n");
        html.append("#legend{position:absolute;bottom:12px;left:12px;background:#161b22cc;");
        html.append("border:1px solid #30363d;border-radius:6px;padding:8px 10px;font-size:10px}\n");
        html.append("#legend div{display:flex;align-items:center;gap:5px;margin-bottom:3px;color:#8b949e}\n");
        html.append(".ld{width:9px;height:9px;border-radius:50%}\n");
        html.append(".le{height:2px;width:18px}\n");
        // Tooltip
        html.append("#tooltip{position:fixed;pointer-events:none;background:#21262d;border:1px solid #30363d;\n");
        html.append("border-radius:6px;padding:8px 12px;font-size:11px;max-width:260px;z-index:100;\n");
        html.append("display:none;color:#e6edf3;line-height:1.5;box-shadow:0 4px 12px rgba(0,0,0,.4)}\n");
        html.append("#tooltip .tt-name{font-weight:600;color:#58a6ff;margin-bottom:3px;font-family:monospace}\n");
        html.append("#tooltip .tt-desc{color:#c9d1d9}\n");
        html.append("#tooltip .tt-tags{margin-top:4px;color:#8b949e;font-size:10px}\n");
        // Cluster nav sidebar
        html.append("#cluster-nav{width:190px;background:#161b22;border-right:1px solid #30363d;\n");
        html.append("display:flex;flex-direction:column;flex-shrink:0;overflow:hidden;transition:width .2s}\n");
        html.append("#cluster-nav.collapsed{width:32px}\n");
        html.append("#cluster-nav-header{display:flex;align-items:center;justify-content:space-between;\n");
        html.append("padding:8px 10px;border-bottom:1px solid #30363d;font-size:11px;font-weight:600;\n");
        html.append("color:#8b949e;flex-shrink:0;white-space:nowrap}\n");
        html.append("#cluster-nav.collapsed #cluster-nav-header span{display:none}\n");
        html.append("#nav-collapse{background:none;border:none;color:#8b949e;cursor:pointer;font-size:11px;padding:2px}\n");
        html.append("#nav-collapse:hover{color:#e6edf3}\n");
        html.append("#cluster-list{overflow-y:auto;flex:1;padding:4px 0}\n");
        html.append(".cluster-item{display:flex;align-items:center;gap:7px;padding:5px 10px;\n");
        html.append("cursor:pointer;transition:background .1s;font-size:11px}\n");
        html.append(".cluster-item:hover{background:#21262d}\n");
        html.append(".cluster-item.active{background:#388bfd1a;color:#58a6ff}\n");
        html.append(".cluster-dot{width:9px;height:9px;border-radius:50%;flex-shrink:0}\n");
        html.append(".cluster-label{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#c9d1d9}\n");
        html.append(".cluster-count{color:#8b949e;font-size:10px;flex-shrink:0}\n");
        html.append("#cluster-nav.collapsed .cluster-label,#cluster-nav.collapsed .cluster-count{display:none}\n");
        html.append("</style>\n</head>\n<body>\n");

        // Toolbar
        html.append("<div id=\"toolbar\">\n");
        html.append("  <h1>⚡ ").append(graphTitle).append("</h1>\n");
        String nodeLabel = "workspace".equals(mode) || "modules".equals(mode) ? "nodes" : "skills";
        html.append("  <input id=\"search\" type=\"text\" placeholder=\"Search ").append(nodeLabel).append("...\">\n");
        html.append("  <button id=\"reset-btn\" onclick=\"resetGraph()\">Reset</button>\n");
        html.append("  <button id=\"edge-toggle\" onclick=\"toggleEdges()\">Show edges</button>\n");
        html.append("  <span id=\"stats\">").append(nodes.size()).append(" ").append(nodeLabel).append(" · ")
            .append(edges.size()).append(" edges · ").append(generated).append("</span>\n");
        html.append("</div>\n");

        // Main area with cluster nav sidebar
        html.append("<div id=\"main\">\n");
        html.append("  <div id=\"cluster-nav\">\n");
        html.append("    <div id=\"cluster-nav-header\">\n");
        String navHeader = "modules".equals(mode) ? "Modules" : "workspace".equals(mode) ? "Repos" : "Clusters";
        html.append("      <span>").append(navHeader).append("</span>\n");
        html.append("      <button id=\"nav-collapse\" onclick=\"toggleNav()\">&#x25C0;</button>\n");
        html.append("    </div>\n");
        html.append("    <div id=\"cluster-list\"></div>\n");
        html.append("  </div>\n");
        html.append("  <div id=\"cy\"></div>\n");
        html.append("  <div id=\"panel\"><div id=\"panel-content\">");
        html.append("<button id=\"close-panel\" onclick=\"closePanel()\">&#x2715;</button>");
        html.append("<div id=\"panel-body\"></div></div></div>\n");
        html.append("</div>\n");

        // Legend
        html.append("<div id=\"legend\">\n");
        html.append("  <div><div class=\"ld\" style=\"background:#1f6feb\"></div> Skill (color = cluster)</div>\n");
        html.append("  <div><div class=\"le\" style=\"background:#3fb950\"></div> explicit link</div>\n");
        html.append("</div>\n");
        html.append("<div id=\"tooltip\"></div>\n");

        // Script
        html.append("<script>\n");
        html.append("const ELEMENTS = ").append(elemsStr).append(";\n\n");

        // A3: Cluster color functions (HSL palette)
        html.append("const CLUSTER_IDS = [...new Set(ELEMENTS.filter(e => e.data && e.data.cluster).map(e => e.data.cluster))];\n");
        html.append("function clusterHue(id) {\n");
        html.append("    const idx = CLUSTER_IDS.indexOf(id);\n");
        html.append("    return idx < 0 ? 210 : Math.round((idx * 360) / CLUSTER_IDS.length);\n");
        html.append("}\n");
        html.append("function clusterColor(id) { return `hsl(${clusterHue(id)},65%,40%)`; }\n");
        html.append("function clusterBorderColor(id) { return `hsl(${clusterHue(id)},65%,55%)`; }\n");
        html.append("function clusterBgColor(id) { return `hsla(${clusterHue(id)},50%,20%,0.3)`; }\n\n");

        html.append("const cy = cytoscape({\n");
        html.append("  container: document.getElementById('cy'),\n");
        html.append("  elements: ELEMENTS,\n");
        html.append("  userPanningEnabled: true,\n");
        html.append("  userZoomingEnabled: true,\n");
        html.append("  boxSelectionEnabled: false,\n");
        html.append("  autoungrabify: false,\n");
        // Scale font and node size based on graph density
        int nodeCount = nodes.size();
        String nodeFontSize = nodeCount < 30 ? "14px" : nodeCount < 100 ? "12px" : "10px";
        int nodeMin = nodeCount < 30 ? 28 : 22;
        int nodeMax = nodeCount < 30 ? 56 : 50;

        html.append("  style: [\n");
        html.append("    { selector: 'edge', style: { 'display': 'none' } },\n");
        html.append("    { selector: 'node', style: {\n");
        html.append("        'label': 'data(label)',\n");
        html.append("        'background-color': function(ele){ return clusterColor(ele.data('cluster')); },\n");
        html.append("        'border-color': function(ele){ return clusterBorderColor(ele.data('cluster')); },\n");
        html.append("        'border-width': 1.5,\n");
        html.append("        'color': '#e6edf3',\n");
        html.append("        'font-size': '").append(nodeFontSize).append("',\n");
        html.append("        'text-valign': 'bottom',\n");
        html.append("        'text-margin-y': 4,\n");
        html.append("        'text-outline-color': '#0d1117',\n");
        html.append("        'text-outline-width': 2,\n");
        html.append("        'width': 'mapData(w, 14, 52, ").append(nodeMin).append(", ").append(nodeMax).append(")',\n");
        html.append("        'height': 'mapData(w, 14, 52, ").append(nodeMin).append(", ").append(nodeMax).append(")'\n");
        html.append("    }},\n");
        html.append("    { selector: 'node:selected', style: {\n");
        html.append("        'border-color': '#58a6ff', 'border-width': 3, 'background-color': '#388bfd'\n");
        html.append("    }},\n");
        html.append("    { selector: 'edge[type=\"explicit\"]', style: {\n");
        html.append("        'line-color': '#3fb950', 'target-arrow-color': '#3fb950',\n");
        html.append("        'target-arrow-shape': 'triangle',\n");
        html.append("        'width': 'mapData(weight,1,3,1,3)', 'curve-style': 'bezier', 'opacity': 0.8\n");
        html.append("    }},\n");
        html.append("    { selector: 'edge[type=\"mention\"]', style: {\n");
        html.append("        'line-color': '#d29922', 'target-arrow-color': '#d29922',\n");
        html.append("        'target-arrow-shape': 'triangle', 'line-style': 'dashed',\n");
        html.append("        'width': 1.5, 'curve-style': 'bezier', 'opacity': 0.6\n");
        html.append("    }},\n");
        html.append("    { selector: '.highlighted', style: { 'background-color': '#f78166', 'border-color': '#ff7b72', 'border-width': 2 } },\n");
        html.append("    { selector: '.dimmed', style: { 'opacity': 0.12 } },\n");
        html.append("    { selector: '[type = \"cluster-label\"]', style: {\n");
        html.append("        'background-opacity': 0, 'border-width': 0, 'width': 5, 'height': 5,\n");
        html.append("        'label': 'data(label)', 'font-size': '15px', 'font-weight': '700',\n");
        html.append("        'text-valign': 'center', 'text-halign': 'center',\n");
        html.append("        'color': '#ffffff', 'text-outline-color': '#0d1117', 'text-outline-width': 3,\n");
        html.append("        'z-index': 999, 'events': 'no'\n");
        html.append("    }}\n");
        html.append("  ]\n");
        html.append("});\n\n");

        // A2: fcose layout with galaxy-preset fallback (run after constructor)
        // Galaxy layout: cluster centroids on phyllotaxis spiral, nodes in circles within each cluster
        html.append("function computeGalaxyPositions() {\n");
        html.append("    const map = {};\n");
        html.append("    cy.nodes('[type != \"cluster-label\"]').forEach(n => { const c = n.data('cluster')||'misc'; (map[c]||(map[c]=[])).push(n.id()); });\n");
        html.append("    const clusters = Object.entries(map).sort((a,b) => b[1].length - a[1].length);\n");
        html.append("    const nC = clusters.length;\n");
        html.append("    const W = cy.width()||900, H = cy.height()||600;\n");
        html.append("    const ox = W/2, oy = H/2;\n");
        html.append("    const maxR = Math.min(W,H) * 0.40;\n");
        html.append("    const positions = {};\n");
        html.append("    const golden = Math.PI * (3 - Math.sqrt(5));\n");
        html.append("    clusters.forEach(([cluster, ids], ci) => {\n");
        html.append("        const r = ci===0 ? 0 : maxR * Math.sqrt(ci / Math.max(nC-1,1)) * 0.95;\n");
        html.append("        const a0 = ci * golden;\n");
        html.append("        const ccx = ox + r * Math.cos(a0), ccy = oy + r * Math.sin(a0);\n");
        html.append("        const n = ids.length;\n");
        html.append("        const clR = Math.max(28, Math.sqrt(n) * 20);\n");
        html.append("        ids.forEach((id, j) => {\n");
        html.append("            const a = 2 * Math.PI * j / Math.max(n,1);\n");
        html.append("            const rMult = 0.55 + (j * 0.618034 % 1) * 0.7;\n");
        html.append("            positions[id] = { x: ccx + clR*rMult*Math.cos(a), y: ccy + clR*rMult*Math.sin(a) };\n");
        html.append("        });\n");
        html.append("        positions['clabel-' + cluster] = { x: ccx, y: ccy };\n");
        html.append("    });\n");
        html.append("    return positions;\n");
        html.append("}\n");
        html.append("function runLayout() {\n");
        html.append("    const useFcose = typeof cytoscapeFcose !== 'undefined';\n");
        html.append("    if (useFcose) {\n");
        html.append("        cy.layout({\n");
        html.append("            name: 'fcose', quality: 'default', animate: false, fit: true, padding: 50,\n");
        html.append("            nodeSeparation: 100,\n");
        html.append("            idealEdgeLength: function(e){ return e.data('type')==='explicit'?100:180; },\n");
        html.append("            nodeRepulsion: function(){ return 8000; },\n");
        html.append("            stop: function(){\n");
        html.append("                cy.nodes('[type = \"cluster-label\"]').forEach(ln => {\n");
        html.append("                    const members = cy.nodes('[cluster = \"' + ln.data('cluster') + '\"][type != \"cluster-label\"]');\n");
        html.append("                    if (!members.length) return;\n");
        html.append("                    const bb = members.boundingBox();\n");
        html.append("                    ln.position({ x: bb.x1 + bb.w/2, y: bb.y1 + bb.h/2 });\n");
        html.append("                });\n");
        html.append("                cy.fit(50);\n");
        html.append("            }\n");
        html.append("        }).run();\n");
        html.append("    } else {\n");
        html.append("        const positions = computeGalaxyPositions();\n");
        html.append("        cy.layout({ name: 'preset', positions: function(n){ return positions[n.id()]; },\n");
        html.append("            fit: true, padding: 50 }).run();\n");
        html.append("    }\n");
        html.append("}\n");
        html.append("runLayout();\n\n");

        // JS functions
        html.append("function showPanel(node) {\n");
        html.append("  const d = node.data();\n");
        html.append("  const edges = node.connectedEdges();\n");
        html.append("  let relHtml = '';\n");
        html.append("  edges.forEach(e => {\n");
        html.append("    const oid = e.source().id() === d.id ? e.target().id() : e.source().id();\n");
        html.append("    if (oid !== d.id) {\n");
        html.append("      relHtml += `<a class=\"rlink\" onclick=\"focusNode('${oid}')\">${oid} <span class=\"et-${e.data('type')}\">[${e.data('type')}]</span></a>`;\n");
        html.append("    }\n");
        html.append("  });\n");
        html.append("  const tagsHtml = (d.tags||'').split(',').map(t=>t.trim()).filter(Boolean)\n");
        html.append("    .map(t=>`<span class=\"chip\" onclick=\"filterByTag('${t}')\">${t}</span>`).join('');\n");
        html.append("  document.getElementById('panel-body').innerHTML = `\n");
        html.append("    <h2>${d.label}</h2>\n");
        html.append("    <div class=\"pf\"><div class=\"pl\">Description</div><div class=\"pv\">${d.description||'—'}</div></div>\n");
        html.append("    ${tagsHtml?`<div class=\"pf\"><div class=\"pl\">Tags</div><div class=\"pv\">${tagsHtml}</div></div>`:''}\n");
        html.append("    ${d.triggers?`<div class=\"pf\"><div class=\"pl\">Trigger phrases</div><div class=\"pv\" style=\"color:#8b949e;font-size:11px\">${d.triggers}</div></div>`:''}\n");
        html.append("    <div class=\"pf\"><div class=\"pl\">Version · Size</div><div class=\"pv\" style=\"color:#8b949e\">v${d.version} · ${d.lines} lines</div></div>\n");
        html.append("    ${relHtml?`<div class=\"pf\"><div class=\"pl\">Connected (${edges.length})</div>${relHtml}</div>`:`<div class=\"pf\"><div class=\"pl\">Connected</div><div style=\"color:#8b949e;font-size:11px\">No connections</div></div>`}\n");
        html.append("    <div class=\"pf\" style=\"margin-top:14px\"><div class=\"pl\">File</div><div class=\"pv mono\" style=\"color:#8b949e;word-break:break-all\">${d.filePath}</div></div>\n");
        html.append("  `;\n");
        html.append("  document.getElementById('panel').classList.add('visible');\n");
        html.append("}\n\n");

        html.append("function closePanel() {\n");
        html.append("  document.getElementById('panel').classList.remove('visible');\n");
        html.append("  cy.nodes().removeClass('highlighted dimmed');\n");
        html.append("  cy.edges().removeClass('dimmed');\n");
        html.append("  if (!edgesVisible) cy.edges().style('display', 'none');\n");
        html.append("}\n\n");

        html.append("function focusNode(id) {\n");
        html.append("  const node = cy.getElementById(id);\n");
        html.append("  if (!node || !node.length) return;\n");
        html.append("  cy.nodes().addClass('dimmed'); cy.edges().addClass('dimmed');\n");
        html.append("  node.removeClass('dimmed').addClass('highlighted');\n");
        html.append("  node.neighborhood().removeClass('dimmed');\n");
        html.append("  node.connectedEdges().removeClass('dimmed');\n");
        html.append("  cy.animate({ fit: { eles: node.neighborhood().add(node), padding: 60 } });\n");
        html.append("  showPanel(node);\n");
        html.append("}\n\n");

        // B4: Tab navigation state — must be declared before cy.on handlers
        html.append("let _tabNeighbors = [];\n");
        html.append("let _tabIdx = -1;\n\n");

        html.append("cy.on('tap', 'node', function(evt) {\n");
        html.append("  const node = evt.target;\n");
        html.append("  if (node.data('type') === 'cluster-label') return;\n");
        html.append("  cy.nodes().removeClass('highlighted dimmed'); cy.edges().removeClass('dimmed');\n");
        html.append("  node.addClass('highlighted');\n");
        html.append("  node.neighborhood('node').forEach(n => n.addClass('highlighted'));\n");
        html.append("  cy.nodes().not(node).not(node.neighborhood('node')).addClass('dimmed');\n");
        html.append("  node.connectedEdges().style('display', 'element').removeClass('dimmed');\n");
        html.append("  showPanel(node);\n");
        // B4: populate tab neighbors on tap
        html.append("  _tabNeighbors = [node.id(), ...node.neighborhood('node').map(n => n.id())];\n");
        html.append("  _tabIdx = 0;\n");
        html.append("});\n\n");

        html.append("cy.on('tap', function(evt) { if (evt.target === cy) closePanel(); });\n\n");

        // A4: Tooltip event handlers
        html.append("const tooltip = document.getElementById('tooltip');\n");
        html.append("cy.on('mouseover', 'node', function(evt) {\n");
        html.append("    const d = evt.target.data();\n");
        html.append("    const tagsStr = d.tags ? d.tags.split(',').map(t=>t.trim()).filter(Boolean).join(' \\u00B7 ') : '';\n");
        html.append("    tooltip.innerHTML = `<div class=\"tt-name\">${d.label}</div>\n");
        html.append("        <div class=\"tt-desc\">${d.description || '\\u2014'}</div>\n");
        html.append("        ${tagsStr ? `<div class=\"tt-tags\">${tagsStr}</div>` : ''}`;\n");
        html.append("    tooltip.style.display = 'block';\n");
        html.append("});\n");
        html.append("cy.on('mouseout', 'node', function() { tooltip.style.display = 'none'; });\n");
        html.append("cy.on('mousemove', function(evt) {\n");
        html.append("    if (tooltip.style.display !== 'none') {\n");
        html.append("        tooltip.style.left = (evt.originalEvent.clientX + 15) + 'px';\n");
        html.append("        tooltip.style.top = (evt.originalEvent.clientY - 10) + 'px';\n");
        html.append("    }\n");
        html.append("});\n\n");

        html.append("document.getElementById('search').addEventListener('input', function() {\n");
        html.append("  const q = this.value.toLowerCase().trim();\n");
        html.append("  if (!q) { cy.nodes().removeClass('highlighted dimmed'); cy.edges().removeClass('dimmed'); return; }\n");
        html.append("  cy.nodes().forEach(n => {\n");
        html.append("    const hit = n.id().toLowerCase().includes(q) || (n.data('tags')||'').toLowerCase().includes(q) || (n.data('description')||'').toLowerCase().includes(q);\n");
        html.append("    n.toggleClass('highlighted', hit).toggleClass('dimmed', !hit);\n");
        html.append("  });\n");
        html.append("});\n\n");

        html.append("function filterByTag(tag) {\n");
        html.append("  closePanel();\n");
        html.append("  cy.nodes().forEach(n => {\n");
        html.append("    const hit = (n.data('tags')||'').toLowerCase().split(',').map(t=>t.trim()).includes(tag.toLowerCase());\n");
        html.append("    n.toggleClass('highlighted', hit).toggleClass('dimmed', !hit);\n");
        html.append("  });\n");
        html.append("}\n\n");

        // Edge visibility toggle
        html.append("let edgesVisible = false;\n");
        html.append("function toggleEdges() {\n");
        html.append("    edgesVisible = !edgesVisible;\n");
        html.append("    cy.edges().style('display', edgesVisible ? 'element' : 'none');\n");
        html.append("    document.getElementById('edge-toggle').classList.toggle('active', edgesVisible);\n");
        html.append("    document.getElementById('edge-toggle').textContent = edgesVisible ? 'Hide edges' : 'Show edges';\n");
        html.append("}\n\n");

        // A5: Updated resetGraph to also reset cluster nav
        html.append("function resetGraph() {\n");
        html.append("  cy.nodes().removeClass('highlighted dimmed'); cy.edges().removeClass('dimmed');\n");
        html.append("  document.getElementById('search').value = '';\n");
        html.append("  document.querySelectorAll('.cluster-item').forEach(b => b.classList.remove('active'));\n");
        html.append("  const allBtn = document.getElementById('cluster-all');\n");
        html.append("  if (allBtn) allBtn.classList.add('active');\n");
        html.append("  edgesVisible = false;\n");
        html.append("  cy.edges().style('display', 'none');\n");
        html.append("  document.getElementById('edge-toggle').textContent = 'Show edges';\n");
        html.append("  document.getElementById('edge-toggle').classList.remove('active');\n");
        html.append("  closePanel(); cy.fit();\n");
        html.append("}\n\n");

        // B4: Keyboard handler with Tab navigation
        html.append("document.addEventListener('keydown', e => {\n");
        html.append("    if (e.key === 'Escape') { resetGraph(); return; }\n");
        html.append("    if (e.key === 'Tab' && _tabNeighbors.length > 0) {\n");
        html.append("        e.preventDefault();\n");
        html.append("        _tabIdx = e.shiftKey\n");
        html.append("            ? (_tabIdx - 1 + _tabNeighbors.length) % _tabNeighbors.length\n");
        html.append("            : (_tabIdx + 1) % _tabNeighbors.length;\n");
        html.append("        focusNode(_tabNeighbors[_tabIdx]);\n");
        html.append("    }\n");
        html.append("});\n\n");

        // A5: Cluster nav sidebar builder (flat — no compound parent nodes)
        html.append("function buildClusterNav() {\n");
        html.append("    const list = document.getElementById('cluster-list');\n");
        html.append("    list.innerHTML = '';\n");
        html.append("    const all = document.createElement('div');\n");
        html.append("    all.className = 'cluster-item active';\n");
        html.append("    all.id = 'cluster-all';\n");
        String allLabel = ("workspace".equals(mode) || "modules".equals(mode)) ? "All nodes" : "All skills";
        html.append("    all.innerHTML = `<div class=\"cluster-dot\" style=\"background:#58a6ff\"></div>\n");
        html.append("        <span class=\"cluster-label\">").append(allLabel).append("</span>\n");
        html.append("        <span class=\"cluster-count\">${cy.nodes('[type != \"cluster-label\"]').length}</span>`;\n");
        html.append("    all.onclick = () => resetGraph();\n");
        html.append("    list.appendChild(all);\n");
        html.append("    const clusterIds = [...new Set(cy.nodes('[type != \"cluster-label\"]').map(n => n.data('cluster')).filter(Boolean))];\n");
        html.append("    const clusterData = clusterIds.map(id => ({\n");
        html.append("        id, count: cy.nodes('[cluster = \"' + id + '\"][type != \"cluster-label\"]').length\n");
        html.append("    })).sort((a,b) => b.count - a.count);\n");
        html.append("    clusterData.forEach(({ id, count }) => {\n");
        html.append("        const label = id.replace('cluster-','').replace(/-/g,' ');\n");
        html.append("        const item = document.createElement('div');\n");
        html.append("        item.className = 'cluster-item';\n");
        html.append("        item.dataset.clusterId = id;\n");
        html.append("        item.innerHTML = `<div class=\"cluster-dot\" style=\"background:${clusterColor(id)}\"></div>\n");
        html.append("            <span class=\"cluster-label\">${label}</span>\n");
        html.append("            <span class=\"cluster-count\">${count}</span>`;\n");
        html.append("        item.onclick = () => filterByCluster(id);\n");
        html.append("        list.appendChild(item);\n");
        html.append("    });\n");
        html.append("}\n\n");

        html.append("function filterByCluster(clusterId) {\n");
        html.append("    document.querySelectorAll('.cluster-item').forEach(i => i.classList.remove('active'));\n");
        html.append("    const item = document.querySelector(`.cluster-item[data-cluster-id=\"${clusterId}\"]`);\n");
        html.append("    if (item) item.classList.add('active');\n");
        html.append("    const matchedNodes = cy.nodes(`[cluster=\"${clusterId}\"]`);\n");
        html.append("    cy.nodes().forEach(n => {\n");
        html.append("        const hit = n.data('cluster') === clusterId;\n");
        html.append("        n.toggleClass('highlighted', hit).toggleClass('dimmed', !hit);\n");
        html.append("    });\n");
        html.append("    cy.edges().forEach(e => {\n");
        html.append("        const show = e.source().data('cluster') === clusterId || e.target().data('cluster') === clusterId;\n");
        html.append("        e.toggleClass('dimmed', !show);\n");
        html.append("    });\n");
        html.append("    if (matchedNodes.length) cy.animate({ fit: { eles: matchedNodes, padding: 60 } });\n");
        html.append("}\n\n");

        html.append("function toggleNav() {\n");
        html.append("    const nav = document.getElementById('cluster-nav');\n");
        html.append("    nav.classList.toggle('collapsed');\n");
        html.append("    document.getElementById('nav-collapse').textContent = nav.classList.contains('collapsed') ? '\\u25B6' : '\\u25C0';\n");
        html.append("}\n\n");

        html.append("buildClusterNav();\n");
        html.append("</script>\n</body>\n</html>\n");

        return html.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path resolveOutput(String fmt) throws IOException {
        if (output != null) return output.toAbsolutePath().normalize();
        String ext = "json".equals(fmt) ? ".json" : ".html";
        return Files.createTempFile("synthesis-skills-graph-", ext);
    }

    private void openInBrowser(Path file) {
        try {
            new ProcessBuilder("xdg-open", file.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            AnsiOutput.printWarning("Could not open browser: " + e.getMessage());
            AnsiOutput.printInfo("Open manually: " + file);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(i -> i instanceof String)
                    .map(i -> ((String) i).strip())
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v instanceof String s ? s : null;
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", " ")
                       .replace("\r", "")
                       .replace("\t", " ")
               + "\"";
    }

    private static String jsonList(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        return "[" + list.stream()
                         .map(SkillsGraphCommand::jsonStr)
                         .collect(Collectors.joining(",")) + "]";
    }
}
