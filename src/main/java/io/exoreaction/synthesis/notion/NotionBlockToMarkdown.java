package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts Notion block JSON (Jackson {@link JsonNode}) to Markdown text.
 *
 * <p>Covers all common Notion block types: paragraphs, headings, lists,
 * code blocks, quotes, callouts, dividers, images, bookmarks, tables,
 * child pages/databases, and to-do items. Unknown block types are rendered
 * as HTML comments for lossless round-trip awareness.
 *
 * <p>Rich text arrays are converted with full annotation support:
 * bold, italic, strikethrough, inline code, and links.
 */
public class NotionBlockToMarkdown {

    /**
     * Converts a list of Notion block JSON nodes to a single Markdown string.
     *
     * @param blocks list of Notion block JSON objects
     * @return the Markdown representation, with blocks separated by newlines
     */
    public String convert(List<JsonNode> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            JsonNode block = blocks.get(i);
            String converted = convertBlock(block, 0);
            sb.append(converted);
            if (i < blocks.size() - 1 && !converted.isEmpty()) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Converts a single Notion block JSON node to Markdown.
     *
     * @param block       the Notion block JSON object
     * @param indentLevel nesting depth for list items (0 = top level)
     * @return the Markdown representation of this block
     */
    String convertBlock(JsonNode block, int indentLevel) {
        if (block == null) {
            return "";
        }

        String type = block.has("type") ? block.get("type").asText() : "";
        String indent = "    ".repeat(indentLevel);

        return switch (type) {
            case "paragraph" -> {
                JsonNode data = block.get("paragraph");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                yield text + "\n";
            }
            case "heading_1" -> {
                JsonNode data = block.get("heading_1");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                yield "# " + text + "\n";
            }
            case "heading_2" -> {
                JsonNode data = block.get("heading_2");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                yield "## " + text + "\n";
            }
            case "heading_3" -> {
                JsonNode data = block.get("heading_3");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                yield "### " + text + "\n";
            }
            case "bulleted_list_item" -> {
                JsonNode data = block.get("bulleted_list_item");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                String result = indent + "- " + text + "\n";
                // Handle nested children
                if (data != null && data.has("children")) {
                    result += convertChildren(data.get("children"), indentLevel + 1);
                }
                yield result;
            }
            case "numbered_list_item" -> {
                JsonNode data = block.get("numbered_list_item");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                String result = indent + "1. " + text + "\n";
                // Handle nested children
                if (data != null && data.has("children")) {
                    result += convertChildren(data.get("children"), indentLevel + 1);
                }
                yield result;
            }
            case "to_do" -> {
                JsonNode data = block.get("to_do");
                if (data == null) yield "\n";
                String text = convertRichText(data.get("rich_text"));
                boolean checked = data.has("checked") && data.get("checked").asBoolean();
                yield indent + "- [" + (checked ? "x" : " ") + "] " + text + "\n";
            }
            case "code" -> {
                JsonNode data = block.get("code");
                if (data == null) yield "\n";
                String text = convertRichText(data.get("rich_text"));
                String language = data.has("language") ? data.get("language").asText() : "";
                yield "```" + language + "\n" + text + "\n```\n";
            }
            case "quote" -> {
                JsonNode data = block.get("quote");
                String text = data != null ? convertRichText(data.get("rich_text")) : "";
                yield prefixLines(text, "> ") + "\n";
            }
            case "callout" -> {
                JsonNode data = block.get("callout");
                if (data == null) yield "\n";
                String text = convertRichText(data.get("rich_text"));
                String emoji = "";
                if (data.has("icon")) {
                    JsonNode icon = data.get("icon");
                    if (icon.has("emoji")) {
                        emoji = icon.get("emoji").asText() + " ";
                    }
                }
                yield prefixLines(emoji + text, "> ") + "\n";
            }
            case "divider" -> "---\n";
            case "image" -> {
                JsonNode data = block.get("image");
                if (data == null) yield "\n";
                String url = extractFileUrl(data);
                String caption = "";
                if (data.has("caption")) {
                    caption = convertRichText(data.get("caption"));
                }
                yield "![" + caption + "](" + url + ")\n";
            }
            case "bookmark" -> {
                JsonNode data = block.get("bookmark");
                if (data == null) yield "\n";
                String url = data.has("url") ? data.get("url").asText() : "";
                yield "[" + url + "](" + url + ")\n";
            }
            case "table" -> {
                // Table block: children contain table_row blocks
                JsonNode data = block.get("table");
                if (data == null || !block.has("children")) yield "\n";
                yield convertTable(block.get("children"));
            }
            case "table_row" -> {
                JsonNode data = block.get("table_row");
                if (data == null || !data.has("cells")) yield "\n";
                yield convertTableRow(data.get("cells"));
            }
            case "child_page" -> {
                JsonNode data = block.get("child_page");
                String title = data != null && data.has("title") ? data.get("title").asText() : "Untitled";
                String id = block.has("id") ? block.get("id").asText() : "";
                yield "[" + title + "](notion://" + id + ")\n";
            }
            case "child_database" -> {
                JsonNode data = block.get("child_database");
                String title = data != null && data.has("title") ? data.get("title").asText() : "Untitled Database";
                String id = block.has("id") ? block.get("id").asText() : "";
                yield "[" + title + "](notion://" + id + ")\n";
            }
            default -> "<!-- Notion block: " + type + " -->\n";
        };
    }

    /**
     * Converts a Notion rich_text JSON array to formatted Markdown text.
     *
     * <p>Handles annotations: bold, italic, strikethrough, code, and links.
     * Multiple annotations can be combined (e.g. bold + italic).
     *
     * @param richTextArray the JSON array of rich text objects
     * @return the formatted Markdown string
     */
    String convertRichText(JsonNode richTextArray) {
        if (richTextArray == null || !richTextArray.isArray() || richTextArray.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode segment : richTextArray) {
            String plainText = segment.has("plain_text") ? segment.get("plain_text").asText() : "";
            if (plainText.isEmpty()) {
                continue;
            }

            // Check for link in href field
            String href = segment.has("href") && !segment.get("href").isNull()
                    ? segment.get("href").asText()
                    : null;

            // Check annotations
            boolean bold = false;
            boolean italic = false;
            boolean strikethrough = false;
            boolean code = false;

            if (segment.has("annotations")) {
                JsonNode ann = segment.get("annotations");
                bold = ann.has("bold") && ann.get("bold").asBoolean();
                italic = ann.has("italic") && ann.get("italic").asBoolean();
                strikethrough = ann.has("strikethrough") && ann.get("strikethrough").asBoolean();
                code = ann.has("code") && ann.get("code").asBoolean();
            }

            String text = plainText;

            // Apply inline code first (no other formatting inside code spans)
            if (code) {
                text = "`" + text + "`";
            } else {
                // Apply formatting in order: strikethrough, then bold+italic combinations
                if (bold && italic) {
                    text = "***" + text + "***";
                } else if (bold) {
                    text = "**" + text + "**";
                } else if (italic) {
                    text = "*" + text + "*";
                }
                if (strikethrough) {
                    text = "~~" + text + "~~";
                }
            }

            // Apply link wrapping
            if (href != null) {
                text = "[" + text + "](" + href + ")";
            }

            sb.append(text);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private String convertChildren(JsonNode children, int indentLevel) {
        if (children == null || !children.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode child : children) {
            sb.append(convertBlock(child, indentLevel));
        }
        return sb.toString();
    }

    private String convertTable(JsonNode rows) {
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return "\n";
        }

        StringBuilder sb = new StringBuilder();
        boolean isHeader = true;
        for (JsonNode row : rows) {
            JsonNode tableRow = row.get("table_row");
            if (tableRow == null || !tableRow.has("cells")) continue;
            sb.append(convertTableRow(tableRow.get("cells")));
            if (isHeader) {
                // Add separator row after header
                JsonNode cells = tableRow.get("cells");
                sb.append("|");
                for (int i = 0; i < cells.size(); i++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
                isHeader = false;
            }
        }
        return sb.toString();
    }

    private String convertTableRow(JsonNode cells) {
        if (cells == null || !cells.isArray()) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder("|");
        for (JsonNode cell : cells) {
            String text = convertRichText(cell);
            sb.append(" ").append(text).append(" |");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String extractFileUrl(JsonNode fileBlock) {
        if (fileBlock == null) return "";
        String fileType = fileBlock.has("type") ? fileBlock.get("type").asText() : "";
        JsonNode urlSource = fileBlock.get(fileType);
        if (urlSource != null && urlSource.has("url")) {
            return urlSource.get("url").asText();
        }
        return "";
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
