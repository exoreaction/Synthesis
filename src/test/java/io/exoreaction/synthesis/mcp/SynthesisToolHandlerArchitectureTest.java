package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SynthesisToolHandler#handleArchitecture} / {@code runSynthesisCli}.
 *
 * <p>architecture uses its exit code to signal severity (0=clean, 1=warnings, 2=errors),
 * not success/failure, and always prints its report to stdout. runSynthesisCli used to
 * treat any nonzero exit as a failure and discard stdout, producing a blank error on any
 * workspace with a warning- or error-severity alert.
 *
 * <p>Exercises the real subprocess path (runSynthesisCli shells out to the globally
 * installed {@code ~/.synthesis/bin/synthesis}), so skipped if that binary isn't present --
 * same convention as {@code TesseractOcrExtractorTest}.
 */
class SynthesisToolHandlerArchitectureTest {

    @TempDir
    Path tempDir;

    static boolean synthesisCliAvailable() {
        Path bin = Path.of(System.getProperty("user.home"), ".synthesis", "bin", "synthesis");
        return Files.isExecutable(bin);
    }

    @Test
    @EnabledIf("synthesisCliAvailable")
    void handleArchitecture_returnsFullReportWhenWarningsPresent() throws Exception {
        // A single file over the GOD_CLASS line threshold is the cheapest fixture that
        // reliably produces a warning-severity alert (see ArchitectureMonitorTest).
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            content.append("    public void method").append(i).append("() {}\n");
        }
        Files.writeString(tempDir.resolve("GodClass.java"), content.toString());

        runSynthesisCli(List.of("init", tempDir.toString(), "--name", "test-arch", "--yes"));
        runSynthesisCli(List.of("-d", tempDir.toString(), "scan"));

        ObjectMapper mapper = new ObjectMapper();
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);
        ObjectNode result = handler.handleArchitecture(mapper.createObjectNode());

        String architecture = result.get("architecture").asText();
        assertFalse(architecture.isBlank());
        assertTrue(architecture.contains("GOD_CLASS"));
    }

    @Test
    @EnabledIf("synthesisCliAvailable")
    void handleArchitecture_throwsOnGenuineFailure() {
        // No .synthesis/ directory -- validate() fails, stdout stays blank, stderr
        // carries the real message. Must still throw after the fix.
        ObjectMapper mapper = new ObjectMapper();
        SynthesisToolHandler handler = new SynthesisToolHandler(mapper, tempDir);

        SynthesisToolHandler.McpToolException ex = assertThrows(
                SynthesisToolHandler.McpToolException.class,
                () -> handler.handleArchitecture(mapper.createObjectNode()));
        assertTrue(ex.getMessage().contains("not a Synthesis workspace"));
    }

    private void runSynthesisCli(List<String> args) throws IOException, InterruptedException {
        String bin = Path.of(System.getProperty("user.home"), ".synthesis", "bin", "synthesis").toString();
        List<String> command = new java.util.ArrayList<>(List.of(bin));
        command.addAll(args);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        assertEquals(0, exitCode, "setup command " + args + " failed:\n" + output);
    }
}
