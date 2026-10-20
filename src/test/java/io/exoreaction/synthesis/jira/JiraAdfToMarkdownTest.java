package io.exoreaction.synthesis.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JiraAdfToMarkdown} — Atlassian Document Format to Markdown
 * conversion, covering representative ADF snippets as they appear in real Jira
 * issue {@code description} and comment {@code body} fields.
 */
class JiraAdfToMarkdownTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JiraAdfToMarkdown converter = new JiraAdfToMarkdown();

    private JsonNode parse(String json) throws Exception {
        return JSON.readTree(json);
    }

    // -----------------------------------------------------------------------
    // 1. null/empty doc
    // -----------------------------------------------------------------------

    @Test
    void convert_nullDoc_returnsEmptyString() {
        assertEquals("", converter.convert(null));
    }

    // -----------------------------------------------------------------------
    // 2. simple paragraph with plain text
    // -----------------------------------------------------------------------

    @Test
    void convert_simpleParagraph_returnsText() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "paragraph", "content": [
                            {"type": "text", "text": "This is a simple description."}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("This is a simple description.", md);
    }

    // -----------------------------------------------------------------------
    // 3. text marks: bold, italic, strike, code, link
    // -----------------------------------------------------------------------

    @Test
    void convert_textMarks_appliesFormatting() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "paragraph", "content": [
                            {"type": "text", "text": "bold", "marks": [{"type": "strong"}]},
                            {"type": "text", "text": " and "},
                            {"type": "text", "text": "italic", "marks": [{"type": "em"}]},
                            {"type": "text", "text": " and "},
                            {"type": "text", "text": "struck", "marks": [{"type": "strike"}]},
                            {"type": "text", "text": " and "},
                            {"type": "text", "text": "code", "marks": [{"type": "code"}]},
                            {"type": "text", "text": " and a "},
                            {"type": "text", "text": "link", "marks": [
                                {"type": "link", "attrs": {"href": "https://example.com"}}
                            ]}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("**bold** and *italic* and ~~struck~~ and `code` and a [link](https://example.com)", md);
    }

    // -----------------------------------------------------------------------
    // 4. heading levels
    // -----------------------------------------------------------------------

    @Test
    void convert_heading_rendersHashPrefix() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "heading", "attrs": {"level": 2}, "content": [
                            {"type": "text", "text": "Root Cause"}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("## Root Cause", md);
    }

    // -----------------------------------------------------------------------
    // 5. bullet list
    // -----------------------------------------------------------------------

    @Test
    void convert_bulletList_rendersDashItems() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "bulletList", "content": [
                            {"type": "listItem", "content": [
                                {"type": "paragraph", "content": [{"type": "text", "text": "First"}]}
                            ]},
                            {"type": "listItem", "content": [
                                {"type": "paragraph", "content": [{"type": "text", "text": "Second"}]}
                            ]}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("- First\n- Second", md);
    }

    // -----------------------------------------------------------------------
    // 6. ordered list
    // -----------------------------------------------------------------------

    @Test
    void convert_orderedList_rendersNumberedItems() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "orderedList", "content": [
                            {"type": "listItem", "content": [
                                {"type": "paragraph", "content": [{"type": "text", "text": "Step one"}]}
                            ]},
                            {"type": "listItem", "content": [
                                {"type": "paragraph", "content": [{"type": "text", "text": "Step two"}]}
                            ]}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("1. Step one\n2. Step two", md);
    }

    // -----------------------------------------------------------------------
    // 7. code block with language
    // -----------------------------------------------------------------------

    @Test
    void convert_codeBlock_rendersFencedCode() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "codeBlock", "attrs": {"language": "java"}, "content": [
                            {"type": "text", "text": "System.out.println(\\"hi\\");"}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("```java\nSystem.out.println(\"hi\");\n```", md);
    }

    // -----------------------------------------------------------------------
    // 8. blockquote
    // -----------------------------------------------------------------------

    @Test
    void convert_blockquote_prefixesWithAngleBracket() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "blockquote", "content": [
                            {"type": "paragraph", "content": [{"type": "text", "text": "Quoted text"}]}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("> Quoted text", md);
    }

    // -----------------------------------------------------------------------
    // 9. rule (divider)
    // -----------------------------------------------------------------------

    @Test
    void convert_rule_rendersDivider() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [{"type": "rule"}]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("---", md);
    }

    // -----------------------------------------------------------------------
    // 10. mention
    // -----------------------------------------------------------------------

    @Test
    void convert_mention_rendersDisplayName() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "paragraph", "content": [
                            {"type": "text", "text": "Assigned to "},
                            {"type": "mention", "attrs": {"id": "abc123", "text": "@Alice"}}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("Assigned to @Alice", md);
    }

    // -----------------------------------------------------------------------
    // 11. table
    // -----------------------------------------------------------------------

    @Test
    void convert_table_rendersMarkdownTable() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "table", "content": [
                            {"type": "tableRow", "content": [
                                {"type": "tableHeader", "content": [
                                    {"type": "paragraph", "content": [{"type": "text", "text": "Field"}]}
                                ]},
                                {"type": "tableHeader", "content": [
                                    {"type": "paragraph", "content": [{"type": "text", "text": "Value"}]}
                                ]}
                            ]},
                            {"type": "tableRow", "content": [
                                {"type": "tableCell", "content": [
                                    {"type": "paragraph", "content": [{"type": "text", "text": "Status"}]}
                                ]},
                                {"type": "tableCell", "content": [
                                    {"type": "paragraph", "content": [{"type": "text", "text": "Done"}]}
                                ]}
                            ]}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertTrue(md.contains("| Field | Value |"));
        assertTrue(md.contains("| --- | --- |"));
        assertTrue(md.contains("| Status | Done |"));
    }

    // -----------------------------------------------------------------------
    // 12. unknown node type falls back to HTML comment
    // -----------------------------------------------------------------------

    @Test
    void convert_unknownNodeType_rendersHtmlComment() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "panel", "attrs": {"panelType": "info"}, "content": [
                            {"type": "paragraph", "content": [{"type": "text", "text": "Note"}]}
                        ]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertTrue(md.contains("<!-- ADF node: panel -->"));
    }

    // -----------------------------------------------------------------------
    // 13. multi-paragraph document joins with a blank line between blocks
    // -----------------------------------------------------------------------

    @Test
    void convert_multipleParagraphs_joinedWithBlankLine() throws Exception {
        JsonNode doc = parse("""
                {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {"type": "paragraph", "content": [{"type": "text", "text": "First paragraph."}]},
                        {"type": "paragraph", "content": [{"type": "text", "text": "Second paragraph."}]}
                    ]
                }
                """);

        String md = converter.convert(doc);
        assertEquals("First paragraph.\n\nSecond paragraph.", md);
    }
}
