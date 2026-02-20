package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #188: Unify sub-workspace config entries with .synthesis.md directory identities.
 *
 * <p>Verifies the three-tier precedence:
 * <ol>
 *   <li>Hand-edited {@code .synthesis.md} (source: "manual") — NEVER overwritten</li>
 *   <li>Config sub-workspace entry → source: "config entry", confidence 0.95</li>
 *   <li>Inferred identity (source: "inferred from N files") — always regeneratable</li>
 * </ol>
 */
class SyncCommandPrecedenceTest {

    @TempDir
    Path tempDir;

    private DirectoryIdentityParser parser;

    @BeforeEach
    void setUp() throws IOException {
        parser = new DirectoryIdentityParser();
        initWorkspace(tempDir);
    }

    // -------------------------------------------------------------------------
    // Precedence 1: manual source — never overwritten (unless --force)
    // -------------------------------------------------------------------------

    @Test
    void manualSynthesisMd_notOverwritten() throws Exception {
        // A directory with a manually-edited .synthesis.md (source: "manual")
        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("brief.md"), "# Acme brief");

        // Write a manual .synthesis.md
        DirectoryIdentity manual = new DirectoryIdentity(
                List.of("client-docs", "handwritten"),
                List.of("pdf", "docx"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Acme",
                null,
                0.99,
                Instant.now(),
                "manual",
                "Hand-crafted identity for Acme"
        );
        Path synthesisFile = clientDir.resolve(".synthesis.md");
        parser.write(synthesisFile, manual);

        // Run sync
        runSync(tempDir);

        // Assert file unchanged — still manual
        DirectoryIdentity result = parser.parse(synthesisFile);

        assertEquals("manual", result.source(),
                "Source should remain 'manual' — manual files must not be overwritten");
        assertTrue(result.acceptsTypes().contains("client-docs"),
                "Custom types must be preserved. Types: " + result.acceptsTypes());
        assertFalse(result.acceptsTypes().contains("knowledge"),
                "Inferred 'knowledge' type must NOT be added to manual identity");
    }

    @Test
    void manualSynthesisMd_notOverwrittenByConfigEntry() throws Exception {
        // Config has an entry for this directory
        writeSynthesisConfig(tempDir,
                "workspace:\n"
                + "  name: test\n"
                + "subWorkspaces:\n"
                + "  - name: \"Acme\"\n"
                + "    path: \"clients/Acme\"\n"
                + "    type: \"client\"\n"
                + "    description: \"Acme client docs\"\n");

        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("proposal.pdf"), "proposal content");

        // Write a manual .synthesis.md
        DirectoryIdentity manual = new DirectoryIdentity(
                List.of("strategic-account"),
                List.of("pdf"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Acme",
                null,
                0.99,
                Instant.now(),
                "manual",
                "Manually crafted — do not overwrite"
        );
        Path synthesisFile = clientDir.resolve(".synthesis.md");
        parser.write(synthesisFile, manual);

        // Run sync
        runSync(tempDir);

        // Assert the manual identity is preserved, NOT replaced by config entry
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertEquals("manual", result.source(),
                "Config entry must NOT overwrite manual source");
        assertTrue(result.acceptsTypes().contains("strategic-account"),
                "Custom type must be preserved. Types: " + result.acceptsTypes());
        assertFalse(result.acceptsTypes().contains("client"),
                "Config-derived 'client' type must NOT be injected into manual identity");
    }

    // -------------------------------------------------------------------------
    // Precedence 2: config entry — generates high-confidence identity
    // -------------------------------------------------------------------------

    @Test
    void configEntry_generatesHighConfidenceIdentity() throws Exception {
        // Config has sub-workspace entry for "clients/Acme"
        writeSynthesisConfig(tempDir,
                "workspace:\n"
                + "  name: test\n"
                + "subWorkspaces:\n"
                + "  - name: \"Acme\"\n"
                + "    path: \"clients/Acme\"\n"
                + "    type: \"client\"\n"
                + "    description: \"Acme client docs\"\n");

        // Directory exists but has no .synthesis.md
        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("contract.pdf"), "contract content");

        // Run sync
        runSync(tempDir);

