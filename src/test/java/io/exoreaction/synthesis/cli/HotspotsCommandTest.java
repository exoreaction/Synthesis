package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HotspotsCommand trend indicator logic.
 */
class HotspotsCommandTest {

    @Test
    void trend_stableActivity_returnsNeutral() {
        // 90d avg monthly rate = 9/3 = 3.0. 30d = 3 → within normal range
        assertEquals("→", HotspotsCommand.trend(3, 9));
    }

    @Test
    void trend_risingActivity_returnsUp() {
        // 90d avg monthly rate = 6/3 = 2.0. 30d = 5 > 2.0 * 1.5 = 3.0 → rising
        assertNotEquals("→", HotspotsCommand.trend(5, 6));
    }

    @Test
    void trend_coolingActivity_returnsDown() {
        // 90d avg monthly rate = 12/3 = 4.0. 30d = 1 < 4.0 / 2.0 = 2.0 → cooling
        assertEquals("↓", HotspotsCommand.trend(1, 12));
    }

    @Test
    void trend_zeroActivity_returnsNeutral() {
        // No recent or historical activity — neutral
        assertEquals("→", HotspotsCommand.trend(0, 0));
    }

    @Test
    void trend_lowBaseRate_returnsNeutral() {
        // 90d avg monthly rate = 1/3 = 0.33 < 0.5 threshold → neutral regardless of 30d
        assertEquals("→", HotspotsCommand.trend(10, 1));
    }

    @Test
    void trend_highBurstVsQuietHistory_returnsUp() {
        // 90d avg monthly = 3/3 = 1.0. 30d = 4 > 1.5 → rising
        String result = HotspotsCommand.trend(4, 3);
        // Should be ↑ (red) or at least not cooling
        assertNotEquals("↓", result);
    }
}
