package io.exoreaction.synthesis.confluence;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/**
 * Converts Confluence storage-format XHTML (the {@code body.storage.value} returned
 * by the Confluence Cloud REST API v2 when requesting {@code body-format=storage})
 * to Markdown text.
 *
 * <p>Confluence storage format is XHTML with Confluence-specific namespaced elements
 * ({@code ac:*} for macros/structural constructs, {@code ri:*} for resource identifiers
 * like page/attachment references). This is a genuinely different markup model from
 * Notion's flat/nested block JSON — none of {@code NotionBlockToMarkdown}'s
 * switch-on-block-type logic transfers as code — but the architectural role (a single
 * "content to Markdown" converter consumed by the workspace source) and the
 * lossless-fallback principle for unrecognized constructs (preserved as an HTML
 * comment, mirroring {@code NotionBlockToMarkdown}'s unknown-block-type handling)
 * are deliberately kept the same.
 *
 * <p>Covers common storage-format constructs: paragraphs, headings, bulleted/numbered
 * lists (including nesting), tables, blockquotes, horizontal rules, inline formatting
 * (bold, italic, code, strikethrough, links), images, page links, task lists, and the
 * {@code code}/{@code info}/{@code note}/{@code warning}/{@code tip} structured macros.
 * Unrecognized {@code ac:structured-macro} types are rendered as HTML comments for
 * lossless round-trip awareness.
 */
public class ConfluenceContentToMarkdown {

