package io.exoreaction.synthesis.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeClient utility methods.
 * Note: Actual API calls are not tested (require API key).
 */
class ClaudeClientTest {

    @Test
    void testIsVisionSupported() {
        assertTrue(ClaudeClient.isVisionSupported(".jpg"));
        assertTrue(ClaudeClient.isVisionSupported(".jpeg"));
        assertTrue(ClaudeClient.isVisionSupported(".png"));
        assertTrue(ClaudeClient.isVisionSupported(".gif"));
        assertTrue(ClaudeClient.isVisionSupported(".webp"));
        assertTrue(ClaudeClient.isVisionSupported(".JPG"));
        assertTrue(ClaudeClient.isVisionSupported(".PNG"));

        assertFalse(ClaudeClient.isVisionSupported(".svg"));
        assertFalse(ClaudeClient.isVisionSupported(".tiff"));
        assertFalse(ClaudeClient.isVisionSupported(".bmp"));
        assertFalse(ClaudeClient.isVisionSupported(".heic"));
        assertFalse(ClaudeClient.isVisionSupported(".pdf"));
    }

    @Test
    void testEstimateVisionCostSmallImage() {
        double cost = ClaudeClient.estimateVisionCost(100_000); // 100 KB
        assertEquals(0.01, cost, 0.001);
    }

    @Test
    void testEstimateVisionCostMediumImage() {
        double cost = ClaudeClient.estimateVisionCost(2_000_000); // 2 MB
        assertEquals(0.02, cost, 0.001);
    }

    @Test
    void testEstimateVisionCostLargeImage() {
        double cost = ClaudeClient.estimateVisionCost(8_000_000); // 8 MB
        assertEquals(0.04, cost, 0.001);
    }

    @Test
    void testEstimateVisionCostBoundaries() {
        // Under 1 MB -- should be small
        assertEquals(0.01, ClaudeClient.estimateVisionCost(999_999), 0.001);
        // At exactly 1 MB -- boundary is > 1_000_000, so 1M is still small
        assertEquals(0.01, ClaudeClient.estimateVisionCost(1_000_000), 0.001);
        // Just over 1 MB -- should be medium
        assertEquals(0.02, ClaudeClient.estimateVisionCost(1_000_001), 0.001);
        // Under 5 MB -- should be medium
        assertEquals(0.02, ClaudeClient.estimateVisionCost(4_999_999), 0.001);
        // At exactly 5 MB -- boundary is > 5_000_000, so 5M is still medium
        assertEquals(0.02, ClaudeClient.estimateVisionCost(5_000_000), 0.001);
        // Over 5 MB -- should be large
        assertEquals(0.04, ClaudeClient.estimateVisionCost(5_000_001), 0.001);
    }
}
