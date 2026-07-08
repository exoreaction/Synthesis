package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the process timeout guard in {@link SynthesisToolHandler#runProcess}.
 *
 * <p>Validates fix for issue #325: MCP scan tool times out on large workspaces
 * because {@code runSynthesisCli} blocks indefinitely with no timeout or
 * concurrent stderr drain.
 *
 * <p>Uses simple shell commands (echo, sleep, bash -c) rather than the real
 * synthesis binary, so no {@code @EnabledIf} guard is needed.
 */
class RunSynthesisCliTimeoutTest {

    private SynthesisToolHandler handler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        handler = new SynthesisToolHandler(new ObjectMapper(), tempDir);
    }

    // --- Normal execution ---

    @Test
    void runProcess_capturesStdout() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("echo", "hello world");
        String output = handler.runProcess(pb, 10);
        assertEquals("hello world", output.trim());
    }

    @Test
    void runProcess_capturesStdoutAndStderrOnFailure() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "echo 'stdout line' && echo 'stderr line' >&2 && exit 1");
        // Non-zero exit with stdout present → appends stderr
        String output = handler.runProcess(pb, 10);
        assertTrue(output.contains("stdout line"));
        assertTrue(output.contains("[stderr] stderr line"));
    }

    @Test
    void runProcess_throwsOnFailureWithNoStdout() {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "echo 'error message' >&2 && exit 1");
        SynthesisToolHandler.McpToolException ex = assertThrows(
                SynthesisToolHandler.McpToolException.class,
                () -> handler.runProcess(pb, 10));
        assertTrue(ex.getMessage().contains("error message"));
    }

    // --- Timeout ---

    @Test
    void runProcess_killsOnTimeout() {
        // Process that sleeps for 60s should be killed after 2s timeout
        ProcessBuilder pb = new ProcessBuilder("sleep", "60");
        long start = System.nanoTime();

        SynthesisToolHandler.McpToolException ex = assertThrows(
                SynthesisToolHandler.McpToolException.class,
                () -> handler.runProcess(pb, 2));

        long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(elapsed < 10, "Should have timed out quickly, took " + elapsed + "s");
        assertTrue(ex.getMessage().contains("timed out"),
                "Error should mention timeout: " + ex.getMessage());
    }

    @Test
    void runProcess_timeoutMessageIncludesGuidance() {
        ProcessBuilder pb = new ProcessBuilder("sleep", "60");
        SynthesisToolHandler.McpToolException ex = assertThrows(
                SynthesisToolHandler.McpToolException.class,
                () -> handler.runProcess(pb, 2));
        // Should guide user to run from CLI directly
        assertTrue(ex.getMessage().toLowerCase().contains("cli"),
                "Timeout error should mention CLI alternative: " + ex.getMessage());
    }

    // --- Stderr deadlock prevention ---

    @Test
    void runProcess_drainsStderrConcurrently_noDeadlock() throws Exception {
        // Write >64KB to stderr (OS pipe buffer size) to trigger the deadlock
        // that existed before the fix. If stderr isn't drained concurrently,
        // the process will hang because the pipe buffer fills up.
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "for i in $(seq 1 2000); do echo \"stderr line $i some padding text to fill the buffer\" >&2; done && echo 'done'");
        String output = handler.runProcess(pb, 30);
        assertTrue(output.contains("done"),
                "Process should complete without deadlock, got: " + output.substring(0, Math.min(200, output.length())));
    }

    @Test
    void runProcess_largeStderrWithNonZeroExit_appendedToOutput() throws Exception {
        // Non-zero exit with both stdout and large stderr
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "echo 'report output' && for i in $(seq 1 100); do echo \"warn $i\" >&2; done && exit 1");
        String output = handler.runProcess(pb, 30);
        assertTrue(output.contains("report output"));
        assertTrue(output.contains("[stderr]"));
    }

    // --- Default timeout from constant ---

    @Test
    void defaultCliTimeoutSeconds_isFiveMinutes() {
        assertEquals(300, SynthesisToolHandler.DEFAULT_CLI_TIMEOUT_SECONDS);
    }

    // --- Process cleanup ---

    @Test
    void runProcess_destroysProcessOnTimeout() throws Exception {
        // Start a process that creates a marker file after sleeping.
        // If destroyed properly, the marker should NOT appear.
        Path marker = tempDir.resolve("still-alive.txt");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "sleep 10 && touch " + marker.toAbsolutePath());

        assertThrows(SynthesisToolHandler.McpToolException.class,
                () -> handler.runProcess(pb, 2));

        // Give a moment for any zombie to finish
        Thread.sleep(500);
        assertFalse(Files.exists(marker),
                "Process should have been killed before creating marker file");
    }
}
