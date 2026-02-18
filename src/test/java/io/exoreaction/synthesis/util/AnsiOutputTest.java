package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AnsiOutput — color formatting, disabled/enabled mode,
 * semantic methods, and null/empty text handling.
 */
class AnsiOutputTest {

    private boolean originalEnabled;

    @BeforeEach
    void saveState() {
        originalEnabled = AnsiOutput.isEnabled();
    }

    @AfterEach
    void restoreState() {
        AnsiOutput.setEnabled(originalEnabled);
    }

    // --- setEnabled / isEnabled ---

    @Test
    void setEnabled_true_isEnabledReturnsTrue() {
        AnsiOutput.setEnabled(true);
        assertTrue(AnsiOutput.isEnabled());
    }

    @Test
    void setEnabled_false_isEnabledReturnsFalse() {
        AnsiOutput.setEnabled(false);
        assertFalse(AnsiOutput.isEnabled());
    }

    // --- disabled mode: text passes through unchanged ---

    @Test
    void disabled_red_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("hello", AnsiOutput.red("hello"));
    }

    @Test
    void disabled_green_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("hello", AnsiOutput.green("hello"));
    }

    @Test
    void disabled_yellow_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("test", AnsiOutput.yellow("test"));
    }

    @Test
    void disabled_blue_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("info", AnsiOutput.blue("info"));
    }

    @Test
    void disabled_magenta_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("text", AnsiOutput.magenta("text"));
    }

    @Test
    void disabled_cyan_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("text", AnsiOutput.cyan("text"));
    }

    @Test
    void disabled_bold_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("bold text", AnsiOutput.bold("bold text"));
    }

    @Test
    void disabled_dim_returnsTextUnchanged() {
        AnsiOutput.setEnabled(false);
        assertEquals("dim text", AnsiOutput.dim("dim text"));
    }

    // --- enabled mode: text is wrapped with ANSI codes ---

    @Test
    void enabled_red_containsAnsiCodes() {
        AnsiOutput.setEnabled(true);
        String result = AnsiOutput.red("error");
        assertTrue(result.contains("error"), "Should contain original text");
        assertTrue(result.contains("\u001B["), "Should contain ANSI escape");
        assertTrue(result.contains("\u001B[0m"), "Should contain reset code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "world", "test message", ""})
    void enabled_green_textIsContained(String text) {
        AnsiOutput.setEnabled(true);
        String result = AnsiOutput.green(text);
        assertTrue(result.contains(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "world", "test"})
    void enabled_allColorMethods_containOriginalText(String text) {
        AnsiOutput.setEnabled(true);
        assertTrue(AnsiOutput.red(text).contains(text));
        assertTrue(AnsiOutput.green(text).contains(text));
        assertTrue(AnsiOutput.yellow(text).contains(text));
        assertTrue(AnsiOutput.blue(text).contains(text));
        assertTrue(AnsiOutput.magenta(text).contains(text));
        assertTrue(AnsiOutput.cyan(text).contains(text));
        assertTrue(AnsiOutput.bold(text).contains(text));
        assertTrue(AnsiOutput.dim(text).contains(text));
    }

    // --- semantic methods delegate to color methods ---

    @Test
    void disabled_success_equalsInput() {
        AnsiOutput.setEnabled(false);
        assertEquals("ok", AnsiOutput.success("ok"));
    }

    @Test
    void disabled_error_equalsInput() {
        AnsiOutput.setEnabled(false);
        assertEquals("fail", AnsiOutput.error("fail"));
    }

    @Test
    void disabled_warning_equalsInput() {
        AnsiOutput.setEnabled(false);
        assertEquals("warn", AnsiOutput.warning("warn"));
    }

    @Test
    void disabled_info_equalsInput() {
        AnsiOutput.setEnabled(false);
        assertEquals("note", AnsiOutput.info("note"));
    }

    @Test
    void disabled_highlight_equalsInput() {
        AnsiOutput.setEnabled(false);
        assertEquals("highlighted", AnsiOutput.highlight("highlighted"));
    }

    @Test
    void disabled_header_equalsInput() {
        AnsiOutput.setEnabled(false);
        assertEquals("Header", AnsiOutput.header("Header"));
    }

    // --- enabled: semantic methods return different from input ---

    @Test
    void enabled_success_wrapsWithAnsi() {
        AnsiOutput.setEnabled(true);
        String result = AnsiOutput.success("done");
        assertTrue(result.contains("done"));
        assertTrue(result.length() > "done".length(), "Should be longer due to ANSI codes");
    }

    // --- print methods: don't throw ---

    @Test
    void printSuccess_doesNotThrow() {
        assertDoesNotThrow(() -> AnsiOutput.printSuccess("success message"));
    }

    @Test
    void printError_doesNotThrow() {
        assertDoesNotThrow(() -> AnsiOutput.printError("error message"));
    }

    @Test
    void printWarning_doesNotThrow() {
        assertDoesNotThrow(() -> AnsiOutput.printWarning("warning message"));
    }

    @Test
    void printInfo_doesNotThrow() {
        assertDoesNotThrow(() -> AnsiOutput.printInfo("info message"));
    }

    @Test
    void printHeader_doesNotThrow() {
        assertDoesNotThrow(() -> AnsiOutput.printHeader("My Header"));
    }
}
