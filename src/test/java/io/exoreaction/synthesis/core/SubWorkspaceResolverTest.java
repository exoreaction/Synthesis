package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubWorkspaceResolver — path prefix matching, longest-prefix wins,
 * null/empty edge cases, and absolute-path resolution.
 */
class SubWorkspaceResolverTest {

    // --- empty / no sub-workspaces ---

    @Test
    void noSubWorkspaces_resolve_returnsNull() {
        SubWorkspaceResolver resolver = new SubWorkspaceResolver(List.of());
        assertNull(resolver.resolve("src/Main.java"));
    }

    @Test
    void nullSubWorkspaces_resolve_returnsNull() {
        SubWorkspaceResolver resolver = new SubWorkspaceResolver((List<SynthesisConfig.SubWorkspaceConfig>) null);
        assertNull(resolver.resolve("src/Main.java"));
    }

    @Test
    void noSubWorkspaces_hasSubWorkspaces_returnsFalse() {
        SubWorkspaceResolver resolver = new SubWorkspaceResolver(List.of());
        assertFalse(resolver.hasSubWorkspaces());
    }

    @Test
    void noSubWorkspaces_count_isZero() {
        SubWorkspaceResolver resolver = new SubWorkspaceResolver(List.of());
        assertEquals(0, resolver.count());
    }

    // --- basic prefix matching ---

    @Test
    void singleSubWorkspace_matchingPrefix_returnsName() {
        SubWorkspaceResolver resolver = resolverWith("backend", "backend");
        assertEquals("backend", resolver.resolve("backend/src/Main.java"));
    }

    @Test
    void singleSubWorkspace_nonMatchingPath_returnsNull() {
        SubWorkspaceResolver resolver = resolverWith("backend", "backend");
        assertNull(resolver.resolve("frontend/src/App.tsx"));
    }

    @Test
    void singleSubWorkspace_exactPathMatch_returnsName() {
        // Exact match: relativePath.equals(prefix)
        SubWorkspaceResolver resolver = resolverWith("lib", "lib");
        assertEquals("lib", resolver.resolve("lib"));
    }

    // --- trailing slash normalization ---

    @Test
    void trailingSlashInConfig_matchesFilesUnder() {
        // Config with trailing slash should still match
        SubWorkspaceResolver resolver = resolverWith("frontend", "frontend/");
        assertEquals("frontend", resolver.resolve("frontend/App.tsx"));
    }

    // --- longest prefix wins ---

    @ParameterizedTest
    @CsvSource({
        "services/auth/src/Main.java,       auth",
        "services/billing/src/Main.java,    billing",
        "services/shared/Common.java,       services"
    })
    void longestPrefix_winsOverShorter(String path, String expectedName) {
        List<SynthesisConfig.SubWorkspaceConfig> configs = List.of(
                sub("services", "services"),
                sub("auth", "services/auth"),
                sub("billing", "services/billing")
        );
        SubWorkspaceResolver resolver = new SubWorkspaceResolver(configs);
        assertEquals(expectedName, resolver.resolve(path));
    }

    @Test
    void longestPrefix_noMatch_returnsNull() {
        List<SynthesisConfig.SubWorkspaceConfig> configs = List.of(
                sub("services", "services"),
                sub("auth", "services/auth")
        );
        SubWorkspaceResolver resolver = new SubWorkspaceResolver(configs);
        assertNull(resolver.resolve("docs/README.md"));
    }

    // --- null relativePath ---

    @Test
    void resolve_nullPath_returnsNull() {
        SubWorkspaceResolver resolver = resolverWith("backend", "backend");
        assertNull(resolver.resolve((String) null));
    }

    // --- hasSubWorkspaces / count ---

    @Test
    void withSubWorkspaces_hasSubWorkspaces_returnsTrue() {
        SubWorkspaceResolver resolver = resolverWith("ws", "ws");
        assertTrue(resolver.hasSubWorkspaces());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void count_matchesNumberOfSubWorkspaces(int n) {
        List<SynthesisConfig.SubWorkspaceConfig> configs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            configs.add(sub("ws" + i, "path" + i));
        }
        SubWorkspaceResolver resolver = new SubWorkspaceResolver(configs);
        assertEquals(n, resolver.count());
    }

    // --- findByName ---

    @Test
    void findByName_existingName_returnsConfig() {
        SubWorkspaceResolver resolver = resolverWith("backend", "backend");
        SynthesisConfig.SubWorkspaceConfig found = resolver.findByName("backend");
        assertNotNull(found);
        assertEquals("backend", found.getName());
    }

    @Test
    void findByName_nonExistentName_returnsNull() {
        SubWorkspaceResolver resolver = resolverWith("backend", "backend");
        assertNull(resolver.findByName("frontend"));
    }

    @Test
    void findByName_null_returnsNull() {
        SubWorkspaceResolver resolver = resolverWith("backend", "backend");
        assertNull(resolver.findByName(null));
    }

    // --- absolute path resolution ---

    @Test
    void resolve_absolutePath_matchingFile_returnsName(@TempDir Path workspaceRoot) {
        SubWorkspaceResolver resolver = resolverWith("src", "src");
        Path srcFile = workspaceRoot.resolve("src").resolve("Main.java");
        assertEquals("src", resolver.resolve(srcFile, workspaceRoot));
    }

    @Test
    void resolve_absolutePath_nonMatchingFile_returnsNull(@TempDir Path workspaceRoot) {
        SubWorkspaceResolver resolver = resolverWith("src", "src");
        Path docsFile = workspaceRoot.resolve("docs").resolve("README.md");
        assertNull(resolver.resolve(docsFile, workspaceRoot));
    }

    @Test
    void resolve_absolutePath_nullFile_returnsNull(@TempDir Path workspaceRoot) {
        SubWorkspaceResolver resolver = resolverWith("src", "src");
        assertNull(resolver.resolve((Path) null, workspaceRoot));
    }

    @Test
    void resolve_absolutePath_nullWorkspaceRoot_returnsNull(@TempDir Path workspaceRoot) {
        SubWorkspaceResolver resolver = resolverWith("src", "src");
        Path srcFile = workspaceRoot.resolve("src").resolve("Main.java");
        assertNull(resolver.resolve(srcFile, null));
    }

    @Test
    void resolve_absolutePath_outsideWorkspace_returnsNull(@TempDir Path workspaceRoot,
                                                            @TempDir Path otherDir) {
        SubWorkspaceResolver resolver = resolverWith("src", "src");
        Path outsideFile = otherDir.resolve("Main.java");
        // otherDir is not relative to workspaceRoot
        assertNull(resolver.resolve(outsideFile, workspaceRoot));
    }

    // --- getSubWorkspaces ---

    @Test
    void getSubWorkspaces_returnsConfiguredList() {
        SubWorkspaceResolver resolver = resolverWith("ws", "ws/path");
        assertEquals(1, resolver.getSubWorkspaces().size());
        assertEquals("ws/path", resolver.getSubWorkspaces().get(0).getPath());
    }

    // --- helpers ---

    private SubWorkspaceResolver resolverWith(String name, String path) {
        return new SubWorkspaceResolver(List.of(sub(name, path)));
    }

    private SynthesisConfig.SubWorkspaceConfig sub(String name, String path) {
        return new SynthesisConfig.SubWorkspaceConfig(name, path);
    }
}
