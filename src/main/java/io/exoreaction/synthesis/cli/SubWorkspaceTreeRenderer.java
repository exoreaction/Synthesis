package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.util.AnsiOutput;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders sub-workspace hierarchy as a visual tree with ANSI colors and Unicode graphics.
 */
public class SubWorkspaceTreeRenderer {

    /**
     * Tree node representing a sub-workspace and its children.
     */
    static class TreeNode {
        String name;
        String path;
        String type;
        List<String> tags;
        String description;
        long ownFileCount;      // Files directly in this sub-workspace
        long totalFileCount;    // Own + all descendants
        List<TreeNode> children = new ArrayList<>();

        TreeNode(String name, String path, String type, List<String> tags, String description) {
            this.name = name;
            this.path = path;
            this.type = type != null ? type : "general";
            this.tags = tags != null ? tags : new ArrayList<>();
            this.description = description;
            this.ownFileCount = 0;
            this.totalFileCount = 0;
        }
    }

    /**
     * Renders the sub-workspace tree to stdout with ANSI formatting.
     *
     * @param subWorkspaces  configured sub-workspaces from config
     * @param fileCounts     map from sub-workspace name -> file count (from index)
     * @param totalFiles     total files in the workspace (for percentage calculation)
     */
    public static void render(List<SubWorkspaceConfig> subWorkspaces,
                              Map<String, Long> fileCounts,
                              long totalFiles) {
        if (subWorkspaces == null || subWorkspaces.isEmpty()) {
            return;
        }

        // Build tree from flat list
        List<TreeNode> roots = buildTree(subWorkspaces, fileCounts);

        if (roots.isEmpty()) {
            return;
        }

        // Find max count for bar chart scaling
        long maxCount = roots.stream()
                .mapToLong(n -> n.totalFileCount)
                .max()
                .orElse(1);

        // Header
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Sub-workspace Hierarchy:") +
                          AnsiOutput.dim(String.format("                    %,d files", totalFiles)));
        System.out.println("  " + AnsiOutput.dim("╔════════════════════════════════════════════════════════════════════════╗"));

        // Render each root
        for (int i = 0; i < roots.size(); i++) {
            TreeNode root = roots.get(i);
            boolean isLast = (i == roots.size() - 1);
            renderNode(root, "  ║  ", isLast, totalFiles, maxCount, true);
        }

