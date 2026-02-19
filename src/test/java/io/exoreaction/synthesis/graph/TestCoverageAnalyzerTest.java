package io.exoreaction.synthesis.graph;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TestCoverageAnalyzerTest {
    private final TestCoverageAnalyzer analyzer = new TestCoverageAnalyzer();
    private SearchResult makeResult(String relPath, String fileName) {
        return new SearchResult(null, relPath, 1.0f, fileName, "java", "Java", null, null, null, 100L);
    }
    @Test
    void findTests_conventionMatch(@TempDir Path tmp) throws IOException {
        SearchResult source = makeResult("src/main/Foo.java", "Foo.java");
        Path tf = tmp.resolve("src/test/FooTest.java");
        Files.createDirectories(tf.getParent());
        Files.writeString(tf, "class FooTest {\n    @Test void a(){}\n    @Test void b(){}\n}");
        SearchResult tr = makeResult("src/test/FooTest.java", "FooTest.java");
        List<SearchResult> all = Arrays.asList(source, tr);
        TestCoverageAnalyzer.TestCoverageResult r = analyzer.findTests(source, all, tmp);
        assertEquals(1, r.testClasses().size());
        assertEquals("FooTest.java", r.testClasses().get(0).fileName());
        assertEquals("convention", r.testClasses().get(0).detectionMethod());
        assertEquals(2, r.testClasses().get(0).testMethodCount());
        assertEquals(2, r.testMethodCount());
    }
    @Test
    void findTests_importMatch(@TempDir Path tmp) throws IOException {
        SearchResult source = makeResult("src/main/BarService.java", "BarService.java");
        Path tf = tmp.resolve("src/test/BarIntegrationTest.java");
        Files.createDirectories(tf.getParent());
        Files.writeString(tf, "import com.example.BarService;\nclass BarIT {\n    @Test void t(){}\n}");
        SearchResult tr = makeResult("src/test/BarIntegrationTest.java", "BarIntegrationTest.java");
        List<SearchResult> all = Arrays.asList(source, tr);
        TestCoverageAnalyzer.TestCoverageResult r = analyzer.findTests(source, all, tmp);
        assertEquals(1, r.testClasses().size());
        assertEquals("BarIntegrationTest.java", r.testClasses().get(0).fileName());
        assertEquals("import", r.testClasses().get(0).detectionMethod());
        assertEquals(1, r.testMethodCount());
    }
    @Test
    void findTests_noMatch_returnsEmpty(@TempDir Path tmp) throws IOException {
        SearchResult source = makeResult("src/main/Qux.java", "Qux.java");
        List<SearchResult> all = Collections.singletonList(source);
        TestCoverageAnalyzer.TestCoverageResult r = analyzer.findTests(source, all, tmp);
        assertTrue(r.testClasses().isEmpty());
        assertEquals(0, r.testMethodCount());
        assertEquals("src/main/Qux.java", r.sourceFile());
    }
    @Test
    void findTests_countTestMethods(@TempDir Path tmp) throws IOException {
        SearchResult source = makeResult("src/main/Alpha.java", "Alpha.java");
        Path tf = tmp.resolve("src/test/AlphaTest.java");
        Files.createDirectories(tf.getParent());
        Files.writeString(tf, "class AT {\n    @Test void a(){}\n    @Test void b(){}\n    @Test void c(){}\n}");
        SearchResult tr = makeResult("src/test/AlphaTest.java", "AlphaTest.java");
        List<SearchResult> all = Arrays.asList(source, tr);
        TestCoverageAnalyzer.TestCoverageResult r = analyzer.findTests(source, all, tmp);
        assertEquals(3, r.testMethodCount());
    }
    @Test
    void findTests_deduplicates(@TempDir Path tmp) throws IOException {
        SearchResult source = makeResult("src/main/Dup.java", "Dup.java");
        Path tf = tmp.resolve("src/test/DupTest.java");
        Files.createDirectories(tf.getParent());
        Files.writeString(tf, "import com.example.Dup;\nclass DupTest {\n    @Test void t(){}\n}");
        SearchResult tr = makeResult("src/test/DupTest.java", "DupTest.java");
        List<SearchResult> all = Arrays.asList(source, tr);
        TestCoverageAnalyzer.TestCoverageResult r = analyzer.findTests(source, all, tmp);
        assertEquals(1, r.testClasses().size(), "Should not duplicate");
    }
    @Test
    void findUntested_returnsUntestedFiles() {
        SearchResult s1 = makeResult("src/main/Tested.java", "Tested.java");
        SearchResult t1 = makeResult("src/test/TestedTest.java", "TestedTest.java");
        SearchResult s2 = makeResult("src/main/Untested.java", "Untested.java");
        List<SearchResult> all = Arrays.asList(s1, t1, s2);
        List<SearchResult> untested = analyzer.findUntested(all);
        assertEquals(1, untested.size());
        assertEquals("Untested.java", untested.get(0).fileName());
    }
    @Test
    void findUntested_excludesTestFilesThemselves() {
        SearchResult tf = makeResult("src/test/SomeTest.java", "SomeTest.java");
        List<SearchResult> all = Collections.singletonList(tf);
        assertTrue(analyzer.findUntested(all).isEmpty());
    }
    @Test
    void findUntested_excludesTestDirectoryFiles() {
        SearchResult src = makeResult("src/test/Helper.java", "Helper.java");
        List<SearchResult> all = Collections.singletonList(src);
        assertTrue(analyzer.findUntested(all).isEmpty());
    }
}
