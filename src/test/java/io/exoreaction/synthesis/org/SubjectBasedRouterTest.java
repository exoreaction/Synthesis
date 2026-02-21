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
 * Tests for issue #201: SubjectBasedRouter.
 */
class SubjectBasedRouterTest {

    @TempDir
    Path tempDir;

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();
    private final SubjectBasedRouter router = new SubjectBasedRouter();

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

        // synthesis-demo has tokens [synthesis, demo]; dir tokens include [products, synthesis, media]
        // 1 match (synthesis) / 2 = 0.5 * 0.9 confidence = 0.45
        Optional<SubjectBasedRouter.RoutingDecision> result =
                router.findBestMatch(file, tempDir, 0.4);

        assertTrue(result.isPresent(),
                "synthesis-demo.mp4 should route to products/Synthesis/media/");
        assertTrue(result.get().destination().endsWith("products/Synthesis/media"),
                "Destination should be Synthesis/media, got: " + result.get().destination());
        assertTrue(result.get().score() >= 0.4,
                "Score should be >= 0.4, got: " + result.get().score());
    }

    @Test
    void auroraAnalyticsDemo_routesTo_xorceryAaaMedia() throws IOException {
        // Create products/xorcery-aaa/media/ with aliases "aurora", "alchemy"
        // Also add alias "analytics" to improve matching
        createDirWithAliases("products/xorcery-aaa/media",
                List.of("media", "video"), List.of("mp4"),
                List.of("aurora", "alchemy", "analytics"), 0.9);

        Path file = tempDir.resolve("aurora-analytics-demo.mp4");

        // aurora-analytics-demo tokens: [aurora, analytics, demo]
        // dir tokens: [products, xorcery, aaa, media, aurora, alchemy, analytics]
        // 2 matches (aurora, analytics) / 3 = 0.667 * 0.9 = 0.6
        Optional<SubjectBasedRouter.RoutingDecision> result =
                router.findBestMatch(file, tempDir, 0.5);

        assertTrue(result.isPresent(),
                "aurora-analytics-demo.mp4 should route to xorcery-aaa/media/");
        assertTrue(result.get().destination().toString().contains("xorcery-aaa"),
                "Destination should contain xorcery-aaa, got: " + result.get().destination());
    }

    @Test
    void randomTalk_returnsEmpty_whenNoDirExceedsThreshold() throws IOException {
        // Create a Synthesis dir but the file "random-talk.mp4" won't match
        createDirWithAliases("products/Synthesis/media",
                List.of("media"), List.of("mp4"),
                List.of("synthesis"), 0.9);

        Path file = tempDir.resolve("random-talk.mp4");

        Optional<SubjectBasedRouter.RoutingDecision> result =
                router.findBestMatch(file, tempDir, 0.7);

        assertTrue(result.isEmpty(),
                "random-talk.mp4 should not match any directory above 0.7 threshold");
    }

    @Test
    void higherThreshold_correctlyRejectsWeakMatches() throws IOException {
        // synthesis-demo has 1/2 tokens matching ("synthesis" matches)
        // With confidence 0.9 => score = 0.5 * 0.9 = 0.45
        // That should pass 0.4 threshold but fail 0.8 threshold
        createDirWithAliases("products/Synthesis/media",
                List.of("media"), List.of("mp4"),
                List.of("synthesis"), 0.9);

        Path file = tempDir.resolve("synthesis-demo.mp4");

        Optional<SubjectBasedRouter.RoutingDecision> at04 =
                router.findBestMatch(file, tempDir, 0.4);
        assertTrue(at04.isPresent(), "Should match at 0.4 threshold");

        // At 0.8, the 0.45 score should fail
        Optional<SubjectBasedRouter.RoutingDecision> at08 =
                router.findBestMatch(file, tempDir, 0.8);
        // The file has tokens: [synthesis, demo], dir has: [products, synthesis, media]
        // 1 match (synthesis) / 2 tokens = 0.5 * 0.9 confidence = 0.45
        assertTrue(at08.isEmpty(),
                "synthesis-demo.mp4 with score 0.45 should not pass 0.8 threshold");
    }

    @Test
    void transientDirsSkipped_asDestinations() throws IOException {
        // Create a transient marketing dir — it should NOT be a routing destination
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

        Optional<SubjectBasedRouter.RoutingDecision> result =
                router.findBestMatch(file, tempDir, 0.3);

        assertTrue(result.isEmpty(),
                "Transient directories should be skipped as routing destinations");
    }

    @Test
    void noDirectoriesWithIdentity_returnsEmpty() {
        // No .synthesis.md files anywhere
        Path file = tempDir.resolve("test.mp4");

        Optional<SubjectBasedRouter.RoutingDecision> result =
                router.findBestMatch(file, tempDir, 0.5);

        assertTrue(result.isEmpty());
    }

    // ---- tokenize tests ----

    @Test
    void tokenizeFileName_splitsOnDashes() {
        Set<String> tokens = SubjectBasedRouter.tokenizeFileName("synthesis-taming-the-ai-torrent.mp4");
        assertTrue(tokens.contains("synthesis"));
        assertTrue(tokens.contains("taming"));
        assertTrue(tokens.contains("torrent"));
        assertTrue(tokens.contains("the"), "'the' is exactly 3 chars, passes >= 3 filter");
        assertFalse(tokens.contains("ai"), "'ai' is < 3 chars");
        assertFalse(tokens.contains("mp4"), "extension should be stripped");
    }

    @Test
    void tokenizeFileName_splitsCamelCase() {
        Set<String> tokens = SubjectBasedRouter.tokenizeFileName("AuroraTemporalAnalytics.mp4");
        assertTrue(tokens.contains("aurora"));
        assertTrue(tokens.contains("temporal"));
        assertTrue(tokens.contains("analytics"));
    }

    @Test
    void tokenize_handlesAliasStrings() {
        Set<String> tokens = SubjectBasedRouter.tokenize("knowledge-infrastructure");
        assertTrue(tokens.contains("knowledge"));
        assertTrue(tokens.contains("infrastructure"));
    }
}
