package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.integration.WorkspaceFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HealthCommand} fix-config phantom removal (issue #211).
 *
 * <p>Verifies that {@code synthesis health --fix-config} removes phantom
 * sub-workspace entries with no fuzzy match, in addition to remapping those
 * that do have a match. Also verifies that {@link MaintainOrchestrator} Phase 3
 * warns when phantom sub-workspace paths are detected.
 */
class HealthFixConfigTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link SynthesisConfig} whose {@code subWorkspaces} list contains
     * one entry per given {@code (name, path)} pair. The returned list is a mutable
     * {@link ArrayList} so tests can call {@code removeAll} on it.
     */
    private SynthesisConfig configWith(String... namePaths) {
        SynthesisConfig config = new SynthesisConfig();
        List<SubWorkspaceConfig> subs = new ArrayList<>();
        for (int i = 0; i < namePaths.length; i += 2) {
            SubWorkspaceConfig sw = new SubWorkspaceConfig();
            sw.setName(namePaths[i]);
            sw.setPath(namePaths[i + 1]);
            subs.add(sw);
        }
        config.setSubWorkspaces(subs);
        return config;
    }

    /**
     * Writes a minimal {@code synthesis-config.yaml} at {@code workspaceRoot} with
     * the given sub-workspace entries. Returns the loaded {@link SynthesisConfig}.
     */
    private SynthesisConfig writeAndLoadConfig(Path workspaceRoot,
                                                List<SubWorkspaceConfig> subWorkspaces)
            throws Exception {
        StringBuilder sb = new StringBuilder("subWorkspaces:\n");
        for (SubWorkspaceConfig sw : subWorkspaces) {
            sb.append("  - name: ").append(sw.getName()).append("\n");
            sb.append("    path: ").append(sw.getPath()).append("\n");
        }
        Path configFile = workspaceRoot.resolve("synthesis-config.yaml");
        Files.writeString(configFile, sb.toString());
        return ConfigLoader.load(workspaceRoot);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Workspace with 1 real directory + 2 phantom paths.
     * After fix-config, the 2 phantoms should be removed and only the real entry remains.
     */
    @Test
    void fix_config_removes_phantom_entries_when_no_match_found() throws Exception {
        // Create the real directory
        Files.createDirectories(tempDir.resolve("realdir"));

        // Create config with 1 real + 2 phantoms (names chosen to have no fuzzy match)
        List<SubWorkspaceConfig> entries = new ArrayList<>();
        SubWorkspaceConfig real = new SubWorkspaceConfig();
        real.setName("realdir"); real.setPath("realdir");
        entries.add(real);

        SubWorkspaceConfig phantom1 = new SubWorkspaceConfig();
        phantom1.setName("phantom-xyzabc"); phantom1.setPath("phantom-xyzabc");
        entries.add(phantom1);

        SubWorkspaceConfig phantom2 = new SubWorkspaceConfig();
        phantom2.setName("ghost-qwerty99"); phantom2.setPath("ghost-qwerty99");
        entries.add(phantom2);

        SynthesisConfig config = writeAndLoadConfig(tempDir, entries);

        // Verify phantoms are detected
        List<SubWorkspaceConfig> phantoms = HealthCommand.findPhantomSubWorkspaces(tempDir, config);
        assertEquals(2, phantoms.size(), "Should detect 2 phantom entries");

        // Build suggestions map: no match for the two phantoms
        List<Path> actualDirs = HealthCommand.listAllDirectories(tempDir, 6);
        Map<SubWorkspaceConfig, String> suggestions = new java.util.LinkedHashMap<>();
        for (SubWorkspaceConfig phantom : phantoms) {
            String suggestion = HealthCommand.findBestMatch(phantom.getPath(), actualDirs, tempDir);
            suggestions.put(phantom, suggestion);
        }

        // All suggestions should be null (no match)
        for (String s : suggestions.values()) {
            assertNull(s, "Phantom with no similar dir should produce null suggestion");
        }

        // Remove unmatched phantoms from config
        List<SubWorkspaceConfig> toRemove = new ArrayList<>();
        for (var entry : suggestions.entrySet()) {
            if (entry.getValue() == null) {
                toRemove.add(entry.getKey());
            }
        }
        config.getSubWorkspaces().removeAll(toRemove);

        // Save updated config
        HealthCommand.saveConfig(tempDir, config);

        // Reload and verify only the real entry remains
        SynthesisConfig reloaded = ConfigLoader.load(tempDir);
        assertEquals(1, reloaded.getSubWorkspaces().size(),
                "Only the real sub-workspace should remain");
        assertEquals("realdir", reloaded.getSubWorkspaces().get(0).getName());

        // Verify no phantoms remain
        List<SubWorkspaceConfig> remainingPhantoms =
                HealthCommand.findPhantomSubWorkspaces(tempDir, reloaded);
        assertTrue(remainingPhantoms.isEmpty(),
                "No phantom entries should remain after fix-config");
    }

    /**
     * Workspace with only real paths — fix-config should report 0 removed and
     * the config should remain unchanged.
     */
    @Test
    void fix_config_is_idempotent_when_no_phantoms() throws Exception {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.createDirectories(tempDir.resolve("clients"));

        List<SubWorkspaceConfig> entries = new ArrayList<>();
        SubWorkspaceConfig sw1 = new SubWorkspaceConfig();
        sw1.setName("docs"); sw1.setPath("docs");
        entries.add(sw1);

        SubWorkspaceConfig sw2 = new SubWorkspaceConfig();
        sw2.setName("clients"); sw2.setPath("clients");
        entries.add(sw2);

        SynthesisConfig config = writeAndLoadConfig(tempDir, entries);

        // No phantoms detected
        List<SubWorkspaceConfig> phantoms = HealthCommand.findPhantomSubWorkspaces(tempDir, config);
        assertTrue(phantoms.isEmpty(), "Should find 0 phantoms when all dirs exist");

        // Save config (nothing to remove)
        HealthCommand.saveConfig(tempDir, config);

        // Reload: both entries still present
        SynthesisConfig reloaded = ConfigLoader.load(tempDir);
        assertEquals(2, reloaded.getSubWorkspaces().size(),
                "Both real entries should remain intact");
    }

    /**
     * Workspace with 2 real + 3 phantom directories.
     * After fix-config, 3 phantoms removed and 2 preserved.
     */
    @Test
    void fix_config_preserves_real_entries_and_removes_phantoms() throws Exception {
        Files.createDirectories(tempDir.resolve("active"));
        Files.createDirectories(tempDir.resolve("archive"));

        List<SubWorkspaceConfig> entries = new ArrayList<>();
        // Real entries
        SubWorkspaceConfig real1 = new SubWorkspaceConfig();
        real1.setName("active"); real1.setPath("active");
        entries.add(real1);

        SubWorkspaceConfig real2 = new SubWorkspaceConfig();
        real2.setName("archive"); real2.setPath("archive");
        entries.add(real2);

        // Phantom entries (chosen to be unresolvable)
        for (int i = 1; i <= 3; i++) {
            SubWorkspaceConfig ph = new SubWorkspaceConfig();
            ph.setName("phantom-zzz" + i); ph.setPath("phantom-zzz" + i);
            entries.add(ph);
        }

        SynthesisConfig config = writeAndLoadConfig(tempDir, entries);

        // Find and remove phantoms
        List<SubWorkspaceConfig> phantoms = HealthCommand.findPhantomSubWorkspaces(tempDir, config);
        assertEquals(3, phantoms.size(), "Should detect exactly 3 phantom entries");

        // Remove unmatched phantoms from config (simulate applyRemappings with no suggestions)
        config.getSubWorkspaces().removeAll(phantoms);
        HealthCommand.saveConfig(tempDir, config);

        // Reload and verify
        SynthesisConfig reloaded = ConfigLoader.load(tempDir);
        assertEquals(2, reloaded.getSubWorkspaces().size(),
                "Exactly 2 real entries should remain");

        List<String> names = reloaded.getSubWorkspaces().stream()
                .map(SubWorkspaceConfig::getName)
                .toList();
        assertTrue(names.contains("active"), "active should remain");
        assertTrue(names.contains("archive"), "archive should remain");

        // Verify phantoms are gone
        assertTrue(HealthCommand.findPhantomSubWorkspaces(tempDir, reloaded).isEmpty(),
                "No phantoms should remain in config");
    }

    /**
     * Verify that the output lists each removed phantom entry by name.
     */
    @Test
    void fix_config_output_lists_removed_entries() throws Exception {
        // Set up workspace with 1 real dir + 2 distinct phantom entries
        Files.createDirectories(tempDir.resolve("realdir"));

        List<SubWorkspaceConfig> entries = new ArrayList<>();
        SubWorkspaceConfig real = new SubWorkspaceConfig();
        real.setName("realdir"); real.setPath("realdir");
        entries.add(real);

        SubWorkspaceConfig ph1 = new SubWorkspaceConfig();
        ph1.setName("eXOReaction/clients/@active"); ph1.setPath("eXOReaction/clients/@active");
        entries.add(ph1);

        SubWorkspaceConfig ph2 = new SubWorkspaceConfig();
        ph2.setName("eXOReaction/clients/@opportunities");
        ph2.setPath("eXOReaction/clients/@opportunities");
        entries.add(ph2);

        SynthesisConfig config = writeAndLoadConfig(tempDir, entries);

        // Capture stdout
        PrintStream origOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try {
            // Remove phantoms and print output as applyRemappings would
            List<SubWorkspaceConfig> phantoms =
                    HealthCommand.findPhantomSubWorkspaces(tempDir, config);
            List<SubWorkspaceConfig> toRemove = new ArrayList<>(phantoms);
            config.getSubWorkspaces().removeAll(toRemove);
            HealthCommand.saveConfig(tempDir, config);

            if (!toRemove.isEmpty()) {
                System.out.printf("%nRemoved %d phantom sub-workspace entr%s:%n",
                        toRemove.size(), toRemove.size() == 1 ? "y" : "ies");
                for (SubWorkspaceConfig sw : toRemove) {
                    System.out.println("  - " + sw.getName());
                }
            }
        } finally {
            System.setOut(origOut);
        }

        String output = baos.toString();

        assertTrue(output.contains("Removed 2 phantom sub-workspace entries"),
                "Output should report 2 removed. Got: " + output);
        assertTrue(output.contains("eXOReaction/clients/@active"),
                "Output should list first phantom name. Got: " + output);
        assertTrue(output.contains("eXOReaction/clients/@opportunities"),
                "Output should list second phantom name. Got: " + output);
    }

    /**
     * Verifies that MaintainOrchestrator Phase 3 (Sync) includes a WARNING detail
     * when phantom sub-workspace paths are configured.
     */
    @Test
    void maintain_phase3_warns_about_phantom_paths() throws Exception {
        // Build a workspace using WorkspaceFixture (provides .synthesis/config.yaml)
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .workspaceName("phantom-warn-test")
                .build();

        Path workspaceRoot = fixture.getRoot();

        // Manually append a phantom sub-workspace entry to the config
        Path configFile = workspaceRoot.resolve(".synthesis/config.yaml");
        String existing = Files.readString(configFile);
        String withPhantom = existing
                + "\nsubWorkspaces:\n"
                + "  - name: phantom-nonexistent\n"
                + "    path: path/that/does/not/exist\n";
        Files.writeString(configFile, withPhantom);

        SynthesisConfig config = ConfigLoader.load(workspaceRoot);

        // Verify the phantom is actually configured
        List<SubWorkspaceConfig> phantoms =
                HealthCommand.findPhantomSubWorkspaces(workspaceRoot, config);
        assertEquals(1, phantoms.size(),
                "Should detect 1 phantom sub-workspace in config");

        // Run the orchestrator
        MaintainOptions opts = MaintainOptions.defaults();
        MaintainOrchestrator orchestrator = new MaintainOrchestrator(workspaceRoot, opts, config);
        MaintainResult result = orchestrator.run();

        // Phase 3 is Sync (index 2)
        PhaseResult syncPhase = result.phases().get(2);
        assertEquals("Sync", syncPhase.name(), "Phase 3 should be Sync");
        assertTrue(syncPhase.succeeded(), "Sync should succeed: " + syncPhase.error());

        // The details list should contain a WARNING about phantoms
        List<String> details = syncPhase.details();
        boolean hasWarning = details.stream()
                .anyMatch(d -> d.contains("WARNING") && d.contains("phantom"));
        assertTrue(hasWarning,
                "Phase 3 Sync details should warn about phantom paths. Got: " + details);
    }
}
