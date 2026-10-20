package io.exoreaction.synthesis.jira;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;

/**
 * Converts Atlassian Document Format (ADF) JSON — used by Jira's {@code description}
 * and comment {@code body} fields in REST API v3 — to Markdown text.
 *
 * <p><b>Scope note:</b> ADF is shared across Jira and Confluence (both Atlassian Cloud
 * products). This converter is scoped to the node types Jira actually emits in issue
 * descriptions and comments (paragraphs, headings, lists, code blocks, block quotes,
 * tables, rules, text marks, mentions). If/when #541's Confluence connector lands its
 * own ADF converter, the two should be consolidated into a shared
 * {@code io.exoreaction.synthesis.adf} package to remove duplication — not done here
 * since #541 had not landed a converter at the time this was written (see PR notes).
 *
 * <p>Covers the common ADF node types: {@code doc}, {@code paragraph}, {@code heading},
 * {@code bulletList}/{@code orderedList}/{@code listItem}, {@code codeBlock},
 * {@code blockquote}, {@code rule}, {@code table}/{@code tableRow}/{@code tableCell}/
 * {@code tableHeader}, {@code hardBreak}, {@code mention}, and {@code text} with marks
 * (strong, em, strike, code, link). Unknown node types are rendered as HTML comments
 * for lossless round-trip awareness, mirroring {@code NotionBlockToMarkdown}'s fallback.
 */
public class JiraAdfToMarkdown {

