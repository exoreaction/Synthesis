package io.exoreaction.synthesis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ecosystem detection based on marker files.
 */
class EcosystemDetectorTest {

    @Test
    void detectPythonProject(@TempDir Path tempDir) throws IOException {
        // Create requirements.txt marker
        Files.writeString(tempDir.resolve("requirements.txt"), "requests==2.28.0\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.PYTHON));
        assertEquals(1, detected.size());
    }

    @Test
    void detectPythonProjectWithSetupPy(@TempDir Path tempDir) throws IOException {
        // Create setup.py marker
        Files.writeString(tempDir.resolve("setup.py"), "from setuptools import setup\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.PYTHON));
        assertEquals(1, detected.size());
    }

    @Test
    void detectPythonProjectWithPyprojectToml(@TempDir Path tempDir) throws IOException {
        // Create pyproject.toml marker
        Files.writeString(tempDir.resolve("pyproject.toml"), "[tool.poetry]\nname = \"test\"\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.PYTHON));
        assertEquals(1, detected.size());
    }

    @Test
    void detectJavaScriptProject(@TempDir Path tempDir) throws IOException {
        // Create package.json marker
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.JAVASCRIPT));
        assertEquals(1, detected.size());
    }

    @Test
    void detectJavaMavenProject(@TempDir Path tempDir) throws IOException {
        // Create pom.xml marker
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.JAVA_MAVEN));
        assertEquals(1, detected.size());
    }

    @Test
    void detectJavaGradleProject(@TempDir Path tempDir) throws IOException {
        // Create build.gradle marker
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.JAVA_GRADLE));
        assertEquals(1, detected.size());
    }

    @Test
    void detectJavaGradleProjectKotlinDsl(@TempDir Path tempDir) throws IOException {
        // Create build.gradle.kts marker
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins { java }\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.JAVA_GRADLE));
        assertEquals(1, detected.size());
    }

    @Test
    void detectRustProject(@TempDir Path tempDir) throws IOException {
        // Create Cargo.toml marker
        Files.writeString(tempDir.resolve("Cargo.toml"), "[package]\nname = \"test\"\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.RUST));
        assertEquals(1, detected.size());
    }

    @Test
    void detectGoProject(@TempDir Path tempDir) throws IOException {
        // Create go.mod marker
        Files.writeString(tempDir.resolve("go.mod"), "module example.com/test\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.GO));
        assertEquals(1, detected.size());
    }

    @Test
    void detectDotNetProject(@TempDir Path tempDir) throws IOException {
        // Create a subdirectory with .csproj file
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("TestApp.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\"></Project>\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.DOTNET));
        assertEquals(1, detected.size());
    }

    @Test
    void detectDotNetProjectWithSolution(@TempDir Path tempDir) throws IOException {
        // Create .sln file at root
        Files.writeString(tempDir.resolve("TestSolution.sln"), "Microsoft Visual Studio Solution File\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.DOTNET));
        assertEquals(1, detected.size());
    }

    @Test
    void detectRubyProject(@TempDir Path tempDir) throws IOException {
        // Create Gemfile marker
        Files.writeString(tempDir.resolve("Gemfile"), "source 'https://rubygems.org'\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.RUBY));
        assertEquals(1, detected.size());
    }

    @Test
    void detectPhpProject(@TempDir Path tempDir) throws IOException {
        // Create composer.json marker
        Files.writeString(tempDir.resolve("composer.json"), "{\"name\": \"test/project\"}\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.PHP));
        assertEquals(1, detected.size());
    }

    @Test
    void detectMonorepoMultipleEcosystems(@TempDir Path tempDir) throws IOException {
        // Create markers for multiple ecosystems
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"monorepo\"}\n");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(tempDir.resolve("requirements.txt"), "requests==2.28.0\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.JAVASCRIPT));
        assertTrue(detected.contains(Ecosystem.JAVA_MAVEN));
        assertTrue(detected.contains(Ecosystem.PYTHON));
        assertEquals(3, detected.size());
    }

    @Test
    void detectNoEcosystems(@TempDir Path tempDir) throws IOException {
        // Empty directory with no marker files
        Files.writeString(tempDir.resolve("README.md"), "# Test Project\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.isEmpty());
    }

    @Test
    void detectNonExistentDirectory() {
        Path nonExistent = Path.of("/non/existent/path");

        Set<Ecosystem> detected = EcosystemDetector.detect(nonExistent);

        assertTrue(detected.isEmpty());
    }

    @Test
    void detectJavaMavenAndGradleProject(@TempDir Path tempDir) throws IOException {
        // Project with both Maven and Gradle
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);

        assertTrue(detected.contains(Ecosystem.JAVA_MAVEN));
        assertTrue(detected.contains(Ecosystem.JAVA_GRADLE));
        assertEquals(2, detected.size());
    }
}