        System.out.println("  " + AnsiOutput.dim("╚════════════════════════════════════════════════════════════════════════╝"));
    }

    /**
     * Build tree from flat list using path-prefix relationships.
     */
    static List<TreeNode> buildTree(List<SubWorkspaceConfig> subWorkspaces,
                                     Map<String, Long> fileCounts) {
        // Step 1: Create nodes for each sub-workspace
        Map<String, TreeNode> nodeMap = new HashMap<>();
        for (SubWorkspaceConfig sw : subWorkspaces) {
            TreeNode node = new TreeNode(sw.getName(), sw.getPath(),
                    sw.getType(), sw.getTags(), sw.getDescription());
            node.ownFileCount = fileCounts.getOrDefault(sw.getName(), 0L);
            nodeMap.put(sw.getName(), node);
        }

        // Step 2: Determine parent-child relationships
        // Sort by path length (shorter paths = higher in hierarchy)
        List<TreeNode> sortedNodes = nodeMap.values().stream()
                .sorted(Comparator.comparingInt(n -> n.path.length()))
                .collect(Collectors.toList());

        List<TreeNode> roots = new ArrayList<>();
        for (TreeNode node : sortedNodes) {
            // Find the most specific (longest path) ancestor
            TreeNode bestParent = null;
            int bestParentPathLen = 0;

            for (TreeNode candidate : nodeMap.values()) {
                if (candidate != node &&
                    node.path.startsWith(candidate.path + "/") &&
                    candidate.path.length() > bestParentPathLen) {
                    bestParent = candidate;
                    bestParentPathLen = candidate.path.length();
                }
            }

            if (bestParent != null) {
                bestParent.children.add(node);
            } else {
                roots.add(node);
            }
        }

        // Step 3: Aggregate counts bottom-up
        for (TreeNode root : roots) {
            aggregateCounts(root);
        }

        // Step 4: Sort children within each level by totalFileCount (descending)
        sortChildren(roots);

        return roots;
    }

    /**
     * Aggregate file counts from children upward.
     */
    static void aggregateCounts(TreeNode node) {
        node.totalFileCount = node.ownFileCount;
        for (TreeNode child : node.children) {
            aggregateCounts(child);
            node.totalFileCount += child.totalFileCount;
        }
    }

    /**
     * Sort children recursively by total file count (descending).
     */
    static void sortChildren(List<TreeNode> nodes) {
        nodes.sort((a, b) -> Long.compare(b.totalFileCount, a.totalFileCount));
        for (TreeNode node : nodes) {
            sortChildren(node.children);
        }
    }

    /**
     * Render a single tree node with proper indentation.
     */
    private static void renderNode(TreeNode node, String prefix, boolean isLast,
                                    long totalFiles, long maxCount, boolean isRoot) {
        StringBuilder line = new StringBuilder();
        line.append(prefix);

        // Tree connector (skip for root level)
        String connector = "";
        String continuation = "";

        if (!isRoot) {
            connector = isLast ? "└── " : "├── ";
            continuation = isLast ? "    " : "│   ";
            line.append(AnsiOutput.dim(connector));
        }

        // Name
        String nameStr = AnsiOutput.bold(node.name);
        line.append(nameStr);

        // Padding to align badges
        int nameLen = node.name.length() + (isRoot ? 0 : connector.length());
        int targetCol = 32;
        if (nameLen < targetCol) {
            line.append(" ".repeat(targetCol - nameLen));
        } else {
            line.append("  ");
        }

        // Badge
        String badge = statusBadge(node.type, node.tags);
        line.append(badge);

        // Padding to align counts
        int currentLen = targetCol + stripAnsi(badge).length();
        int countCol = 50;
        if (currentLen < countCol) {
            line.append(" ".repeat(countCol - currentLen));
        } else {
            line.append("  ");
        }

        // File count
        line.append(String.format("%,6d", node.totalFileCount));
        line.append(" ");

        // Mini bar chart
        String bar = miniBar(node.totalFileCount, maxCount, 12);
        line.append(bar);

        // Percentage
        double pct = totalFiles > 0 ? (node.totalFileCount * 100.0 / totalFiles) : 0;
        String pctStr = pct < 1 && pct > 0 ? "<1%" : String.format("%3.0f%%", pct);
        line.append("  ");
        line.append(AnsiOutput.dim(pctStr));

        // Add trailing space and border
        line.append(" ".repeat(Math.max(0, 2)));
        line.append(AnsiOutput.dim("║"));

        System.out.println(line);

        // Render children
        for (int i = 0; i < node.children.size(); i++) {
            TreeNode child = node.children.get(i);
            boolean childIsLast = (i == node.children.size() - 1);
            String childPrefix = prefix + (isRoot ? "" : continuation);
            renderNode(child, childPrefix, childIsLast, totalFiles, maxCount, false);
        }
    }

    /**
     * Generate the mini bar chart string using Unicode block elements.
     */
    static String miniBar(long count, long maxCount, int maxWidth) {
        if (maxCount == 0 || count == 0) {
            return "";
        }

        // Calculate proportional width (8 sub-units per character)
        double fraction = (double) count / maxCount;
        int totalEighths = (int) Math.round(fraction * maxWidth * 8);

        int fullBlocks = totalEighths / 8;
        int remainder = totalEighths % 8;

        StringBuilder bar = new StringBuilder();

        // Full blocks
        for (int i = 0; i < fullBlocks && i < maxWidth; i++) {
            bar.append("█");
        }

        // Partial block
        if (fullBlocks < maxWidth && remainder > 0) {
            // Unicode block elements: ▏▎▍▌▋▊▉ (1/8 to 7/8)
            String[] eighths = {" ", "▏", "▎", "▍", "▌", "▋", "▊", "▉"};
            bar.append(eighths[remainder]);
        }

        return AnsiOutput.green(bar.toString());
    }

    /**
     * Determine the status badge from tags.
     */
    static String statusBadge(String type, List<String> tags) {
        // Functional areas (higher priority than entity types)
        if (tags.contains("clients")) {
            return AnsiOutput.dim("[clients]");
        }

        // Entity types
        if (tags.contains("company")) {
            return AnsiOutput.blue("[company]");
        }
        if (tags.contains("foundation")) {
            return AnsiOutput.green("[foundation]");
        }
        if (tags.contains("holding")) {
            return AnsiOutput.magenta("[holding]");
        }
        if (tags.contains("personal")) {
            return AnsiOutput.dim("[personal]");
        }

        // Client badges
        if ("client".equals(type) || tags.contains("client")) {
            if (tags.contains("hot")) {
                return AnsiOutput.bold(AnsiOutput.yellow("★ hot"));
            }
            if (tags.contains("closed")) {
                return AnsiOutput.green("✓ closed");
            }
            if (tags.contains("warm")) {
                return AnsiOutput.yellow("~ warm");
            }
            if (tags.contains("active")) {
                return AnsiOutput.green("[active]");
            }
            if (tags.contains("past")) {
                return AnsiOutput.dim("[past]");
            }
            if (tags.contains("opportunity")) {
                return AnsiOutput.yellow("[opportunity]");
            }
            return AnsiOutput.cyan("[client]");
        }

        // Functional areas (eXOReaction-Clients, etc.)
        if (tags.contains("clients")) {
            return AnsiOutput.dim("[clients]");
        }

        return "";
    }

    /**
     * Strip ANSI escape sequences for length calculation.
     */
    private static String stripAnsi(String str) {
        return str.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