    /**
     * Converts a full ADF document (the {@code description} or comment {@code body}
     * field) to a Markdown string.
     *
     * @param adfDoc the ADF document JSON node (top-level {@code {"type":"doc",...}})
     * @return the Markdown representation, or empty string if {@code adfDoc} is null/empty
     */
    public String convert(JsonNode adfDoc) {
        if (adfDoc == null || adfDoc.isNull()) {
            return "";
        }

        JsonNode content = adfDoc.get("content");
        if (content == null || !content.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode node : content) {
            String converted = convertNode(node, 0);
            if (!converted.isEmpty()) {
                sb.append(converted);
                sb.append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Converts a single ADF node to Markdown.
     *
     * @param node        the ADF node JSON object
     * @param indentLevel nesting depth for list items (0 = top level)
     * @return the Markdown representation of this node
     */
    String convertNode(JsonNode node, int indentLevel) {
        if (node == null) {
            return "";
        }

        String type = node.has("type") ? node.get("type").asText() : "";

        return switch (type) {
            case "paragraph" -> convertInlineContent(node.get("content")) + "\n";
            case "heading" -> {
                int level = node.has("attrs") && node.get("attrs").has("level")
                        ? node.get("attrs").get("level").asInt(1)
                        : 1;
                level = Math.max(1, Math.min(level, 6));
                yield "#".repeat(level) + " " + convertInlineContent(node.get("content")) + "\n";
            }
            case "bulletList" -> convertList(node.get("content"), indentLevel, false);
            case "orderedList" -> convertList(node.get("content"), indentLevel, true);
            case "listItem" -> convertListItemBody(node.get("content"), indentLevel);
            case "codeBlock" -> {
                String language = node.has("attrs") && node.get("attrs").has("language")
                        ? node.get("attrs").get("language").asText()
                        : "";
                String text = convertInlineContent(node.get("content"));
                yield "```" + language + "\n" + text + "\n```\n";
            }
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                JsonNode children = node.get("content");
                if (children != null && children.isArray()) {
                    for (JsonNode child : children) {
                        inner.append(convertNode(child, 0));
                    }
                }
                yield prefixLines(inner.toString().stripTrailing(), "> ") + "\n";
            }
            case "rule" -> "---\n";
            case "hardBreak" -> "\n";
            case "table" -> convertTable(node.get("content"));
            case "mediaSingle", "media" -> "<!-- Jira attachment/media -->\n";
            default -> "<!-- ADF node: " + type + " -->\n";
        };
    }

    /**
     * Converts an ADF {@code content} array of inline nodes (text, hardBreak, mention,
     * emoji, inlineCard) to a single Markdown string, applying text marks.
     *
     * @param inlineContent the ADF content array (may be null)
     * @return the formatted Markdown string
     */
    String convertInlineContent(JsonNode inlineContent) {
        if (inlineContent == null || !inlineContent.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode node : inlineContent) {
            String type = node.has("type") ? node.get("type").asText() : "";
            switch (type) {
                case "text" -> sb.append(convertTextNode(node));
                case "hardBreak" -> sb.append("\n");
                case "mention" -> {
                    JsonNode attrs = node.get("attrs");
                    String name = attrs != null && attrs.has("text")
                            ? attrs.get("text").asText()
                            : (attrs != null && attrs.has("id") ? attrs.get("id").asText() : "mention");
                    sb.append(name);
                }
                case "emoji" -> {
                    JsonNode attrs = node.get("attrs");
                    sb.append(attrs != null && attrs.has("text") ? attrs.get("text").asText() : "");
                }
                case "inlineCard" -> {
                    JsonNode attrs = node.get("attrs");
                    String url = attrs != null && attrs.has("url") ? attrs.get("url").asText() : "";
                    sb.append("[").append(url).append("](").append(url).append(")");
                }
                default -> sb.append("<!-- ADF inline: ").append(type).append(" -->");
            }
        }
        return sb.toString();
    }

    /**
     * Converts a single ADF {@code text} node, applying marks: {@code strong}, {@code em},
     * {@code strike}, {@code code}, {@code link}.
     */
    private String convertTextNode(JsonNode node) {
        String text = node.has("text") ? node.get("text").asText() : "";
        if (text.isEmpty()) return "";

        boolean bold = false;
        boolean italic = false;
        boolean strike = false;
        boolean code = false;
        String href = null;

        JsonNode marks = node.get("marks");
        if (marks != null && marks.isArray()) {
            for (JsonNode mark : marks) {
                String markType = mark.has("type") ? mark.get("type").asText() : "";
                switch (markType) {
                    case "strong" -> bold = true;
                    case "em" -> italic = true;
                    case "strike" -> strike = true;
                    case "code" -> code = true;
                    case "link" -> {
                        JsonNode attrs = mark.get("attrs");
                        if (attrs != null && attrs.has("href")) {
                            href = attrs.get("href").asText();
                        }
                    }
                    default -> { /* ignore unsupported marks (e.g. textColor, subsup) */ }
                }
            }
        }

        if (code) {
            text = "`" + text + "`";
        } else {
            if (bold && italic) {
                text = "***" + text + "***";
            } else if (bold) {
                text = "**" + text + "**";
            } else if (italic) {
                text = "*" + text + "*";
            }
            if (strike) {
                text = "~~" + text + "~~";
            }
        }

        if (href != null) {
            text = "[" + text + "](" + href + ")";
        }

        return text;
    }

    private String convertList(JsonNode items, int indentLevel, boolean ordered) {
        if (items == null || !items.isArray()) {
            return "";
        }
        String indent = "    ".repeat(indentLevel);
        StringBuilder sb = new StringBuilder();
        int number = 1;
        for (JsonNode item : items) {
            String marker = ordered ? (number++ + ". ") : "- ";
            String body = convertListItemBody(item.get("content"), indentLevel);
            String[] lines = body.split("\n", -1);
            boolean first = true;
            for (String line : lines) {
                if (line.isEmpty() && !first) continue;
                if (first) {
                    sb.append(indent).append(marker).append(line).append("\n");
                    first = false;
                } else {
                    sb.append("    ".repeat(indentLevel + 1)).append(line).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Converts the content of a {@code listItem}: typically a single paragraph plus
     * optionally a nested {@code bulletList}/{@code orderedList}.
     */
    private String convertListItemBody(JsonNode content, int indentLevel) {
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode child : content) {
            String type = child.has("type") ? child.get("type").asText() : "";
            if ("paragraph".equals(type)) {
                sb.append(convertInlineContent(child.get("content")));
            } else if ("bulletList".equals(type) || "orderedList".equals(type)) {
                sb.append("\n").append(convertNode(child, indentLevel + 1).stripTrailing());
            } else {
                sb.append(convertNode(child, indentLevel).stripTrailing());
            }
        }
        return sb.toString();
    }

    private String convertTable(JsonNode rows) {
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder();
        boolean firstRow = true;
        for (JsonNode row : rows) {
            if (!"tableRow".equals(row.path("type").asText())) continue;
            JsonNode cells = row.get("content");
            if (cells == null || !cells.isArray()) continue;

            sb.append("|");
            for (JsonNode cell : cells) {
                sb.append(" ").append(convertCellText(cell)).append(" |");
            }
            sb.append("\n");

            if (firstRow) {
                sb.append("|");
                for (int i = 0; i < cells.size(); i++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
                firstRow = false;
            }
        }
        return sb.toString();
    }

    private String convertCellText(JsonNode cell) {
        JsonNode content = cell.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        Iterator<JsonNode> it = content.iterator();
        while (it.hasNext()) {
            JsonNode block = it.next();
            if ("paragraph".equals(block.path("type").asText())) {
                sb.append(convertInlineContent(block.get("content")));
            }
            if (it.hasNext()) sb.append(" ");
        }
        return sb.toString();
    }

    private String prefixLines(String text, String prefix) {
        if (text == null || text.isEmpty()) {
            return prefix;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(prefix).append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
