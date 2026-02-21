package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentity;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.ScopeLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the {@code synthesis route-explain} command (P1-07).
 *
 * <p>Tests verify that the command produces meaningful output for various
 * workspace configurations.
 */
class RouteExplainCommandTest {

    @TempDir
    Path workspace;

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    /**
     * Creates a directory with a .synthesis.md identity file.
     */
    private Path createDir(String relativePath, DirectoryIdentity identity) throws IOException {
        Path dir = workspace.resolve(relativePath);
        Files.createDirectories(dir);
        parser.write(dir.resolve(".synthesis.md"), identity);
        return dir;
    }

    /**
     * Runs the route-explain command and captures stdout.
     */
    private String runCommand(String... args) {
        // Build the command args
        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = "-d";
        fullArgs[1] = workspace.toString();
        System.arraycopy(args, 0, fullArgs, 2, args.length);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos));
        try {
            picocli.CommandLine cmd = new picocli.CommandLine(
                    new io.exoreaction.synthesis.SynthesisApp());
            cmd.execute(fullArgs);
        } finally {
            System.setOut(saved);
        }
        return baos.toString();
    }

    @Test
    void routeExplain_showsCandidatesAndRecommendation() throws IOException {
        // Create permanent media directory
        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis"), false, List.of()
        ));

        // Create docs directory
        createDir("docs", new DirectoryIdentity(
                List.of("documentation"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        ));

        // Create the file to analyze
        Path file = workspace.resolve("synthesis-demo.mp4");
        Files.writeString(file, "video-data");

        String output = runCommand("route-explain", file.toString());

        // Should show the file name
        assertTrue(output.contains("synthesis-demo.mp4"),
                "Output should show filename. Got: " + output);
        // Should show candidates
        assertTrue(output.contains("candidates"),
                "Output should mention candidates. Got: " + output);
        // Should show the media directory as a candidate
        assertTrue(output.contains("media") || output.contains("Synthesis"),
                "Output should list the media directory. Got: " + output);
        // Should show a recommendation
        assertTrue(output.contains("Recommendation"),
                "Output should include a recommendation. Got: " + output);
    }

    @Test
    void routeExplain_noCandidates_showsWarning() {
        // Empty workspace with no .synthesis.md files
        Path file = workspace.resolve("orphan.pdf");

        String output = runCommand("route-explain", file.toString());

        assertTrue(output.contains("No candidate") || output.contains("no candidate"),
                "Should warn about no candidates. Got: " + output);
    }

    @Test
    void routeExplain_transientFile_showsNote() throws IOException {
        // Create a transient directory
        createDir("incoming", new DirectoryIdentity(
                List.of("incoming"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create permanent destination
        createDir("products/Aurora/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("aurora"), false, List.of()
        ));

        // File in the transient directory
        Path file = workspace.resolve("incoming/aurora-demo.mp4");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "video-data");

        String output = runCommand("route-explain", file.toString());

        // Should note the transient status
        assertTrue(output.contains("transient"),
                "Should note that file is in a transient directory. Got: " + output);
    }

    @Test
    void routeExplain_showsConfidenceLevel() throws IOException {
        createDir("media", new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        ));

        Path file = workspace.resolve("video.mp4");
        Files.writeString(file, "data");

        String output = runCommand("route-explain", file.toString());

        // Should contain a confidence level name
        boolean hasConfidence = output.contains("CERTAIN") || output.contains("HIGH")
                || output.contains("MODERATE") || output.contains("LOW")
                || output.contains("NONE");
        assertTrue(hasConfidence,
                "Output should include a confidence level. Got: " + output);
    }

    @Test
    void routeExplain_showsScoringBreakdown() throws IOException {
        createDir("docs", new DirectoryIdentity(
                List.of("documentation"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        ));

        Path file = workspace.resolve("notes.md");
        Files.writeString(file, "data");

        String output = runCommand("route-explain", file.toString());

        // Should contain scoring component names
        assertTrue(output.contains("type-match") || output.contains("format-match"),
                "Output should show scoring components. Got: " + output);
    }
}
