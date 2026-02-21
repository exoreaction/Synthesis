package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.DirectoryScanner;
import io.exoreaction.synthesis.core.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for E002 health check / .synthesisignore integration (issue #212).
 *
 * <p>Key design invariant tested here:
 * <ul>
 *   <li>E002 detection ({@link HealthCommand#findBuildArtifacts}) is always
 *       blind to {@code .synthesisignore} — it always scans unconditionally.</li>
 *   <li>{@link DirectoryScanner} respects {@code .synthesisignore} patterns and
 *       excludes the listed directories from indexing results.</li>
 *   <li>{@link HealthCommand#appendToSynthesisIgnore} is only called after explicit
 *       user confirmation — never silently auto-applied.</li>
 * </ul>
 */
class HealthE002Test {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // Helper: create a minimal .synthesis/config.yaml so ConfigLoader is happy
    // -------------------------------------------------------------------------

    private void initWorkspace() throws Exception {
        Path synthDir = workspace.resolve(".synthesis");
        Files.createDirectories(synthDir);
        Files.writeString(synthDir.resolve("config.yaml"),
                "workspace:\n  name: \"test-workspace\"\n");
    }

    // =========================================================================
    // Test 1: E002 fires even when node_modules is already in .synthesisignore
    // =========================================================================

    @Test
    void e002_detects_node_modules_even_when_in_synthesisignore() throws Exception {
        // Given: .synthesisignore exists and lists node_modules/
        Files.writeString(workspace.resolve(".synthesisignore"),
                "# exclusions\nnode_modules/\n");

        // And: there IS a node_modules directory with a file inside
        Path nm = Files.createDirectories(workspace.resolve("project/node_modules"));
        Files.writeString(nm.resolve("big-lib.js"), "/* big library */");

        // When: E002 detection runs (must be blind to .synthesisignore)
        List<Path> artifacts = HealthCommand.findBuildArtifacts(workspace);

        // Then: E002 still fires — health check is independent of .synthesisignore
        assertFalse(artifacts.isEmpty(),
                "E002 must detect node_modules even when it is listed in .synthesisignore");
        assertTrue(artifacts.stream()
                .anyMatch(p -> p.getFileName().toString().equals("node_modules")),
                "E002 artifact list must contain node_modules");
    }

    // =========================================================================
    // Test 2: Scanner SKIPS directories listed in .synthesisignore
    // =========================================================================

    @Test
    void scanner_skips_synthesisignore_patterns() throws Exception {
        initWorkspace();

        // Given: .synthesisignore contains "node_modules/"
        Files.writeString(workspace.resolve(".synthesisignore"), "node_modules/\n");

        // And: workspace has a real source file AND a node_modules file
        Path src = Files.createDirectories(workspace.resolve("project/src"));
        Files.writeString(src.resolve("index.js"), "console.log('hello');");

        Path nm = Files.createDirectories(workspace.resolve("project/node_modules"));
        Files.writeString(nm.resolve("lodash.js"), "// lodash");

        // When: scanner runs
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(false); // isolate to just .synthesisignore
        DirectoryScanner scanner = new DirectoryScanner(workspace, scanConfig, false);
        ScanResult result = scanner.scan();

        // Then: index.js is scanned, lodash.js is NOT
        boolean foundIndexJs = result.files().stream()
                .anyMatch(f -> f.fileName().equals("index.js"));
        boolean foundLodash = result.files().stream()
                .anyMatch(f -> f.fileName().equals("lodash.js"));

        assertTrue(foundIndexJs, "index.js should be scanned");
        assertFalse(foundLodash, "lodash.js inside node_modules/ should be excluded by .synthesisignore");
    }

    // =========================================================================
    // Test 3: Without .synthesisignore, scanner scans everything
    // =========================================================================

    @Test
    void scanner_no_synthesisignore_scans_everything() throws Exception {
        initWorkspace();

        // Given: no .synthesisignore
        assertFalse(Files.exists(workspace.resolve(".synthesisignore")),
                "Precondition: .synthesisignore must not exist");

        // And: workspace has both a src file and a node_modules file
        Path src = Files.createDirectories(workspace.resolve("project/src"));
        Files.writeString(src.resolve("index.js"), "console.log('hello');");

        Path nm = Files.createDirectories(workspace.resolve("project/node_modules"));
        Files.writeString(nm.resolve("lodash.js"), "// lodash");

        // When: scanner runs with smart defaults OFF so node_modules isn't excluded by ecosystem
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(false);
        DirectoryScanner scanner = new DirectoryScanner(workspace, scanConfig, false);
        ScanResult result = scanner.scan();

        // Then: both files are found (no exclusions applied)
        boolean foundIndexJs = result.files().stream()
                .anyMatch(f -> f.fileName().equals("index.js"));
        boolean foundLodash = result.files().stream()
                .anyMatch(f -> f.fileName().equals("lodash.js"));

        assertTrue(foundIndexJs, "index.js should be scanned");
        assertTrue(foundLodash, "lodash.js should also be scanned when there is no .synthesisignore");
    }

    // =========================================================================
    // Test 4: --fix-config E002 does NOT write .synthesisignore when user answers N
    // =========================================================================

    @Test
    void fix_config_e002_requires_confirmation_before_writing_synthesisignore() throws Exception {
        initWorkspace();

        // Given: a node_modules directory exists (triggers E002)
        Path nm = Files.createDirectories(workspace.resolve("frontend/node_modules"));
        Files.writeString(nm.resolve("react.js"), "// react");

        // And: user will answer "N" to each prompt
        String userInput = "N\n"; // answer "N" to the artifact prompt
        ByteArrayInputStream in = new ByteArrayInputStream(userInput.getBytes());
        System.setIn(in);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes);

        // When: runFixE002 is called
        PrintStream savedOut = System.out;
        System.setOut(out);
        try {
            HealthCommand cmd = new HealthCommand();
            cmd.runFixE002(workspace);
        } finally {
            System.setOut(savedOut);
            System.setIn(System.in);
        }

        // Then: .synthesisignore must NOT be created
        assertFalse(Files.exists(workspace.resolve(".synthesisignore")),
                ".synthesisignore must NOT be created when user answers N");
    }

    // =========================================================================
    // Test 5: --fix-config E002 writes .synthesisignore when user answers y
    // =========================================================================

    @Test
    void fix_config_e002_writes_synthesisignore_on_confirmation() throws Exception {
        initWorkspace();

        // Given: a node_modules directory exists (triggers E002)
        Path nm = Files.createDirectories(workspace.resolve("frontend/node_modules"));
        Files.writeString(nm.resolve("react.js"), "// react");

        // And: user will answer "y" to the prompt
        String userInput = "y\n";
        ByteArrayInputStream in = new ByteArrayInputStream(userInput.getBytes());
        System.setIn(in);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes);

        // When: runFixE002 is called
        PrintStream savedOut = System.out;
        System.setOut(out);
        try {
            HealthCommand cmd = new HealthCommand();
            cmd.runFixE002(workspace);
        } finally {
            System.setOut(savedOut);
            System.setIn(System.in);
        }

        // Then: .synthesisignore IS created and contains the pattern
        assertTrue(Files.exists(workspace.resolve(".synthesisignore")),
                ".synthesisignore must be created when user answers y");

        String content = Files.readString(workspace.resolve(".synthesisignore"));
        assertTrue(content.contains("node_modules/"),
                ".synthesisignore should contain 'node_modules/' pattern");
    }

    // =========================================================================
    // Test 6: parseSynthesisIgnore skips blank lines and comments
    // =========================================================================

    @Test
    void parse_synthesisignore_skips_comments_and_blanks() throws Exception {
        // Given: a .synthesisignore with comments, blanks, and 3 real patterns
        Path ignoreFile = workspace.resolve(".synthesisignore");
        Files.writeString(ignoreFile,
                "# This is a comment\n" +
                "\n" +
                "node_modules/\n" +
                "# Another comment\n" +
                "\n" +
                "target/\n" +
                ".venv/\n");

        // When
        List<String> patterns = DirectoryScanner.parseSynthesisIgnore(ignoreFile);

        // Then: only the 3 real patterns are returned
        assertEquals(3, patterns.size(),
                "Expected exactly 3 patterns (comments and blanks stripped)");
        assertTrue(patterns.contains("node_modules/"), "Should contain node_modules/");
        assertTrue(patterns.contains("target/"), "Should contain target/");
        assertTrue(patterns.contains(".venv/"), "Should contain .venv/");
    }

    // =========================================================================
    // Test 7: appendToSynthesisIgnore creates file and appends pattern
    // =========================================================================

    @Test
    void append_to_synthesisignore_creates_file_if_missing() throws Exception {
        Path ignoreFile = workspace.resolve(".synthesisignore");
        assertFalse(Files.exists(ignoreFile), "Precondition: file must not exist");

        HealthCommand.appendToSynthesisIgnore(ignoreFile, "node_modules/");

        assertTrue(Files.exists(ignoreFile), ".synthesisignore should be created");
        String content = Files.readString(ignoreFile);
        assertTrue(content.contains("node_modules/"), "Pattern should be appended");
    }

    @Test
    void append_to_synthesisignore_does_not_duplicate_existing_pattern() throws Exception {
        Path ignoreFile = workspace.resolve(".synthesisignore");
        Files.writeString(ignoreFile, "# exclusions\nnode_modules/\n");

        // Append same pattern again
        HealthCommand.appendToSynthesisIgnore(ignoreFile, "node_modules/");

        String content = Files.readString(ignoreFile);
        long count = content.lines()
                .filter(l -> l.trim().equals("node_modules/"))
                .count();
        assertEquals(1, count, "Pattern should not be duplicated in .synthesisignore");
    }

    // =========================================================================
    // Test 8: InitCommand proposes .synthesisignore and creates it when accepted
    // =========================================================================

    @Test
    void init_proposes_synthesisignore_creates_it_when_accepted() throws Exception {
        initWorkspace();
        assertFalse(Files.exists(workspace.resolve(".synthesisignore")));

        // Simulate user answering "y"
        ByteArrayInputStream in = new ByteArrayInputStream("y\n".getBytes());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes);

        InitCommand cmd = new InitCommand();
        cmd.setInteractiveIO(new BufferedReader(new InputStreamReader(in)), out);

        cmd.proposeSynthesisIgnore(workspace);

        assertTrue(Files.exists(workspace.resolve(".synthesisignore")),
                ".synthesisignore should be created when user answers y");
        String content = Files.readString(workspace.resolve(".synthesisignore"));
        assertTrue(content.contains("node_modules/"));
        assertTrue(content.contains("target/"));
    }

    @Test
    void init_proposes_synthesisignore_skips_when_declined() throws Exception {
        initWorkspace();

        // Simulate user answering "n"
        ByteArrayInputStream in = new ByteArrayInputStream("n\n".getBytes());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes);

        InitCommand cmd = new InitCommand();
        cmd.setInteractiveIO(new BufferedReader(new InputStreamReader(in)), out);

        cmd.proposeSynthesisIgnore(workspace);

        assertFalse(Files.exists(workspace.resolve(".synthesisignore")),
                ".synthesisignore should NOT be created when user answers n");
    }

    @Test
    void init_proposes_synthesisignore_skips_if_already_exists() throws Exception {
        initWorkspace();

        // Pre-create .synthesisignore with existing content
        String existingContent = "# existing\ncustom-dir/\n";
        Files.writeString(workspace.resolve(".synthesisignore"), existingContent);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes);

        InitCommand cmd = new InitCommand();
        cmd.setInteractiveIO(
                new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0]))),
                out);

        cmd.proposeSynthesisIgnore(workspace);

        // Content must remain unchanged
        String content = Files.readString(workspace.resolve(".synthesisignore"));
        assertEquals(existingContent, content,
                "Existing .synthesisignore must not be modified");
    }

    @Test
    void init_noInteractive_auto_creates_synthesisignore() throws Exception {
        initWorkspace();

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes);

        InitCommand cmd = new InitCommand();
        cmd.setInteractiveIO(
                new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0]))),
                out);

        // Set noInteractive=true via reflection
        java.lang.reflect.Field niField = InitCommand.class.getDeclaredField("noInteractive");
        niField.setAccessible(true);
        niField.setBoolean(cmd, true);

        cmd.proposeSynthesisIgnore(workspace);

        assertTrue(Files.exists(workspace.resolve(".synthesisignore")),
                "In non-interactive mode, .synthesisignore should be auto-created");
    }
}
