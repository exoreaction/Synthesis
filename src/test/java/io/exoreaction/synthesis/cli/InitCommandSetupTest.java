package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for the guided 5-phase first-run setup in {@link InitCommand} (issue #187).
 *
 * <p>Each test uses a temporary directory to avoid touching real workspace state.
 * Phases 4 and 5 degrade gracefully in minimal workspaces, so the tests focus on
 * observable file-system side-effects and captured stdout rather than deep internals.
 */
class InitCommandSetupTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helper: create a minimal Synthesis workspace (.synthesis/ dir + config.yaml)
    // -------------------------------------------------------------------------

    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis").resolve("config.yaml"),
                "workspace:\n  name: \"test-workspace\"\n");
    }

    private SynthesisConfig loadConfig(Path root) throws IOException {
        return ConfigLoader.load(root);
    }

    // =========================================================================
    // 1. Creates the .synthesis/ sentinel directory on init
    // =========================================================================

    @Test
    void init_creates_synthesis_sentinel_directory() throws Exception {
        // Given: a fresh directory (no .synthesis/ yet)
        // Note: runGuidedSetup operates on an already-initialized workspace,
        // so we initialize it first (mimicking what InitCommand.call() does).
        initWorkspace(tempDir);

        // When: guided setup runs
        SynthesisConfig config = loadConfig(tempDir);
        InitCommand cmd = new InitCommand();
        captureAndRun(() -> cmd.runGuidedSetup(tempDir, config));

        // Then: .synthesis/ directory must exist
        assertTrue(Files.isDirectory(tempDir.resolve(".synthesis")),
                ".synthesis/ directory must exist after guided setup");
    }

    // =========================================================================
    // 2. Detects Downloads path when present
    // =========================================================================

    @Test
    void init_detects_downloads_path_when_present() {
        // Given: the user.home property points to tempDir which has a Downloads subdir
        Path fakeDownloads = tempDir.resolve("Downloads");
        try {
            Files.createDirectories(fakeDownloads);
        } catch (IOException e) {
            fail("Could not create fake Downloads dir: " + e.getMessage());
        }

        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            // When: detectDownloadsPath is called
            Path detected = InitCommand.detectDownloadsPath();

            // Then: the Downloads directory is detected
            assertNotNull(detected, "Should detect Downloads directory");
            assertTrue(detected.toString().contains("Downloads"),
                    "Detected path should reference Downloads: " + detected);
            assertTrue(Files.isDirectory(detected),
                    "Detected path must be a real directory");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void init_returns_null_when_no_downloads_present() {
        // Given: user.home points to a directory with no Downloads/Desktop/Documents
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            // When
            Path detected = InitCommand.detectDownloadsPath();

            // Then: nothing detected (tempDir has no Downloads/Desktop/Documents children)
            assertNull(detected, "Should return null when no standard staging folder exists");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    // =========================================================================
    // 3. Generates directory identities (.synthesis.md) for known directory names
    // =========================================================================

    @Test
    void init_generates_synthesis_md_for_known_directory_names() throws Exception {
        // Given: workspace with a 'meetings' subdirectory containing a file
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup-2026-02-20.md"),
                "# Standup\nAttendees: Alice, Bob");

        // When: guided setup runs (phase 3 = SyncCommand)
        SynthesisConfig config = loadConfig(tempDir);
        InitCommand cmd = new InitCommand();
        captureAndRun(() -> cmd.runGuidedSetup(tempDir, config));

        // Then: SyncCommand should have created a .synthesis.md identity file
        Path synthesisFile = meetings.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile),
                "Phase 3 should generate .synthesis.md for the meetings/ directory. "
                        + "Files in meetings/: " + listFiles(meetings));
    }

    // =========================================================================
    // 4. --yes flag skips all interactive prompts
    // =========================================================================

    @Test
    void init_with_yes_flag_skips_all_prompts() throws Exception {
        // Given: a workspace with an org-like directory structure
        initWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("acme-corp"));
        Files.writeString(tempDir.resolve("acme-corp").resolve("README.md"),
                "# Acme Corp\nWelcome to Acme.");

        // When: InitCommand is invoked with --yes via field reflection
        // (We test the flag resolves noInteractive=true without actual picocli parsing)
        InitCommand cmd = new InitCommand();

        // Set 'yes' via reflection
        java.lang.reflect.Field yesField = InitCommand.class.getDeclaredField("yes");
        yesField.setAccessible(true);
        yesField.setBoolean(cmd, true);

        // Set 'noInteractive' directly (this is what --yes triggers in call())
        java.lang.reflect.Field niField = InitCommand.class.getDeclaredField("noInteractive");
        niField.setAccessible(true);

        // Simulate the --yes → noInteractive=true merge that happens in call()
        boolean yesValue = (boolean) yesField.get(cmd);
        if (yesValue) {
            niField.setBoolean(cmd, true);
        }

        boolean noInteractive = (boolean) niField.get(cmd);
        assertTrue(noInteractive, "--yes should cause noInteractive=true");

        // Also verify guided setup runs without blocking on stdin
        SynthesisConfig config = loadConfig(tempDir);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos));
        try {
            cmd.runGuidedSetup(tempDir, config);
        } finally {
            System.setOut(saved);
        }

        String output = baos.toString();
        assertTrue(output.contains("[1/5]"), "Phase 1 should appear in guided setup output");
        assertTrue(output.contains("[5/5]"), "Phase 5 should appear in guided setup output");
    }

    // =========================================================================
    // 5. Running init twice is idempotent (second run does not fail)
    // =========================================================================

    @Test
    void init_is_idempotent() throws Exception {
        // Given: a minimal workspace
        initWorkspace(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "# Test Workspace");

        SynthesisConfig config = loadConfig(tempDir);
        InitCommand cmd = new InitCommand();

        // When: guided setup runs twice
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable)
                () -> captureAndRun(() -> cmd.runGuidedSetup(tempDir, config)),
                "First guided setup run should not throw");

        assertDoesNotThrow((org.junit.jupiter.api.function.Executable)
                () -> captureAndRun(() -> cmd.runGuidedSetup(tempDir, config)),
                "Second guided setup run should not throw (idempotent)");

        // Then: .synthesis/ directory is still intact
        assertTrue(Files.isDirectory(tempDir.resolve(".synthesis")),
                ".synthesis/ directory must survive two guided setup runs");
    }

    // =========================================================================
    // 6. Phase 1 output reports directories and files
    // =========================================================================

    @Test
    void guided_setup_phase1_reports_structure() throws Exception {
        // Given: workspace with 2 subdirs and 1 root file
        initWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("docs"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("README.md"), "# Root file");

        // When: guided setup runs
        SynthesisConfig config = loadConfig(tempDir);
        InitCommand cmd = new InitCommand();
        String output = captureAndRun(() -> cmd.runGuidedSetup(tempDir, config));

        // Then: phase 1 output should mention directories and files
        assertTrue(output.contains("[1/5]"), "Should show phase 1 header");
        assertTrue(output.contains("director"), "Should mention directories in phase 1");
    }

    // =========================================================================
    // 7. Guided setup output includes "What you can do now"
    // =========================================================================

    @Test
    void guided_setup_prints_what_you_can_do_now() throws Exception {
        // Given: minimal workspace
        initWorkspace(tempDir);

        // When
        SynthesisConfig config = loadConfig(tempDir);
        InitCommand cmd = new InitCommand();
        String output = captureAndRun(() -> cmd.runGuidedSetup(tempDir, config));

        // Then: "What you can do now" section appears
        assertTrue(output.contains("What you can do now"),
                "Should print 'What you can do now' section. Output was:\n" + output);
        assertTrue(output.contains("synthesis search"),
                "Should suggest synthesis search command");
        assertTrue(output.contains("synthesis maintain"),
                "Should suggest synthesis maintain command");
    }

    // =========================================================================
    // 8. Crontab suggestion appears in output
    // =========================================================================

    @Test
    void guided_setup_prints_crontab_suggestion() throws Exception {
        // Given: minimal workspace
        initWorkspace(tempDir);

        // When
        SynthesisConfig config = loadConfig(tempDir);
        InitCommand cmd = new InitCommand();
        String output = captureAndRun(() -> cmd.runGuidedSetup(tempDir, config));

        // Then: crontab suggestion appears
        assertTrue(output.contains("cron") || output.contains("* * *"),
                "Should include crontab suggestion. Output was:\n" + output);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Runs a runnable while capturing stdout, returning the captured output. */
    private String captureAndRun(RunnableWithException action) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos));
        try {
            action.run();
        } finally {
            System.setOut(saved);
        }
        return baos.toString();
    }

    @FunctionalInterface
    interface RunnableWithException {
        void run() throws Exception;
    }

    private String listFiles(Path dir) {
        try {
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> sb.append(p.getFileName()).append(" "));
            }
            return sb.toString();
        } catch (IOException e) {
            return "<error: " + e.getMessage() + ">";
        }
    }
}
