package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for gitignore-style glob pattern support in {@code .synthesisignore}.
 *
 * <p>Issue #324: Path patterns with embedded {@code /} (e.g. {@code .claude/worktrees/})
 * and glob patterns (e.g. {@code **&#47;.archive/**}) were silently ignored — only bare
 * directory names like {@code node_modules/} worked.
 *
 * <p>This suite locks in the supported pattern shapes:
 * <ul>
 *   <li>Bare directory names still match at any depth (backward compat)</li>
 *   <li>Path patterns with embedded {@code /} are anchored to workspace root (gitignore semantics)</li>
 *   <li>Patterns with {@code **} match descendants via the existing {@code dummy} heuristic</li>
 * </ul>
 */
class SynthesisIgnoreGlobTest {

    @TempDir
    Path workspace;

    private static SynthesisConfig.ScanConfig minimalConfig() {
        SynthesisConfig.ScanConfig cfg = new SynthesisConfig.ScanConfig();
        cfg.setUseSmartDefaults(false); // isolate to .synthesisignore behavior
        return cfg;
    }

    private static boolean foundFile(ScanResult result, String fileName) {
        return result.files().stream().anyMatch(f -> f.fileName().equals(fileName));
    }

    // =========================================================================
    // Backward compatibility — bare directory names
    // =========================================================================

    @Test
    void bare_dir_name_with_trailing_slash_excludes_at_any_depth() throws IOException {
        Files.writeString(workspace.resolve(".synthesisignore"), "node_modules/\n");
        Path nm = Files.createDirectories(workspace.resolve("frontend/node_modules"));
        Files.writeString(nm.resolve("lodash.js"), "// lodash");
        Files.writeString(workspace.resolve("frontend/index.js"), "console.log('hi');");

        ScanResult result = new DirectoryScanner(workspace, minimalConfig(), false).scan();

        assertTrue(foundFile(result, "index.js"), "index.js should be scanned");
        assertFalse(foundFile(result, "lodash.js"),
                "lodash.js inside frontend/node_modules/ should be excluded by bare name pattern");
    }

    @Test
    void bare_dir_name_without_trailing_slash_excludes_at_any_depth() throws IOException {
        Files.writeString(workspace.resolve(".synthesisignore"), "target\n");
        Path target = Files.createDirectories(workspace.resolve("module/target"));
        Files.writeString(target.resolve("classes.jar"), "binary");
        Files.writeString(workspace.resolve("module/Main.java"), "class Main {}");

        ScanResult result = new DirectoryScanner(workspace, minimalConfig(), false).scan();

        assertTrue(foundFile(result, "Main.java"), "Main.java should be scanned");
        assertFalse(foundFile(result, "classes.jar"),
                "classes.jar inside module/target/ should be excluded by bare name pattern (no trailing /)");
    }

    // =========================================================================
    // #324 fix — path patterns with embedded slash
    // =========================================================================

    @Test
    void path_pattern_with_embedded_slash_excludes_root_dir() throws IOException {
        // Issue #324: this pattern was silently ignored; only bare names worked.
        Files.writeString(workspace.resolve(".synthesisignore"), ".claude/worktrees/\n");
        Path wt = Files.createDirectories(workspace.resolve(".claude/worktrees"));
        Files.writeString(wt.resolve("scratch.md"), "# scratch");
        Files.writeString(workspace.resolve("README.md"), "# project");

        ScanResult result = new DirectoryScanner(workspace, minimalConfig(), false).scan();

        assertTrue(foundFile(result, "README.md"), "README.md should be scanned");
        assertFalse(foundFile(result, "scratch.md"),
                "scratch.md inside .claude/worktrees/ should be excluded by path pattern (#324)");
    }

    @Test
    void glob_with_doublestar_excludes_descendants() throws IOException {
        Files.writeString(workspace.resolve(".synthesisignore"), "**/.archive/**\n");
        Path archive = Files.createDirectories(workspace.resolve("project/.archive"));
        Files.writeString(archive.resolve("old.md"), "# old");
        Files.writeString(workspace.resolve("project/README.md"), "# project");

        ScanResult result = new DirectoryScanner(workspace, minimalConfig(), false).scan();

        assertTrue(foundFile(result, "README.md"), "README.md should be scanned");
        assertFalse(foundFile(result, "old.md"),
                "old.md inside project/.archive/ should be excluded by **/.archive/** pattern (#324)");
    }

    @Test
    void path_pattern_is_root_anchored() throws IOException {
        // Gitignore semantics: a pattern with a / in the middle is anchored to the ignorefile's directory.
        // So ".claude/worktrees/" matches .claude/worktrees at root, NOT nested/.claude/worktrees.
        Files.writeString(workspace.resolve(".synthesisignore"), ".claude/worktrees/\n");
        Path nested = Files.createDirectories(workspace.resolve("nested/.claude/worktrees"));
        Files.writeString(nested.resolve("nested-scratch.md"), "# nested");

        ScanResult result = new DirectoryScanner(workspace, minimalConfig(), false).scan();

        assertTrue(foundFile(result, "nested-scratch.md"),
                "Path patterns are root-anchored; nested/.claude/worktrees/ should NOT be excluded");
    }
}
