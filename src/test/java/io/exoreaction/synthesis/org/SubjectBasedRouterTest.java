package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for unified routing via DirectoryIdentityRouter (P1-05).
 *
 * <p>These tests were originally written for SubjectBasedRouter (issue #201)
 * and have been converted to verify equivalent behavior through the unified
 * DirectoryIdentityRouter with skipTransient=true.
 *
 * <p><b>Threshold mapping:</b>
 * <ul>
 *   <li>Old SubjectBasedRouter 0.4 (E010) -> DirectoryScorer ~0.25</li>
 *   <li>Old SubjectBasedRouter 0.7 (rebalance) -> DirectoryScorer ~0.5</li>
 * </ul>
 */
class SubjectBasedRouterTest {

    @TempDir
    Path tempDir;

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    /**
     * Creates a directory with a .synthesis.md identity file including aliases.
     */
    private Path createDirWithAliases(String relativePath, List<String> types, List<String> formats,
                                       List<String> aliases, double confidence)
            throws IOException {
        Path dir = tempDir.resolve(relativePath);
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                types, formats, List.of(),
                ScopeLevel.WORKSPACE, null, null,
                confidence, null, "test", "",
                List.of(), aliases, false, List.of()
        );
        parser.write(dir.resolve(".synthesis.md"), identity);
        return dir;
    }

    @Test
    void synthesisDemo_routesTo_synthesisMediaDir() throws IOException {
        // Create products/Synthesis/media/ with alias "synthesis"
        createDirWithAliases("products/Synthesis/media",
                List.of("media", "video"), List.of("mp4"),
                List.of("synthesis"), 0.9);

        Path file = tempDir.resolve("synthesis-demo.mp4");

        // Using DirectoryIdentityRouter: "synthesis" in filename matches directory path token.
        // Type match (media, generic: +0.15) + format match (mp4: +0.2) + token match bonus
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Optional<DirectoryIdentityRouter.RouteResult> result =
                router.route(file, 0.25, true);

        assertTrue(result.isPresent(),
                "synthesis-demo.mp4 should route to products/Synthesis/media/");
        assertTrue(result.get().directory().endsWith("products/Synthesis/media"),
                "Destination should be Synthesis/media, got: " + result.get().directory());
        assertTrue(result.get().score() >= 0.25,
                "Score should be >= 0.25, got: " + result.get().score());
    }

    @Test
    void auroraAnalyticsDemo_routesTo_xorceryAaaMedia() throws IOException {
        // Create products/xorcery-aaa/media/ with aliases "aurora", "alchemy"
        createDirWithAliases("products/xorcery-aaa/media",
                List.of("media", "video"), List.of("mp4"),
                List.of("aurora", "alchemy", "analytics"), 0.9);

        Path file = tempDir.resolve("aurora-analytics-demo.mp4");

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Optional<DirectoryIdentityRouter.RouteResult> result =
                router.route(file, 0.25, true);

        assertTrue(result.isPresent(),
                "aurora-analytics-demo.mp4 should route to xorcery-aaa/media/");
        assertTrue(result.get().directory().toString().contains("xorcery-aaa"),
                "Destination should contain xorcery-aaa, got: " + result.get().directory());
    }

    @Test
    void randomTalk_returnsEmpty_whenNoDirExceedsThreshold() throws IOException {
        // Create a Synthesis dir but the file "random-talk.mp4" won't match strongly
        createDirWithAliases("products/Synthesis/media",
                List.of("media"), List.of("mp4"),
                List.of("synthesis"), 0.9);

        Path file = tempDir.resolve("random-talk.mp4");

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Optional<DirectoryIdentityRouter.RouteResult> result =
                router.route(file, 0.5, true);

        assertTrue(result.isEmpty(),
                "random-talk.mp4 should not match any directory above 0.5 threshold");
    }

    @Test
    void higherThreshold_correctlyRejectsWeakMatches() throws IOException {
        createDirWithAliases("products/Synthesis/media",
                List.of("media"), List.of("mp4"),
                List.of("synthesis"), 0.9);

        Path file = tempDir.resolve("synthesis-demo.mp4");

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);

        // Should match at lower threshold
        Optional<DirectoryIdentityRouter.RouteResult> atLow =
                router.route(file, 0.25, true);
        assertTrue(atLow.isPresent(), "Should match at 0.25 threshold");

        // The high threshold test verifies that intermediate-strength matches
        // can be filtered out by raising the threshold
        // DirectoryScorer gives type(generic 0.15) + format(0.2) + token(~0.125) = ~0.475
        Optional<DirectoryIdentityRouter.RouteResult> atHigh =
                router.route(file, 0.9, true);
        assertTrue(atHigh.isEmpty(),
                "synthesis-demo.mp4 should not pass 0.9 threshold");
    }

    @Test
    void transientDirsSkipped_asDestinations_whenSkipTransientEnabled() throws IOException {
        // Create a transient marketing dir — it should NOT be a routing destination when skipTransient=true
        Path marketingDir = tempDir.resolve("marketing");
        Files.createDirectories(marketingDir);
        DirectoryIdentity transientIdentity = new DirectoryIdentity(
                List.of("marketing"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of(), List.of("marketing"), true, List.of()
        );
        parser.write(marketingDir.resolve(".synthesis.md"), transientIdentity);

        Path file = tempDir.resolve("marketing-promo.mp4");

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Optional<DirectoryIdentityRouter.RouteResult> result =
                router.route(file, 0.1, true);

        assertTrue(result.isEmpty(),
                "Transient directories should be skipped when skipTransient=true");
    }

    @Test
    void transientDirs_includedByDefault() throws IOException {
        // Same transient dir, but now WITHOUT skipTransient — should be considered
        Path marketingDir = tempDir.resolve("marketing");
        Files.createDirectories(marketingDir);
        DirectoryIdentity transientIdentity = new DirectoryIdentity(
                List.of("marketing"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of(), List.of("marketing"), true, List.of()
        );
        parser.write(marketingDir.resolve(".synthesis.md"), transientIdentity);

        Path file = tempDir.resolve("marketing-promo.mp4");

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Optional<DirectoryIdentityRouter.RouteResult> result =
                router.route(file, 0.1, false);

        // With skipTransient=false, the transient marketing dir IS a valid candidate
        assertTrue(result.isPresent(),
                "Transient directories should be considered when skipTransient=false");
    }

    @Test
    void noDirectoriesWithIdentity_returnsEmpty() {
        // No .synthesis.md files anywhere
        Path file = tempDir.resolve("test.mp4");

        DirectoryIdentityRouter router = new DirectoryIdentityRouter(tempDir, null);
        Optional<DirectoryIdentityRouter.RouteResult> result =
                router.route(file, 0.5, true);

        assertTrue(result.isEmpty());
    }
}
