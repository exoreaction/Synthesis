package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotionBlockToMarkdown} — Notion block JSON to Markdown conversion.
 */
class NotionBlockToMarkdownTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private NotionBlockToMarkdown converter;

    @BeforeEach
    void setUp() {
        converter = new NotionBlockToMarkdown();
    }

    // -----------------------------------------------------------------------
    // Block type tests
    // -----------------------------------------------------------------------

    @Test
    void paragraph_convertsToPlainText() {
        JsonNode block = paragraphBlock("Hello world");
        String result = converter.convertBlock(block, 0);
        assertEquals("Hello world\n", result);
    }

    @Test
    void heading1_convertsToH1() {
        JsonNode block = headingBlock("heading_1", "Title");
        String result = converter.convertBlock(block, 0);
        assertEquals("# Title\n", result);
    }

    @Test
    void heading2_convertsToH2() {
        JsonNode block = headingBlock("heading_2", "Subtitle");
        String result = converter.convertBlock(block, 0);
        assertEquals("## Subtitle\n", result);
    }

    @Test
    void heading3_convertsToH3() {
        JsonNode block = headingBlock("heading_3", "Section");
        String result = converter.convertBlock(block, 0);
        assertEquals("### Section\n", result);
    }

    @Test
    void bulletedListItem_convertsToDash() {
        JsonNode block = listItemBlock("bulleted_list_item", "Item one");
        String result = converter.convertBlock(block, 0);
        assertEquals("- Item one\n", result);
    }

    @Test
    void numberedListItem_convertsToNumbered() {
        JsonNode block = listItemBlock("numbered_list_item", "Step one");
        String result = converter.convertBlock(block, 0);
        assertEquals("1. Step one\n", result);
    }

    @Test
    void bulletedListItem_indentedNesting() {
        JsonNode block = listItemBlock("bulleted_list_item", "Nested item");
        String result = converter.convertBlock(block, 1);
        assertEquals("    - Nested item\n", result);
    }

    @Test
    void todoUnchecked_convertsToCheckbox() {
        JsonNode block = todoBlock("Buy milk", false);
        String result = converter.convertBlock(block, 0);
        assertEquals("- [ ] Buy milk\n", result);
    }

    @Test
    void todoChecked_convertsToCheckedCheckbox() {
        JsonNode block = todoBlock("Done task", true);
        String result = converter.convertBlock(block, 0);
        assertEquals("- [x] Done task\n", result);
    }

    @Test
    void codeBlock_convertsToFencedCode() {
        JsonNode block = codeBlock("print('hello')", "python");
        String result = converter.convertBlock(block, 0);
        assertEquals("```python\nprint('hello')\n```\n", result);
    }

    @Test
    void codeBlock_noLanguage() {
        JsonNode block = codeBlock("some code", "");
        String result = converter.convertBlock(block, 0);
        assertEquals("```\nsome code\n```\n", result);
    }

    @Test
    void quote_convertsToBlockquote() {
        JsonNode block = quoteBlock("A wise saying");
        String result = converter.convertBlock(block, 0);
        assertEquals("> A wise saying\n", result);
    }

    @Test
    void callout_convertsToBlockquoteWithEmoji() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "callout");
        ObjectNode data = block.putObject("callout");
        data.set("rich_text", richTextArray("Important note"));
        ObjectNode icon = data.putObject("icon");
        icon.put("emoji", "\u26a0\ufe0f");

        String result = converter.convertBlock(block, 0);
        assertEquals("> \u26a0\ufe0f Important note\n", result);
    }

    @Test
    void divider_convertsToHorizontalRule() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "divider");
        String result = converter.convertBlock(block, 0);
        assertEquals("---\n", result);
    }

    @Test
    void image_externalUrl() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "image");
        ObjectNode image = block.putObject("image");
        image.put("type", "external");
        ObjectNode external = image.putObject("external");
        external.put("url", "https://example.com/img.png");
        image.set("caption", richTextArray("A photo"));

        String result = converter.convertBlock(block, 0);
        assertEquals("![A photo](https://example.com/img.png)\n", result);
    }

    @Test
    void image_fileUrl() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "image");
        ObjectNode image = block.putObject("image");
        image.put("type", "file");
        ObjectNode file = image.putObject("file");
        file.put("url", "https://s3.notion.so/abc.png");
        image.set("caption", richTextArray(""));

        String result = converter.convertBlock(block, 0);
        assertEquals("![](https://s3.notion.so/abc.png)\n", result);
    }

    @Test
    void bookmark_convertsToLink() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "bookmark");
        ObjectNode bookmark = block.putObject("bookmark");
        bookmark.put("url", "https://example.com");

        String result = converter.convertBlock(block, 0);
        assertEquals("[https://example.com](https://example.com)\n", result);
    }

    @Test
    void childPage_convertsToNotionLink() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "child_page");
        block.put("id", "abc-123");
        ObjectNode data = block.putObject("child_page");
        data.put("title", "Sub Page");

        String result = converter.convertBlock(block, 0);
        assertEquals("[Sub Page](notion://abc-123)\n", result);
    }

    @Test
    void childDatabase_convertsToNotionLink() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "child_database");
        block.put("id", "db-456");
        ObjectNode data = block.putObject("child_database");
        data.put("title", "Tasks DB");

        String result = converter.convertBlock(block, 0);
        assertEquals("[Tasks DB](notion://db-456)\n", result);
    }

    @Test
    void table_convertsToMarkdownTable() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "table");
        block.putObject("table");

        ArrayNode rows = block.putArray("children");

        // Header row
        ObjectNode row1 = rows.addObject();
        row1.put("type", "table_row");
        ObjectNode tr1 = row1.putObject("table_row");
        ArrayNode cells1 = tr1.putArray("cells");
        cells1.add(richTextArray("Name"));
        cells1.add(richTextArray("Age"));

        // Data row
        ObjectNode row2 = rows.addObject();
        row2.put("type", "table_row");
        ObjectNode tr2 = row2.putObject("table_row");
        ArrayNode cells2 = tr2.putArray("cells");
        cells2.add(richTextArray("Alice"));
        cells2.add(richTextArray("30"));

        String result = converter.convertBlock(block, 0);
        String expected = "| Name | Age |\n| --- | --- |\n| Alice | 30 |\n";
        assertEquals(expected, result);
    }

    @Test
    void unknownBlockType_convertsToHtmlComment() {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "synced_block");

        String result = converter.convertBlock(block, 0);
        assertEquals("<!-- Notion block: synced_block -->\n", result);
    }

    // -----------------------------------------------------------------------
    // Rich text annotation tests
    // -----------------------------------------------------------------------

    @Test
    void richText_bold() {
        JsonNode rt = annotatedRichText("strong", true, false, false, false, null);
        String result = converter.convertRichText(rt);
        assertEquals("**strong**", result);
    }

    @Test
    void richText_italic() {
        JsonNode rt = annotatedRichText("emphasis", false, true, false, false, null);
        String result = converter.convertRichText(rt);
        assertEquals("*emphasis*", result);
    }

    @Test
    void richText_boldAndItalic() {
        JsonNode rt = annotatedRichText("both", true, true, false, false, null);
        String result = converter.convertRichText(rt);
        assertEquals("***both***", result);
    }

    @Test
    void richText_strikethrough() {
        JsonNode rt = annotatedRichText("deleted", false, false, true, false, null);
        String result = converter.convertRichText(rt);
        assertEquals("~~deleted~~", result);
    }

    @Test
    void richText_inlineCode() {
        JsonNode rt = annotatedRichText("code()", false, false, false, true, null);
        String result = converter.convertRichText(rt);
        assertEquals("`code()`", result);
    }

    @Test
    void richText_link() {
        JsonNode rt = annotatedRichText("click here", false, false, false, false, "https://example.com");
        String result = converter.convertRichText(rt);
        assertEquals("[click here](https://example.com)", result);
    }

    @Test
    void richText_boldWithLink() {
        JsonNode rt = annotatedRichText("bold link", true, false, false, false, "https://example.com");
        String result = converter.convertRichText(rt);
        assertEquals("[**bold link**](https://example.com)", result);
    }

    // -----------------------------------------------------------------------
    // convert() integration tests
    // -----------------------------------------------------------------------

    @Test
    void convert_emptyList_returnsEmpty() {
        String result = converter.convert(List.of());
        assertEquals("", result);
    }

    @Test
    void convert_nullList_returnsEmpty() {
        String result = converter.convert(null);
        assertEquals("", result);
    }

    @Test
    void convert_multipleBlocks_separatedByNewlines() {
        List<JsonNode> blocks = List.of(
                headingBlock("heading_1", "Title"),
                paragraphBlock("Some text"),
                paragraphBlock("More text")
        );
        String result = converter.convert(blocks);
        assertTrue(result.contains("# Title"));
        assertTrue(result.contains("Some text"));
        assertTrue(result.contains("More text"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ArrayNode richTextArray(String text) {
        ArrayNode arr = JSON.createArrayNode();
        if (text != null && !text.isEmpty()) {
            ObjectNode segment = arr.addObject();
            segment.put("plain_text", text);
            ObjectNode annotations = segment.putObject("annotations");
            annotations.put("bold", false);
            annotations.put("italic", false);
            annotations.put("strikethrough", false);
            annotations.put("code", false);
        }
        return arr;
    }

    private JsonNode annotatedRichText(String text, boolean bold, boolean italic,
                                       boolean strikethrough, boolean code, String href) {
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode segment = arr.addObject();
        segment.put("plain_text", text);
        if (href != null) {
            segment.put("href", href);
        }
        ObjectNode annotations = segment.putObject("annotations");
        annotations.put("bold", bold);
        annotations.put("italic", italic);
        annotations.put("strikethrough", strikethrough);
        annotations.put("code", code);
        return arr;
    }

    private JsonNode paragraphBlock(String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "paragraph");
        ObjectNode para = block.putObject("paragraph");
        para.set("rich_text", richTextArray(text));
        return block;
    }

    private JsonNode headingBlock(String type, String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", type);
        ObjectNode heading = block.putObject(type);
        heading.set("rich_text", richTextArray(text));
        return block;
    }

    private JsonNode listItemBlock(String type, String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", type);
        ObjectNode item = block.putObject(type);
        item.set("rich_text", richTextArray(text));
        return block;
    }

    private JsonNode todoBlock(String text, boolean checked) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "to_do");
        ObjectNode todo = block.putObject("to_do");
        todo.set("rich_text", richTextArray(text));
        todo.put("checked", checked);
        return block;
    }

    private JsonNode codeBlock(String text, String language) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "code");
        ObjectNode code = block.putObject("code");
        code.set("rich_text", richTextArray(text));
        code.put("language", language);
        return block;
    }

    private JsonNode quoteBlock(String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "quote");
        ObjectNode quote = block.putObject("quote");
        quote.set("rich_text", richTextArray(text));
        return block;
    }
}