        // Assert .synthesis.md created with source: "config entry", confidence: 0.95
        Path synthesisFile = clientDir.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile),
                ".synthesis.md should be created for config-registered directory");

        DirectoryIdentity result = parser.parse(synthesisFile);
        assertEquals("config entry", result.source(),
                "Source should be 'config entry'. Got: " + result.source());
        assertEquals(0.95, result.confidence(), 0.001,
                "Config entry should have confidence 0.95");
        assertTrue(result.acceptsTypes().contains("client"),
                "Config 'client' type should be present. Types: " + result.acceptsTypes());
        assertEquals("Acme", result.scopeOrganization(),
                "Scope organization should be the config entry name");
        assertEquals("Acme client docs", result.description(),
                "Description should come from config entry");
    }

    @Test
    void configEntry_withTags_tagsIncludedInTypes() throws Exception {
        writeSynthesisConfig(tempDir,
                "workspace:\n"
                + "  name: test\n"
                + "subWorkspaces:\n"
                + "  - name: \"Nordic\"\n"
                + "    path: \"clients/Nordic\"\n"
                + "    type: \"client\"\n"
                + "    tags:\n"
                + "      - \"energy\"\n"
                + "      - \"enterprise\"\n"
                + "    description: \"Nordic Energy client\"\n");

        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Nordic"));
        Files.writeString(clientDir.resolve("report.md"), "# Nordic report");

        runSync(tempDir);

        Path synthesisFile = clientDir.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        DirectoryIdentity result = parser.parse(synthesisFile);
        assertTrue(result.acceptsTypes().contains("energy"),
                "Tags should be included in types. Types: " + result.acceptsTypes());
        assertTrue(result.acceptsTypes().contains("enterprise"),
                "Tags should be included in types. Types: " + result.acceptsTypes());
        assertTrue(result.acceptsTypes().contains("client"),
                "Type 'client' from config should also be present");
        assertEquals("config entry", result.source());
    }

    @Test
    void configEntry_sourceCodeType_expandsToSourceAndCode() throws Exception {
        writeSynthesisConfig(tempDir,
                "workspace:\n"
                + "  name: test\n"
                + "subWorkspaces:\n"
                + "  - name: \"MyApp\"\n"
                + "    path: \"codebases/MyApp\"\n"
                + "    type: \"source-code\"\n"
                + "    description: \"Main application code\"\n");

        Path codeDir = Files.createDirectories(tempDir.resolve("codebases/MyApp"));
        Files.writeString(codeDir.resolve("README.md"), "# MyApp");

        runSync(tempDir);

        Path synthesisFile = codeDir.resolve(".synthesis.md");
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertTrue(result.acceptsTypes().contains("source"),
                "source-code type should expand to 'source'. Types: " + result.acceptsTypes());
        assertTrue(result.acceptsTypes().contains("code"),
                "source-code type should expand to 'code'. Types: " + result.acceptsTypes());
        assertEquals("config entry", result.source());
    }

    @Test
    void configEntry_takesOverInferred() throws Exception {
        // Directory has an existing inferred identity
        writeSynthesisConfig(tempDir,
                "workspace:\n"
                + "  name: test\n"
                + "subWorkspaces:\n"
                + "  - name: \"Acme\"\n"
                + "    path: \"clients/Acme\"\n"
                + "    type: \"client\"\n"
                + "    description: \"Acme client docs\"\n");

        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("meeting-notes.md"), "# Meeting notes");

        // Pre-write an inferred identity
        DirectoryIdentity inferred = new DirectoryIdentity(
                List.of("meeting-notes"),
                List.of("md"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.6,
                Instant.now(),
                "inferred from 5 existing files",
                ""
        );
        Path synthesisFile = clientDir.resolve(".synthesis.md");
        parser.write(synthesisFile, inferred);

        // Run sync
        runSync(tempDir);

        // Assert .synthesis.md updated with source: "config entry"
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertEquals("config entry", result.source(),
                "Config entry should replace inferred source. Got: " + result.source());
        assertEquals(0.95, result.confidence(), 0.001,
                "Config entry confidence (0.95) should replace inferred (0.6)");
        assertTrue(result.acceptsTypes().contains("client"),
                "Config-derived type 'client' should be present. Types: " + result.acceptsTypes());
    }

    // -------------------------------------------------------------------------
    // Precedence 3: inferred identity — has source set
    // -------------------------------------------------------------------------

    @Test
    void inferredIdentity_hasSourceSet() throws Exception {
        // Directory with markdown files, no config entry
        Path reportsDir = Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(reportsDir.resolve("report-2026-01.md"), "# Q1 Report");
        Files.writeString(reportsDir.resolve("report-2026-02.md"), "# Q2 Report");

        runSync(tempDir);

        Path synthesisFile = reportsDir.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile),
                ".synthesis.md should be created for directory with files");

        DirectoryIdentity result = parser.parse(synthesisFile);
        assertNotNull(result.source(),
                "Inferred identity should have a non-null source");
        assertFalse(result.source().isBlank(),
                "Inferred identity source should not be blank");
        assertTrue(result.source().startsWith("inferred from"),
                "Inferred source should start with 'inferred from'. Got: " + result.source());
    }

    @Test
    void inferredIdentity_vocabulary_hasSourceSet() throws Exception {
        // 'meetings' is a vocabulary-recognized directory name
        Path meetingsDir = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetingsDir.resolve("standup-2026.md"), "# Standup");

        runSync(tempDir);

        Path synthesisFile = meetingsDir.resolve(".synthesis.md");
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertNotNull(result.source(), "Vocabulary-inferred identity must have a source");
        assertFalse(result.source().isBlank(),
                "Source must not be blank for vocabulary-inferred identity. Got: " + result.source());
    }

    // -------------------------------------------------------------------------
    // Force flag — overrides manual
    // -------------------------------------------------------------------------

    @Test
    void force_overwritesManual() throws Exception {
        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("brief.md"), "# Brief");

        // Write a manual .synthesis.md
        DirectoryIdentity manual = new DirectoryIdentity(
                List.of("strategic-account"),
                List.of("pdf"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Acme",
                null,
                0.99,
                Instant.now(),
                "manual",
                "Hand-crafted — do not overwrite"
        );
        Path synthesisFile = clientDir.resolve(".synthesis.md");
        parser.write(synthesisFile, manual);

        // Run sync --force
        runSync(tempDir, "--force");

        // Assert .synthesis.md updated (no longer "manual")
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertNotEquals("manual", result.source(),
                "--force should overwrite manual identity. Got source: " + result.source());
        assertFalse(result.acceptsTypes().contains("strategic-account"),
                "--force should replace custom types. Types: " + result.acceptsTypes());
    }

    @Test
    void force_overwritesConfigEntry() throws Exception {
        // Even config entries get re-inferred with --force
        writeSynthesisConfig(tempDir,
                "workspace:\n"
                + "  name: test\n"
                + "subWorkspaces:\n"
                + "  - name: \"Acme\"\n"
                + "    path: \"clients/Acme\"\n"
                + "    type: \"client\"\n"
                + "    description: \"Acme client docs\"\n");

        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("standup.md"), "# Standup\nNotes from meeting");

        // Run sync --force — bypasses config entry path, falls through to inference
        runSync(tempDir, "--force");

        Path synthesisFile = clientDir.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile),
                ".synthesis.md should be written even with --force");
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertNotEquals("config entry", result.source(),
                "--force bypasses config entry path. Got source: " + result.source());
    }

    // -------------------------------------------------------------------------
    // Verbose output for manual/config entries
    // -------------------------------------------------------------------------

    @Test
    void verbose_manualSkip_showsManualLine() throws Exception {
        Path clientDir = Files.createDirectories(tempDir.resolve("clients/Acme"));
        Files.writeString(clientDir.resolve("brief.md"), "# Brief");

        DirectoryIdentity manual = new DirectoryIdentity(
                List.of("strategic"),
                List.of("pdf"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "Acme",
                null,
                0.99,
                Instant.now(),
                "manual",
                "manual identity"
        );
        parser.write(clientDir.resolve(".synthesis.md"), manual);

        String output = runSync(tempDir, "--verbose");

        assertTrue(output.contains("[MANUAL]"),
                "Verbose mode should show [MANUAL] for skipped manual entries. Output: " + output);
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Initializes a minimal workspace with .synthesis directory and a basic config.
     */
    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        writeSynthesisConfig(root,
                "workspace:\n"
                + "  name: test\n"
                + "scan:\n"
                + "  useSmartDefaults: false\n");
    }

    /**
     * Writes a config.yaml to .synthesis/ directory (overwrites if exists).
     */
    private void writeSynthesisConfig(Path root, String yaml) throws IOException {
        Path configDir = Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(configDir.resolve("config.yaml"), yaml);
    }

    /**
     * Runs the SyncCommand against the given workspace root and returns captured stdout.
     */
    private String runSync(Path workspaceRoot, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));

        try {
            SyncCommand cmd = new SyncCommand();
            SynthesisApp app = new SynthesisApp();
            // Set workspace root directly via reflection to avoid picocli subcommand sharing issues
            Field rootField = SynthesisApp.class.getDeclaredField("workspaceRoot");
            rootField.setAccessible(true);
            rootField.set(app, workspaceRoot.toAbsolutePath().normalize());
            cmd.setParent(app);

            if (extraArgs.length > 0) {
                CommandLine syncCmdLine = new CommandLine(cmd);
                syncCmdLine.parseArgs(extraArgs);
            }

            cmd.call();
        } finally {
            System.setOut(originalOut);
        }

        return baos.toString();
    }
}
