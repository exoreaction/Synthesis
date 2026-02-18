package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchPassResult record — construction, token estimation.
 */
class ResearchPassResultTest {

    @Test
    void construction_storesAllFields() {
        ResearchPassResult pass = new ResearchPassResult("architecture", "content here", 42);
        assertEquals("architecture", pass.passName());
        assertEquals("content here", pass.content());
        assertEquals(42, pass.tokenCount());
    }

    @Test
    void estimateTokens_null_returnsZero() {
        assertEquals(0, ResearchPassResult.estimateTokens(null));
    }

    @Test
    void estimateTokens_empty_returnsZero() {
        assertEquals(0, ResearchPassResult.estimateTokens(""));
    }

    @ParameterizedTest
    @CsvSource({
        "1234, 308",   // 1234 / 4 = 308
        "4000, 1000",
        "1,    0",     // 1 / 4 = 0
        "8,    2",     // 8 / 4 = 2
    })
    void estimateTokens_dividesByFour(int length, int expectedTokens) {
        String content = "a".repeat(length);
        assertEquals(expectedTokens, ResearchPassResult.estimateTokens(content));
    }

    @Test
    void estimateTokens_positiveForNonEmptyContent() {
        int tokens = ResearchPassResult.estimateTokens("Hello, world! This is some text.");
        assertTrue(tokens > 0, "Non-empty content should have positive token estimate");
    }

    @Test
    void equality_sameFields_equal() {
        ResearchPassResult a = new ResearchPassResult("security", "findings", 100);
        ResearchPassResult b = new ResearchPassResult("security", "findings", 100);
        assertEquals(a, b);
    }

    @Test
    void equality_differentPassName_notEqual() {
        ResearchPassResult a = new ResearchPassResult("security", "findings", 100);
        ResearchPassResult b = new ResearchPassResult("quality", "findings", 100);
        assertNotEquals(a, b);
    }
}
