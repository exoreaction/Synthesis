package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProgressReporter — construction, tick, complete, fail,
 * and edge cases (zero total, large counts).
 */
class ProgressReporterTest {

    // --- construction ---

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow((Executable) () -> { new ProgressReporter("Test", 100); });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 100, 1000})
    void constructor_variousTotals_doesNotThrow(int total) {
        assertDoesNotThrow((Executable) () -> { new ProgressReporter("Label", total); });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Scanning", "Indexing", "Processing", ""})
    void constructor_variousLabels_doesNotThrow(String label) {
        assertDoesNotThrow((Executable) () -> { new ProgressReporter(label, 10); });
    }

    // --- tick ---

    @Test
    void tick_single_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 10);
        assertDoesNotThrow((Executable) reporter::tick);
    }

    @Test
    void tick_multipleTimesUpToTotal_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 5);
        assertDoesNotThrow((Executable) () -> {
            for (int i = 0; i < 5; i++) {
                reporter.tick();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 50})
    void tickAmount_variousAmounts_doesNotThrow(int amount) {
        ProgressReporter reporter = new ProgressReporter("Test", 100);
        assertDoesNotThrow((Executable) () -> reporter.tick(amount));
    }

    // --- complete ---

    @Test
    void complete_afterNoTicks_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 10);
        assertDoesNotThrow((Executable) reporter::complete);
    }

    @Test
    void complete_afterAllTicks_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 3);
        reporter.tick();
        reporter.tick();
        reporter.tick();
        assertDoesNotThrow((Executable) reporter::complete);
    }

    @Test
    void complete_zeroTotal_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 0);
        assertDoesNotThrow((Executable) reporter::complete);
    }

    // --- fail ---

    @Test
    void fail_withMessage_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 10);
        assertDoesNotThrow((Executable) () -> reporter.fail("Network error"));
    }

    @Test
    void fail_withEmptyMessage_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Test", 10);
        assertDoesNotThrow((Executable) () -> reporter.fail(""));
    }

    // --- sequential usage ---

    @Test
    void tick_complete_sequentialUsage_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Processing", 10);
        assertDoesNotThrow((Executable) () -> {
            for (int i = 0; i < 10; i++) {
                reporter.tick();
            }
            reporter.complete();
        });
    }

    @Test
    void tick_fail_sequentialUsage_doesNotThrow() {
        ProgressReporter reporter = new ProgressReporter("Processing", 10);
        reporter.tick(5);
        assertDoesNotThrow((Executable) () -> reporter.fail("Partial failure"));
    }
}
