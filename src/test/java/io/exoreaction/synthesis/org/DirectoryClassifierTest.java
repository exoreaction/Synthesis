package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryClassifier} and {@link DirectoryClassification}.
 */
class DirectoryClassifierTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // DirectoryClassification enum tests
    // -------------------------------------------------------------------------

    @Test
    void document_doesNotSkipAnything() {
        DirectoryClassification c = DirectoryClassification.DOCUMENT;
        assertFalse(c.skipCentroid());
        assertFalse(c.skipWants());
        assertFalse(c.skipHealth());
        assertFalse(c.skipRouting());
    }

    @Test
    void code_skipsEverything() {
        DirectoryClassification c = DirectoryClassification.CODE;
        assertTrue(c.skipCentroid());
        assertTrue(c.skipWants());
        assertTrue(c.skipHealth());
        assertTrue(c.skipRouting());
    }

    @Test
    void media_skipsSomeButNotAll() {
        DirectoryClassification c = DirectoryClassification.MEDIA;
        assertFalse(c.skipCentroid());
        assertTrue(c.skipWants());
        assertTrue(c.skipHealth());
        assertFalse(c.skipRouting());
    }

    @Test
    void generated_skipsEverything() {
        DirectoryClassification c = DirectoryClassification.GENERATED;
        assertTrue(c.skipCentroid());
        assertTrue(c.skipWants());
        assertTrue(c.skipHealth());
        assertTrue(c.skipRouting());
    }

    @Test
    void unknown_doesNotSkipAnything() {
        DirectoryClassification c = DirectoryClassification.UNKNOWN;
        assertFalse(c.skipCentroid());
        assertFalse(c.skipWants());
        assertFalse(c.skipHealth());
        assertFalse(c.skipRouting());
    }

    // -------------------------------------------------------------------------
    // Tier 1: Ancestor build file
    // -------------------------------------------------------------------------

    @Test
    void javaMavenRepoRoot_classifiesAsCode() throws IOException {
        // A directory with a pom.xml is a code repo root
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-project"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Path subDir = Files.createDirectories(repoRoot.resolve("utils"));
        Files.writeString(subDir.resolve("Helper.java"), "public class Helper {}");

        DirectoryClassification result = DirectoryClassifier.classify(subDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    @Test
    void javaPackageDir_classifiesAsCode() throws IOException {
        // Deep Java package directory inside a Maven project
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-project"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Path javaDir = Files.createDirectories(
                repoRoot.resolve("src/main/java/com/example/cli"));
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        DirectoryClassification result = DirectoryClassifier.classify(javaDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    @Test
    void docsInsideMavenRepo_classifiesAsDocument() throws IOException {
        // docs/ inside a Maven project should be DOCUMENT (Tier 4 carve-out)
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-project"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Path docsDir = Files.createDirectories(repoRoot.resolve("docs"));
        Files.writeString(docsDir.resolve("README.md"), "# Documentation");

        DirectoryClassification result = DirectoryClassifier.classify(docsDir, tempDir);
        assertEquals(DirectoryClassification.DOCUMENT, result);
    }

    @Test
    void pureDocumentDirectory_classifiesAsDocumentOrUnknown() throws IOException {
        // A directory with no build file ancestors and only markdown files
        Path docsDir = Files.createDirectories(tempDir.resolve("meeting-notes"));
        Files.writeString(docsDir.resolve("standup.md"), "# Standup");
        Files.writeString(docsDir.resolve("retro.md"), "# Retro");

        DirectoryClassification result = DirectoryClassifier.classify(docsDir, tempDir);
        assertEquals(DirectoryClassification.DOCUMENT, result);
    }

    @Test
    void nodeModules_classifiesAsGenerated() throws IOException {
        Path nodeModules = Files.createDirectories(tempDir.resolve("project/node_modules"));
        Files.writeString(nodeModules.resolve("index.js"), "module.exports = {};");

        DirectoryClassification result = DirectoryClassifier.classify(nodeModules, tempDir);
        assertEquals(DirectoryClassification.GENERATED, result);
    }

    @Test
    void targetDir_classifiesAsGenerated() throws IOException {
        Path targetDir = Files.createDirectories(tempDir.resolve("project/target"));
        Files.writeString(targetDir.resolve("output.class"), "bytecode");

        DirectoryClassification result = DirectoryClassifier.classify(targetDir, tempDir);
        assertEquals(DirectoryClassification.GENERATED, result);
    }

    @Test
    void mediaOnlyDirectory_classifiesAsMedia() throws IOException {
        Path mediaDir = Files.createDirectories(tempDir.resolve("photos"));
        Files.writeString(mediaDir.resolve("photo1.jpg"), "jpeg data");
        Files.writeString(mediaDir.resolve("photo2.png"), "png data");
        Files.writeString(mediaDir.resolve("video.mp4"), "mp4 data");

        DirectoryClassification result = DirectoryClassifier.classify(mediaDir, tempDir);
        assertEquals(DirectoryClassification.MEDIA, result);
    }

    @Test
    void emptyDirectory_classifiesAsUnknown() throws IOException {
        Path emptyDir = Files.createDirectories(tempDir.resolve("empty"));

        DirectoryClassification result = DirectoryClassifier.classify(emptyDir, tempDir);
        assertEquals(DirectoryClassification.UNKNOWN, result);
    }

    // -------------------------------------------------------------------------
    // Tier 2: Path pattern tests
    // -------------------------------------------------------------------------

    @Test
    void srcMainJavaPath_classifiesAsCode() throws IOException {
        Path javaRoot = Files.createDirectories(
                tempDir.resolve("project/src/main/java"));

        DirectoryClassification result = DirectoryClassifier.classify(javaRoot, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    @Test
    void srcTestKotlinPath_classifiesAsCode() throws IOException {
        Path kotlinTestDir = Files.createDirectories(
                tempDir.resolve("project/src/test/kotlin/com/example"));

        DirectoryClassification result = DirectoryClassifier.classify(kotlinTestDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    @Test
    void srcMainResourcesPath_classifiesAsCode() throws IOException {
        Path resourcesDir = Files.createDirectories(
                tempDir.resolve("project/src/main/resources"));

        DirectoryClassification result = DirectoryClassifier.classify(resourcesDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    // -------------------------------------------------------------------------
    // Tier 3: Content signal tests
    // -------------------------------------------------------------------------

    @Test
    void sourceCodeOnlyDirectory_classifiesAsCode() throws IOException {
        // Directory with >80% source files, no build file ancestor
        Path codeDir = Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(codeDir.resolve("main.py"), "print('hello')");
        Files.writeString(codeDir.resolve("util.py"), "def helper(): pass");
        Files.writeString(codeDir.resolve("test.py"), "assert True");

        DirectoryClassification result = DirectoryClassifier.classify(codeDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    @Test
    void mixedDirectory_classifiesAsUnknown() throws IOException {
        // Directory with mixed content (no >80% majority)
        Path mixedDir = Files.createDirectories(tempDir.resolve("stuff"));
        Files.writeString(mixedDir.resolve("readme.md"), "# Notes");
        Files.writeString(mixedDir.resolve("photo.jpg"), "image");
        Files.writeString(mixedDir.resolve("script.py"), "code");

        DirectoryClassification result = DirectoryClassifier.classify(mixedDir, tempDir);
        assertEquals(DirectoryClassification.UNKNOWN, result);
    }

    // -------------------------------------------------------------------------
    // Tier 4: Carve-out tests
    // -------------------------------------------------------------------------

    @Test
    void examplesInsideCodeRepo_classifiesAsDocument() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-lib"));
        Files.writeString(repoRoot.resolve("Cargo.toml"), "[package]");
        Path examplesDir = Files.createDirectories(repoRoot.resolve("examples"));
        Files.writeString(examplesDir.resolve("demo.md"), "# Example usage");

        DirectoryClassification result = DirectoryClassifier.classify(examplesDir, tempDir);
        assertEquals(DirectoryClassification.DOCUMENT, result);
    }

    @Test
    void markdownOnlyDirInsideCodeRepo_classifiesAsDocument() throws IOException {
        // A directory containing only .md files inside a code repo
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-app"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Path designDir = Files.createDirectories(repoRoot.resolve("design"));
        Files.writeString(designDir.resolve("architecture.md"), "# Architecture");
        Files.writeString(designDir.resolve("roadmap.md"), "# Roadmap");

        DirectoryClassification result = DirectoryClassifier.classify(designDir, tempDir);
        assertEquals(DirectoryClassification.DOCUMENT, result);
    }

    @Test
    void wikiInsideCodeRepo_classifiesAsDocument() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-app"));
        Files.writeString(repoRoot.resolve("build.gradle"), "plugins {}");
        Path wikiDir = Files.createDirectories(repoRoot.resolve("wiki"));
        Files.writeString(wikiDir.resolve("setup.md"), "# Setup Guide");

        DirectoryClassification result = DirectoryClassifier.classify(wikiDir, tempDir);
        assertEquals(DirectoryClassification.DOCUMENT, result);
    }

    // -------------------------------------------------------------------------
    // Caching tests
    // -------------------------------------------------------------------------

    @Test
    void ancestorBuildFileCache_isPopulatedAndReused() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("my-project"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Path deepDir = Files.createDirectories(repoRoot.resolve("a/b/c"));

        Map<Path, Optional<Path>> cache = new HashMap<>();
        DirectoryClassifier.classify(deepDir, tempDir, cache);

        // Cache should be populated for the deep directory and ancestors
        assertFalse(cache.isEmpty(), "Cache should be populated after classification");
    }

    // -------------------------------------------------------------------------
    // Gradle build file tests
    // -------------------------------------------------------------------------

    @Test
    void gradleKtsProject_classifiesAsCode() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("gradle-app"));
        Files.writeString(repoRoot.resolve("build.gradle.kts"), "plugins {}");
        Path srcDir = Files.createDirectories(repoRoot.resolve("app"));
        Files.writeString(srcDir.resolve("Main.kt"), "fun main() {}")  ;

        DirectoryClassification result = DirectoryClassifier.classify(srcDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    // -------------------------------------------------------------------------
    // Go module tests
    // -------------------------------------------------------------------------

    @Test
    void goModProject_classifiesAsCode() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("go-service"));
        Files.writeString(repoRoot.resolve("go.mod"), "module example.com/mymod");
        Path cmdDir = Files.createDirectories(repoRoot.resolve("cmd"));
        Files.writeString(cmdDir.resolve("main.go"), "package main");

        DirectoryClassification result = DirectoryClassifier.classify(cmdDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    // -------------------------------------------------------------------------
    // Null / edge case tests
    // -------------------------------------------------------------------------

    @Test
    void nullDir_returnsUnknown() {
        assertEquals(DirectoryClassification.UNKNOWN,
                DirectoryClassifier.classify(null, tempDir));
    }

    @Test
    void nullRoot_returnsUnknown() throws IOException {
        Path someDir = Files.createDirectories(tempDir.resolve("any"));
        assertEquals(DirectoryClassification.UNKNOWN,
                DirectoryClassifier.classify(someDir, null));
    }

    // -------------------------------------------------------------------------
    // .csproj / .sln tests
    // -------------------------------------------------------------------------

    @Test
    void csprojProject_classifiesAsCode() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("dotnet-app"));
        Files.writeString(repoRoot.resolve("MyApp.csproj"), "<Project/>");
        Path controllersDir = Files.createDirectories(repoRoot.resolve("Controllers"));
        Files.writeString(controllersDir.resolve("HomeController.cs"), "class HomeController {}");

        DirectoryClassification result = DirectoryClassifier.classify(controllersDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    // -------------------------------------------------------------------------
    // package.json (Node.js) tests
    // -------------------------------------------------------------------------

    @Test
    void nodeProject_classifiesAsCode() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("node-app"));
        Files.writeString(repoRoot.resolve("package.json"), "{}");
        Path srcDir = Files.createDirectories(repoRoot.resolve("src"));
        Files.writeString(srcDir.resolve("index.ts"), "export default {};");

        DirectoryClassification result = DirectoryClassifier.classify(srcDir, tempDir);
        assertEquals(DirectoryClassification.CODE, result);
    }

    // -------------------------------------------------------------------------
    // Semantic directory outside code tree
    // -------------------------------------------------------------------------

    @Test
    void businessDirectory_noBuildFile_classifiesBasedOnContent() throws IOException {
        // A business directory with documents, no code ancestor
        Path bizDir = Files.createDirectories(tempDir.resolve("business/proposals"));
        Files.writeString(bizDir.resolve("q1-proposal.pdf"), "pdf content");
        Files.writeString(bizDir.resolve("budget.xlsx"), "spreadsheet");
        // Less than 80% document extensions (pdf is document, xlsx is document => 100%)
        // Actually both are document extensions, so should classify as DOCUMENT

        DirectoryClassification result = DirectoryClassifier.classify(bizDir, tempDir);
        assertEquals(DirectoryClassification.DOCUMENT, result);
    }
}
