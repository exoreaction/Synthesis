package io.exoreaction.synthesis.confluence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfluenceContentToMarkdown} — Confluence storage-format XHTML to
 * Markdown conversion.
 *
 * <p>Fixtures are representative storage-format snippets based on Atlassian's published
 * Confluence Cloud storage format documentation (headings, lists, tables, and the
 * {@code ac:structured-macro}/{@code ac:link}/{@code ac:image}/{@code ac:task-list}
 * constructs) — this environment has no live Confluence instance to verify against
 * (per RFC #541, credentials are not yet provisioned), so these are not captured from
 * a real API response. Flagged here rather than silently assumed correct.
 */
class ConfluenceContentToMarkdownTest {

    private ConfluenceContentToMarkdown converter;

    @BeforeEach
    void setUp() {
        converter = new ConfluenceContentToMarkdown();
    }

    // -----------------------------------------------------------------------
    // Null / empty input
    // -----------------------------------------------------------------------

    @Test
    void convert_nullInput_returnsEmpty() {
        assertEquals("", converter.convert(null));
    }

    @Test
    void convert_blankInput_returnsEmpty() {
        assertEquals("", converter.convert("   "));
    }

    // -----------------------------------------------------------------------
    // Paragraphs and headings
    // -----------------------------------------------------------------------

    @Test
    void paragraph_convertsToPlainText() {
        assertEquals("Hello world\n", converter.convert("<p>Hello world</p>"));
    }

    @Test
    void heading1_convertsToH1() {
        assertEquals("# Title\n", converter.convert("<h1>Title</h1>"));
    }

    @Test
    void heading2_convertsToH2() {
        assertEquals("## Subtitle\n", converter.convert("<h2>Subtitle</h2>"));
    }

    @Test
    void heading3_convertsToH3() {
        assertEquals("### Section\n", converter.convert("<h3>Section</h3>"));
    }

    // -----------------------------------------------------------------------
    // Lists
    // -----------------------------------------------------------------------

    @Test
    void bulletedList_convertsToDash() {
        assertEquals("- Item one\n", converter.convert("<ul><li>Item one</li></ul>"));
    }

    @Test
    void numberedList_convertsToNumbered() {
        assertEquals("1. Step one\n", converter.convert("<ol><li>Step one</li></ol>"));
    }

    @Test
    void nestedBulletedList_indentsChild() {
        String xhtml = "<ul><li>Parent<ul><li>Child</li></ul></li></ul>";
        assertEquals("- Parent\n    - Child\n", converter.convert(xhtml));
    }

    @Test
    void multipleListItems_allConverted() {
        String xhtml = "<ul><li>First</li><li>Second</li><li>Third</li></ul>";
        String result = converter.convert(xhtml);
        assertEquals("- First\n- Second\n- Third\n", result);
    }

    // -----------------------------------------------------------------------
    // Inline formatting
    // -----------------------------------------------------------------------

    @Test
    void bold_convertsToDoubleAsterisk() {
        assertEquals("Hello **world**\n", converter.convert("<p>Hello <strong>world</strong></p>"));
    }

    @Test
    void italic_convertsToSingleAsterisk() {
        assertEquals("*emphasis*\n", converter.convert("<p><em>emphasis</em></p>"));
    }

    @Test
    void inlineCode_convertsToBackticks() {
        assertEquals("Use `foo()` here\n", converter.convert("<p>Use <code>foo()</code> here</p>"));
    }

    @Test
    void strikethrough_convertsToDoubleTilde() {
        assertEquals("~~deleted~~\n", converter.convert("<p><del>deleted</del></p>"));
    }

    @Test
    void link_convertsToMarkdownLink() {
        assertEquals("[click here](https://example.com)\n",
                converter.convert("<p><a href=\"https://example.com\">click here</a></p>"));
    }

    // -----------------------------------------------------------------------
    // Structural elements
    // -----------------------------------------------------------------------

    @Test
    void horizontalRule_convertsToDashes() {
        assertEquals("---\n", converter.convert("<hr/>"));
    }

    @Test
    void blockquote_convertsToMarkdownQuote() {
        assertEquals("> A wise saying\n",
                converter.convert("<blockquote><p>A wise saying</p></blockquote>"));
    }

    @Test
    void table_convertsToMarkdownTable() {
        String xhtml = """
                <table>
                  <tbody>
                    <tr><th>Name</th><th>Age</th></tr>
                    <tr><td>Alice</td><td>30</td></tr>
                  </tbody>
                </table>
                """;
        String expected = "| Name | Age |\n| --- | --- |\n| Alice | 30 |\n";
        assertEquals(expected, converter.convert(xhtml));
    }

    // -----------------------------------------------------------------------
    // ac:structured-macro
    // -----------------------------------------------------------------------

    @Test
    void codeMacro_withLanguage_convertsToFencedCodeBlock() {
        String xhtml = "<ac:structured-macro ac:name=\"code\">"
                + "<ac:parameter ac:name=\"language\">python</ac:parameter>"
                + "<ac:plain-text-body><![CDATA[print('hello')]]></ac:plain-text-body>"
                + "</ac:structured-macro>";
        assertEquals("```python\nprint('hello')\n```\n", converter.convert(xhtml));
    }

    @Test
    void codeMacro_noLanguage_convertsToPlainFencedBlock() {
        String xhtml = "<ac:structured-macro ac:name=\"code\">"
                + "<ac:plain-text-body><![CDATA[some code]]></ac:plain-text-body>"
                + "</ac:structured-macro>";
        assertEquals("```\nsome code\n```\n", converter.convert(xhtml));
    }

    @Test
    void infoMacro_convertsToLabeledBlockquote() {
        String xhtml = "<ac:structured-macro ac:name=\"info\">"
                + "<ac:rich-text-body><p>Important note</p></ac:rich-text-body>"
                + "</ac:structured-macro>";
        assertEquals("> ℹ️ Important note\n", converter.convert(xhtml));
    }

    @Test
    void warningMacro_convertsToLabeledBlockquote() {
        String xhtml = "<ac:structured-macro ac:name=\"warning\">"
                + "<ac:rich-text-body><p>Be careful</p></ac:rich-text-body>"
                + "</ac:structured-macro>";
        assertEquals("> ⚠️ Be careful\n", converter.convert(xhtml));
    }

    @Test
    void tipMacro_convertsToLabeledBlockquote() {
        String xhtml = "<ac:structured-macro ac:name=\"tip\">"
                + "<ac:rich-text-body><p>Try this</p></ac:rich-text-body>"
                + "</ac:structured-macro>";
        assertEquals("> 💡 Try this\n", converter.convert(xhtml));
    }

    @Test
    void unknownMacro_convertsToHtmlComment() {
        String xhtml = "<ac:structured-macro ac:name=\"jira\"></ac:structured-macro>";
        assertEquals("<!-- Confluence macro: jira -->\n", converter.convert(xhtml));
    }

    // -----------------------------------------------------------------------
    // ac:link / ac:image
    // -----------------------------------------------------------------------

    @Test
    void pageLink_convertsToConfluenceSchemeLink() {
        String xhtml = "<p><ac:link><ri:page ri:content-title=\"Getting Started\" /></ac:link></p>";
        assertEquals("[Getting Started](confluence://page/Getting Started)\n", converter.convert(xhtml));
    }

    @Test
    void pageLink_withDisplayText_usesDisplayTextAsLabel() {
        String xhtml = "<p><ac:link><ri:page ri:content-title=\"Getting Started\" />"
                + "<ac:link-body>Start here</ac:link-body></ac:link></p>";
        assertEquals("[Start here](confluence://page/Getting Started)\n", converter.convert(xhtml));
    }

    @Test
    void image_externalUrl_convertsToMarkdownImage() {
        String xhtml = "<p><ac:image><ri:url ri:value=\"https://example.com/img.png\" /></ac:image></p>";
        assertEquals("![](https://example.com/img.png)\n", converter.convert(xhtml));
    }

    @Test
    void image_attachment_convertsToAttachmentReference() {
        String xhtml = "<p><ac:image><ri:attachment ri:filename=\"diagram.png\" /></ac:image></p>";
        assertEquals("![](attachment:diagram.png)\n", converter.convert(xhtml));
    }

    // -----------------------------------------------------------------------
    // ac:task-list
    // -----------------------------------------------------------------------

    @Test
    void taskList_convertsToCheckboxes() {
        String xhtml = "<ac:task-list>"
                + "<ac:task><ac:task-id>1</ac:task-id><ac:task-status>incomplete</ac:task-status>"
                + "<ac:task-body>Buy milk</ac:task-body></ac:task>"
                + "<ac:task><ac:task-id>2</ac:task-id><ac:task-status>complete</ac:task-status>"
                + "<ac:task-body>Done task</ac:task-body></ac:task>"
                + "</ac:task-list>";
        assertEquals("- [ ] Buy milk\n- [x] Done task\n", converter.convert(xhtml));
    }

    // -----------------------------------------------------------------------
    // Multiple top-level blocks
    // -----------------------------------------------------------------------

    @Test
    void multipleBlocks_allConvertedInOrder() {
        String xhtml = "<h1>Title</h1><p>Some text</p><p>More text</p>";
        String result = converter.convert(xhtml);
        assertTrue(result.contains("# Title"));
        assertTrue(result.contains("Some text"));
        assertTrue(result.contains("More text"));
    }

    // -----------------------------------------------------------------------
    // Malformed content
    // -----------------------------------------------------------------------

    @Test
    void malformedXhtml_fallsBackToComment_doesNotThrow() {
        String xhtml = "<p>Unclosed paragraph";
        String result = assertDoesNotThrow(() -> converter.convert(xhtml));
        assertTrue(result.contains("Confluence content"));
    }
}