    /**
     * Converts a Confluence storage-format XHTML fragment to a Markdown string.
     *
     * <p>The fragment is typically the {@code body.storage.value} of a single page
     * and may contain multiple top-level block elements with no single root — this
     * method wraps it in a synthetic root before parsing.
     *
     * @param storageXhtml the storage-format XHTML, or {@code null}/blank for no content
     * @return the Markdown representation, or {@code ""} for null/blank input
     */
    public String convert(String storageXhtml) {
        if (storageXhtml == null || storageXhtml.isBlank()) {
            return "";
        }

        Document doc;
        try {
            doc = parse(storageXhtml);
        } catch (Exception e) {
            // Malformed storage-format content: don't throw, preserve awareness and move on.
            return "<!-- Confluence content: failed to parse storage format: "
                    + e.getMessage() + " -->\n";
        }

        StringBuilder sb = new StringBuilder();
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            sb.append(convertNode(children.item(i), 0));
        }
        return sb.toString();
    }

    /**
     * Parses a storage-format XHTML fragment, wrapping it in a synthetic root element.
     *
     * <p>Namespace awareness is intentionally disabled: Confluence's {@code ac:*} and
     * {@code ri:*} elements use colon-containing names that are valid XML 1.0 {@code Name}
     * tokens but are not declared against real namespace URIs in standalone page bodies.
     * Treating them as opaque, colon-containing tag names (rather than requiring namespace
     * declarations) avoids spurious parse failures.
     */
    private Document parse(String storageXhtml)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // Feature not supported by this JAXP implementation — not fatal.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        String wrapped = "<root>" + storageXhtml + "</root>";
        return builder.parse(new InputSource(new StringReader(wrapped)));
    }

    // -----------------------------------------------------------------------
    // Block-level conversion
    // -----------------------------------------------------------------------

    /**
     * Converts a single DOM node (element or text) to Markdown at the given list nesting level.
     *
     * @param node        the DOM node
     * @param indentLevel nesting depth for list items (0 = top level)
     * @return the Markdown representation of this node
     */
    String convertNode(Node node, int indentLevel) {
        if (node == null) {
            return "";
        }

        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String text = node.getNodeValue();
            return (text == null || text.isBlank()) ? "" : text;
        }

        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return "";
        }

        Element el = (Element) node;
        String tag = el.getTagName().toLowerCase();
        String indent = "    ".repeat(indentLevel);

        return switch (tag) {
            case "p" -> inlineText(el) + "\n";
            case "h1" -> "# " + inlineText(el) + "\n";
            case "h2" -> "## " + inlineText(el) + "\n";
            case "h3" -> "### " + inlineText(el) + "\n";
            case "h4" -> "#### " + inlineText(el) + "\n";
            case "h5" -> "##### " + inlineText(el) + "\n";
            case "h6" -> "###### " + inlineText(el) + "\n";
            case "ul" -> convertListItems(el, indentLevel, false);
            case "ol" -> convertListItems(el, indentLevel, true);
            case "hr" -> "---\n";
            case "blockquote" -> prefixLines(inlineBlockText(el).strip(), "> ") + "\n";
            case "table" -> convertTable(el);
            case "ac:task-list" -> convertTaskList(el, indentLevel);
            case "ac:structured-macro" -> convertMacro(el);
            case "div" -> convertChildren(el, indentLevel);
            default -> convertChildren(el, indentLevel);
        };
    }

    private String convertChildren(Element el, int indentLevel) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            sb.append(convertNode(children.item(i), indentLevel));
        }
        return sb.toString();
    }

    private String convertListItems(Element listEl, int indentLevel, boolean ordered) {
        StringBuilder sb = new StringBuilder();
        String indent = "    ".repeat(indentLevel);
        NodeList children = listEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element li = (Element) child;
            if (!"li".equalsIgnoreCase(li.getTagName())) continue;

            String marker = ordered ? "1. " : "- ";
            sb.append(indent).append(marker).append(listItemOwnText(li)).append("\n");

            // Nested lists inside this <li>
            NodeList liChildren = li.getChildNodes();
            for (int j = 0; j < liChildren.getLength(); j++) {
                Node nested = liChildren.item(j);
                if (nested.getNodeType() == Node.ELEMENT_NODE) {
                    String nestedTag = ((Element) nested).getTagName().toLowerCase();
                    if ("ul".equals(nestedTag) || "ol".equals(nestedTag)) {
                        sb.append(convertNode(nested, indentLevel + 1));
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Converts a {@code <li>}'s own inline content, excluding any nested {@code <ul>}/{@code <ol>}
     * children — those are rendered separately (and indented) by {@link #convertListItems}.
     */
    private String listItemOwnText(Element li) {
        StringBuilder sb = new StringBuilder();
        NodeList children = li.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String t = ((Element) child).getTagName().toLowerCase();
                if ("ul".equals(t) || "ol".equals(t)) {
                    continue; // rendered separately as a nested list
                }
            }
            sb.append(convertInline(child));
        }
        return sb.toString();
    }

    private String convertTable(Element table) {
        NodeList rows = table.getElementsByTagName("tr");
        if (rows.getLength() == 0) {
            return "\n";
        }

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.getLength(); r++) {
            Element row = (Element) rows.item(r);
            NodeList cells = row.getChildNodes();
            sb.append("|");
            int cellCount = 0;
            for (int c = 0; c < cells.getLength(); c++) {
                Node cellNode = cells.item(c);
                if (cellNode.getNodeType() != Node.ELEMENT_NODE) continue;
                String cellTag = ((Element) cellNode).getTagName().toLowerCase();
                if (!"th".equals(cellTag) && !"td".equals(cellTag)) continue;
                sb.append(" ").append(inlineText((Element) cellNode)).append(" |");
                cellCount++;
            }
            sb.append("\n");
            if (r == 0) {
                sb.append("|");
                for (int c = 0; c < cellCount; c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String convertTaskList(Element taskListEl, int indentLevel) {
        StringBuilder sb = new StringBuilder();
        String indent = "    ".repeat(indentLevel);
        NodeList tasks = taskListEl.getElementsByTagName("ac:task");
        for (int i = 0; i < tasks.getLength(); i++) {
            Element task = (Element) tasks.item(i);
            String status = firstChildText(task, "ac:task-status");
            String body = firstChildText(task, "ac:task-body");
            boolean complete = "complete".equalsIgnoreCase(status);
            sb.append(indent).append("- [").append(complete ? "x" : " ").append("] ")
                    .append(body == null ? "" : body).append("\n");
        }
        return sb.toString();
    }

    private String convertMacro(Element macro) {
        String name = macro.getAttribute("ac:name");
        if (name == null || name.isBlank()) {
            name = "unknown";
        }

        return switch (name) {
            case "code" -> convertCodeMacro(macro);
            case "info" -> convertPanelMacro(macro, "ℹ️ ");
            case "note" -> convertPanelMacro(macro, "⚠️ ");
            case "warning" -> convertPanelMacro(macro, "⚠️ ");
            case "tip" -> convertPanelMacro(macro, "💡 ");
            default -> "<!-- Confluence macro: " + name + " -->\n";
        };
    }

    private String convertCodeMacro(Element macro) {
        String language = "";
        NodeList params = macro.getElementsByTagName("ac:parameter");
        for (int i = 0; i < params.getLength(); i++) {
            Element param = (Element) params.item(i);
            if ("language".equals(param.getAttribute("ac:name"))) {
                language = textContentOf(param);
            }
        }

        String body = "";
        NodeList plainBodies = macro.getElementsByTagName("ac:plain-text-body");
        if (plainBodies.getLength() > 0) {
            body = textContentOf((Element) plainBodies.item(0));
        } else {
            NodeList richBodies = macro.getElementsByTagName("ac:rich-text-body");
            if (richBodies.getLength() > 0) {
                body = inlineBlockText((Element) richBodies.item(0)).strip();
            }
        }

        return "```" + language + "\n" + body + "\n```\n";
    }

    private String convertPanelMacro(Element macro, String emojiPrefix) {
        NodeList richBodies = macro.getElementsByTagName("ac:rich-text-body");
        String text;
        if (richBodies.getLength() > 0) {
            text = inlineBlockText((Element) richBodies.item(0)).strip();
        } else {
            text = textContentOf(macro).strip();
        }
        return prefixLines(emojiPrefix + text, "> ") + "\n";
    }

    // -----------------------------------------------------------------------
    // Inline conversion
    // -----------------------------------------------------------------------

    /**
     * Converts the inline (rich-text) content of a block element (e.g. a {@code <p>}
     * or {@code <li>}) to a single-line Markdown string.
     */
    String inlineText(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            sb.append(convertInline(children.item(i)));
        }
        return sb.toString();
    }

    /**
     * Converts the content of an element that may itself contain multiple block-level
     * children (e.g. {@code ac:rich-text-body} containing several {@code <p>} tags),
     * joining them with newlines rather than concatenating inline.
     */
    private String inlineBlockText(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.matches("p|h[1-6]|ul|ol|blockquote")) {
                    sb.append(convertNode(child, 0));
                    continue;
                }
                sb.append(convertInline(child));
                continue;
            }
            // Skip whitespace-only text between block-level children (formatting artifacts
            // from indentation in the source XHTML) — meaningful inline text is preserved.
            if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && (child.getNodeValue() == null || child.getNodeValue().isBlank())) {
                continue;
            }
            sb.append(convertInline(child));
        }
        return sb.toString();
    }

    private String convertInline(Node node) {
        if (node == null) {
            return "";
        }
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String text = node.getNodeValue();
            return text == null ? "" : text;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return "";
        }

        Element el = (Element) node;
        String tag = el.getTagName().toLowerCase();

        return switch (tag) {
            case "strong", "b" -> "**" + inlineText(el) + "**";
            case "em", "i" -> "*" + inlineText(el) + "*";
            case "code" -> "`" + inlineText(el) + "`";
            case "del", "s", "strike" -> "~~" + inlineText(el) + "~~";
            case "a" -> {
                String href = el.getAttribute("href");
                yield "[" + inlineText(el) + "](" + href + ")";
            }
            case "br" -> "\n";
            case "ac:link" -> convertLink(el);
            case "ac:image" -> convertImage(el);
            case "span" -> inlineText(el);
            default -> inlineText(el);
        };
    }

    private String convertLink(Element linkEl) {
        NodeList pageRefs = linkEl.getElementsByTagName("ri:page");
        if (pageRefs.getLength() > 0) {
            Element pageRef = (Element) pageRefs.item(0);
            String title = pageRef.getAttribute("ri:content-title");
            String display = linkBodyText(linkEl);
            String label = !display.isBlank() ? display : title;
            return "[" + label + "](confluence://page/" + title + ")";
        }
        NodeList attachmentRefs = linkEl.getElementsByTagName("ri:attachment");
        if (attachmentRefs.getLength() > 0) {
            Element attachmentRef = (Element) attachmentRefs.item(0);
            String filename = attachmentRef.getAttribute("ri:filename");
            String display = linkBodyText(linkEl);
            String label = !display.isBlank() ? display : filename;
            return "[" + label + "](confluence://attachment/" + filename + ")";
        }
        return inlineText(linkEl);
    }

    private String linkBodyText(Element linkEl) {
        NodeList bodies = linkEl.getElementsByTagName("ac:link-body");
        if (bodies.getLength() > 0) {
            return inlineText((Element) bodies.item(0));
        }
        NodeList plainBodies = linkEl.getElementsByTagName("ac:plain-text-link-body");
        if (plainBodies.getLength() > 0) {
            return textContentOf((Element) plainBodies.item(0));
        }
        return "";
    }

    private String convertImage(Element imageEl) {
        NodeList urlRefs = imageEl.getElementsByTagName("ri:url");
        if (urlRefs.getLength() > 0) {
            String url = ((Element) urlRefs.item(0)).getAttribute("ri:value");
            return "![](" + url + ")";
        }
        NodeList attachmentRefs = imageEl.getElementsByTagName("ri:attachment");
        if (attachmentRefs.getLength() > 0) {
            String filename = ((Element) attachmentRefs.item(0)).getAttribute("ri:filename");
            return "![](attachment:" + filename + ")";
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private String firstChildText(Element parent, String tagName) {
        NodeList matches = parent.getElementsByTagName(tagName);
        if (matches.getLength() == 0) {
            return null;
        }
        return inlineText((Element) matches.item(0));
    }

    private String textContentOf(Element el) {
        return el.getTextContent();
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
