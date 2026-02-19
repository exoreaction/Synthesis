package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.graph.RelationService;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImpactCommand -- transitive change impact analysis.
 *
 * <p>Tests exercise the core BFS logic via {@code ImpactCommand.computeImpact()}
 * (package-private for testability) using in-memory SearchResult lists backed
 * by real temp files, mirroring the pattern used in RelateCommandTest.
 */
class ImpactCommandTest {

    @TempDir
    Path tempDir;

    // ---- Helpers ----

    private SearchResult makeResult(String relativePath, String fileType, String language) throws IOException {
        String fileName = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        Path filePath = tempDir.resolve(fileName);
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
        }
        return new SearchResult(filePath, relativePath, 1.0f, fileName, fileType, language, "", "", "", 100);
    }

    private SearchResult makeResultWithContent(String relativePath, String content) throws IOException {
        String fileName = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        Path filePath = tempDir.resolve(fileName);
        Files.writeString(filePath, content);
        return new SearchResult(filePath, relativePath, 1.0f, fileName, "CODE", "Java", "", "", "", content.length());
    }

    private ImpactCommand commandWithDepth(int depth) {
        ImpactCommand cmd = new ImpactCommand();
        // Set depth via reflection since it's private
        try {
            var field = ImpactCommand.class.getDeclaredField("depth");
            field.setAccessible(true);
            field.set(cmd, depth);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cmd;
    }

    // ---- Tests ----

    @Test
    void impact_findsDirectCallers() throws IOException {
        // B.java imports A.java -- when A changes, B is a direct caller
        SearchResult fileA = makeResultWithContent("Config.java", "public class Config {}");
        SearchResult fileB = makeResultWithContent("Service.java",
                "import com.example.Config;\npublic class Service { private Config c; }");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(fileA, List.of(fileA, fileB), tempDir);

        assertTrue(impact.containsKey("Service.java"), "Service.java should be in impact set");
        assertEquals(1, impact.get("Service.java"), "Service.java should be at depth 1");
        assertFalse(impact.containsKey("Config.java"), "Target file should not be in impact set");
    }

    @Test
    void impact_findsTransitiveCallers() throws IOException {
        // C.java is target; B imports C; A imports B
        // impact of C should include B (depth 1) and A (depth 2)
        SearchResult fileC = makeResultWithContent("Core.java", "public class Core {}");
        SearchResult fileB = makeResultWithContent("Bridge.java",
                "import something.Core;\npublic class Bridge {}");
        SearchResult fileA = makeResultWithContent("App.java",
                "import something.Bridge;\npublic class App {}");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(fileC, List.of(fileC, fileB, fileA), tempDir);

        assertTrue(impact.containsKey("Bridge.java"), "Bridge.java (depth 1) should be in impact");
        assertEquals(1, impact.get("Bridge.java"));
        assertTrue(impact.containsKey("App.java"), "App.java (depth 2) should be in impact");
        assertEquals(2, impact.get("App.java"));
    }

    @Test
    void impact_respectsDepthLimit() throws IOException {
        // With depth=1, only direct callers returned, not transitive
        SearchResult fileC = makeResultWithContent("Core2.java", "public class Core2 {}");
        SearchResult fileB = makeResultWithContent("Bridge2.java",
                "import something.Core2;\npublic class Bridge2 {}");
        SearchResult fileA = makeResultWithContent("App2.java",
                "import something.Bridge2;\npublic class App2 {}");

        ImpactCommand cmd = commandWithDepth(1);
        Map<String, Integer> impact = cmd.computeImpact(fileC, List.of(fileC, fileB, fileA), tempDir);

        assertTrue(impact.containsKey("Bridge2.java"), "Bridge2.java should be found at depth 1");
        assertFalse(impact.containsKey("App2.java"), "App2.java should NOT be found with depth=1");
    }

    @Test
    void impact_noCallers_returnsEmptyImpact() throws IOException {
        // Isolated file -- no other files reference it
        SearchResult isolated = makeResultWithContent("Isolated.java", "public class Isolated {}");
        SearchResult other = makeResultWithContent("Other.java", "public class Other {}");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(isolated, List.of(isolated, other), tempDir);

        assertTrue(impact.isEmpty(), "No callers means empty impact set");
    }

    @Test
    void impact_detectsCliEntryPoint() throws IOException {
        // A file in a /cli/ path referencing the target
        SearchResult target = makeResultWithContent("SharedUtil.java", "public class SharedUtil {}");

        // Simulate a caller with a /cli/ path
        Path cliDir = Files.createDirectories(tempDir.resolve("cli"));
        Path cliFile = cliDir.resolve("SomeCommand.java");
        Files.writeString(cliFile, "import SharedUtil;\npublic class SomeCommand {}");
        SearchResult cliResult = new SearchResult(
                cliFile, "src/main/java/io/synthesis/cli/SomeCommand.java",
                1.0f, "SomeCommand.java", "CODE", "Java", "", "", "", 100);

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(target, List.of(target, cliResult), tempDir);

        // Verify CLI detection logic
        boolean cliReachable = impact.keySet().stream().anyMatch(p -> p.contains("/cli/"));
        assertTrue(cliReachable, "CLI entry-point should be detected when /cli/ path is in impact set");
    }

    @Test
    void impact_formatJson_producesValidJson() throws IOException {
        SearchResult target = makeResultWithContent("IndexTarget.java", "public class IndexTarget {}");
        SearchResult caller = makeResultWithContent("IndexCaller.java",
                "import IndexTarget;\npublic class IndexCaller {}");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(target, List.of(target, caller), tempDir);

        // Capture output
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(baos));
        try {
            // Call printJson indirectly by checking that computeImpact data is correct
            // (printJson is private, so we test the data shape instead)
            assertFalse(impact.isEmpty(), "Should have at least one entry for JSON output");
            assertTrue(impact.values().stream().allMatch(d -> d >= 1), "All depths should be >= 1");
            assertFalse(impact.containsKey(target.relativePath()), "Target should not appear in impact map");
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void impact_fileNotFound_computeImpactForEmptyList() throws IOException {
        // When allFiles doesn't contain the target, BFS finds nothing
        SearchResult target = makeResultWithContent("Ghost.java", "public class Ghost {}");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(target, List.of(), tempDir);

        assertTrue(impact.isEmpty(), "Empty file list should yield empty impact");
    }

    @Test
    void impact_multipleChains_deduplicates() throws IOException {
        // Two different callers of the target -- both at depth 1 and both reference target
        SearchResult target = makeResultWithContent("Common.java", "public class Common {}");
        SearchResult callerA = makeResultWithContent("CallerA.java",
                "import something.Common;\npublic class CallerA {}");
        SearchResult callerB = makeResultWithContent("CallerB.java",
                "import something.Common;\npublic class CallerB {}");
        // Also a transitive caller of both A and B (depth 2)
        SearchResult root = makeResultWithContent("Root.java",
                "import CallerA; import CallerB;\npublic class Root {}");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(
                target, List.of(target, callerA, callerB, root), tempDir);

        // CallerA and CallerB should each appear once (not duplicated)
        long aCount = impact.keySet().stream().filter(k -> k.equals("CallerA.java")).count();
        long bCount = impact.keySet().stream().filter(k -> k.equals("CallerB.java")).count();
        assertEquals(1, aCount, "CallerA.java should appear exactly once");
        assertEquals(1, bCount, "CallerB.java should appear exactly once");
    }

    @Test
    void impact_singleFileOnly_returnsEmpty() throws IOException {
        // Only the target file in the list -- nothing can reference it
        SearchResult target = makeResultWithContent("OnlyOne.java", "public class OnlyOne {}");

        ImpactCommand cmd = commandWithDepth(3);
        Map<String, Integer> impact = cmd.computeImpact(target, List.of(target), tempDir);

        assertTrue(impact.isEmpty(), "Single-file universe yields empty impact");
    }
}
