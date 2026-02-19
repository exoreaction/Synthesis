package io.exoreaction.synthesis;

import io.exoreaction.synthesis.metrics.MetricsCollector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsCollector wiring in SynthesisApp.
 *
 * <p>Verifies that {@code getMetrics()} returns a non-null collector and that
 * {@code shutdownMetrics()} is safe to call multiple times without error.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/77">#77</a>
 */
class SynthesisAppMetricsTest {

    @Test
    void getMetrics_returnsNonNullCollector() {
        SynthesisApp app = new SynthesisApp();
        MetricsCollector metrics = app.getMetrics();
        assertNotNull(metrics, "getMetrics() should return a non-null MetricsCollector");
        app.shutdownMetrics();
    }

    @Test
    void getMetrics_returnsSameInstance_onRepeatedCalls() {
        SynthesisApp app = new SynthesisApp();
        MetricsCollector first = app.getMetrics();
        MetricsCollector second = app.getMetrics();
        assertSame(first, second, "getMetrics() should return the same instance (lazy singleton)");
        app.shutdownMetrics();
    }

    @Test
    void shutdownMetrics_safeToCallBeforeGetMetrics() {
        SynthesisApp app = new SynthesisApp();
        assertDoesNotThrow(app::shutdownMetrics,
                "shutdownMetrics() must not throw when metrics were never initialized");
    }

    @Test
    void shutdownMetrics_safeToCallTwice() {
        SynthesisApp app = new SynthesisApp();
        app.getMetrics(); // initialize
        assertDoesNotThrow(app::shutdownMetrics, "First shutdown must not throw");
        assertDoesNotThrow(app::shutdownMetrics, "Second shutdown must not throw");
    }
}
