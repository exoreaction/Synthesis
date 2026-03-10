package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestCoverageAnalyzerTest {

    @TempDir
    Path tempDir;

    private SearchResult makeResult(String relativePath, String fileName) {
        return new SearchResult(tempDir.resolve(relativePath), relativePath, 1.0f,
            fileName, "CODE", "Java", "", "", "", 0L);
    }

    private SearchResult makeResultWithContent(String rel, String name, String content) throws IOException {
        Path file = tempDir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return new SearchResult(file, rel, 1.0f, name, "CODE", "Java", "", "", "", (long) content.length());
    }

    private static final String AT_TEST = "@" + "Test";

    @Test
    void findTests_conventionMatch() throws IOException {
        String content = "public class FooTest {\n    " + AT_TEST + "\n    void t1() {}\n}\n";
        SearchResult fooTest = makeResultWithContent("src/test/FooTest.java", "FooTest.java", content);
        SearchResult foo = makeResult("src/main/Foo.java", "Foo.java");
        var result = new TestCoverageAnalyzer().findTests(foo, List.of(foo, fooTest), tempDir);
        assertEquals(1, result.testClasses().size());
        assertEquals("convention", result.testClasses().get(0).detectionMethod());
    }

    @Test
    void findTests_noMatch_returnsEmpty() throws IOException {
        SearchResult baz = makeResult("src/main/Baz.java", "Baz.java");
        String content = "public class OtherTest { " + AT_TEST + " void t1() {} }";
        SearchResult other = makeResultWithContent("src/test/OtherTest.java", "OtherTest.java", content);
        var result = new TestCoverageAnalyzer().findTests(baz, List.of(baz, other), tempDir);
        assertTrue(result.testClasses().isEmpty());
        assertEquals(0, result.testMethodCount());
    }

    @Test
    void findTests_countTestMethods() throws IOException {
        String content = "public class FooTest {\n    " + AT_TEST + "\n    void t1() {}\n    " + AT_TEST + "\n    void t2() {}\n    " + AT_TEST + "\n    void t3() {}\n}\n";
        SearchResult fooTest = makeResultWithContent("src/test/FooTest.java", "FooTest.java", content);
        SearchResult foo = makeResult("src/main/Foo.java", "Foo.java");
        var result = new TestCoverageAnalyzer().findTests(foo, List.of(foo, fooTest), tempDir);
        assertEquals(1, result.testClasses().size());
        assertEquals(3, result.testClasses().get(0).testMethodCount());
        assertEquals(3, result.testMethodCount());
    }

    @Test
    void findTests_deduplicates() throws IOException {
        String content = "import pkg.Foo;\npublic class FooTest {\n    " + AT_TEST + "\n    void t1() {}\n}\n";
        SearchResult fooTest = makeResultWithContent("src/test/FooTest.java", "FooTest.java", content);
        SearchResult foo = makeResult("src/main/Foo.java", "Foo.java");
        var result = new TestCoverageAnalyzer().findTests(foo, List.of(foo, fooTest), tempDir);
        assertEquals(1, result.testClasses().size());
        assertEquals("convention", result.testClasses().get(0).detectionMethod());
    }

    @Test
    void findUntested_returnsUntestedFiles() {
        SearchResult foo = makeResult("src/main/Foo.java", "Foo.java");
        SearchResult bar = makeResult("src/main/Bar.java", "Bar.java");
        SearchResult barTest = makeResult("src/test/BarTest.java", "BarTest.java");
        var untested = new TestCoverageAnalyzer().findUntested(List.of(foo, bar, barTest));
        assertEquals(1, untested.size());
        assertEquals("Foo.java", untested.get(0).fileName());
    }

    @Test
    void findUntested_excludesTestFilesThemselves() {
        SearchResult fooTest = makeResult("src/test/FooTest.java", "FooTest.java");
        SearchResult foo = makeResult("src/main/Foo.java", "Foo.java");
        var untested = new TestCoverageAnalyzer().findUntested(List.of(foo, fooTest));
        for (SearchResult r : untested) {
            assertFalse(r.fileName().endsWith("Test.java"), "Test file in untested list: " + r.fileName());
        }
    }

    @Test
    void findUntested_excludesTestDirectoryFiles() {
        SearchResult mainFile = makeResult("src/main/Service.java", "Service.java");
        SearchResult testHelper = makeResult("src/test/helpers/Helper.java", "Helper.java");
        var untested = new TestCoverageAnalyzer().findUntested(List.of(mainFile, testHelper));
        assertEquals(1, untested.size());
        assertEquals("Service.java", untested.get(0).fileName());
    }

}
