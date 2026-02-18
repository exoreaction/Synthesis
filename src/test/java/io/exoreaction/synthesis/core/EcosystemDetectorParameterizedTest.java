package io.exoreaction.synthesis.core;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for EcosystemDetector — all marker files, all ecosystems.
 */
class EcosystemDetectorParameterizedTest {

    // --- Single ecosystem detection via marker file ---

    @ParameterizedTest
    @CsvSource({
        "requirements.txt, PYTHON",
        "setup.py,         PYTHON",
        "pyproject.toml,   PYTHON",
        "Pipfile,          PYTHON",
        "package.json,     JAVASCRIPT",
        "pom.xml,          JAVA_MAVEN",
        "build.gradle,     JAVA_GRADLE",
        "build.gradle.kts, JAVA_GRADLE",
        "settings.gradle,  JAVA_GRADLE",
        "Cargo.toml,       RUST",
        "go.mod,           GO",
        "Gemfile,          RUBY",
        "composer.json,    PHP"
    })
    void markerFile_detectsExpectedEcosystem(String markerFile, String expectedName,
                                              @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(markerFile), "# content");
        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);
        assertTrue(detected.contains(Ecosystem.valueOf(expectedName)),
                "Marker '" + markerFile + "' should detect " + expectedName);
        assertEquals(1, detected.size(),
                "Only " + expectedName + " should be detected");
    }

    // --- No false positives: unrelated files don't trigger ecosystems ---

    @ParameterizedTest
    @CsvSource({
        "README.md,         false",
        "CLAUDE.md,         false",
        "Makefile,          false",
        ".gitignore,        false",
        "notes.txt,         false"
    })
    void unrelatedFiles_noEcosystemDetected(String filename, boolean expectEmpty,
                                             @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(filename), "# nothing");
        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);
        assertTrue(detected.isEmpty(),
                "'" + filename + "' should not trigger any ecosystem");
    }

    // --- Ecosystem.getExclusionPatterns is non-empty for each type ---

    @ParameterizedTest
    @EnumSource(Ecosystem.class)
    void ecosystem_hasNonEmptyExclusionPatterns(Ecosystem ecosystem) {
        assertFalse(ecosystem.getExclusionPatterns().isEmpty(),
                ecosystem + " should have at least one exclusion pattern");
    }

    // --- .NET via csproj in subdirectory ---

    @ParameterizedTest
    @CsvSource({
        "App.csproj",
        "Lib.fsproj",
        "Module.vbproj",
        "Solution.sln"
    })
    void dotNetProjectFiles_detectDotNet(String filename, @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(filename), "<!-- .NET project -->");
        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);
        assertTrue(detected.contains(Ecosystem.DOTNET),
                "'" + filename + "' should detect DOTNET");
    }

    // --- Two-ecosystem combos ---

    @ParameterizedTest
    @CsvSource({
        "pom.xml,         package.json,  JAVA_MAVEN,   JAVASCRIPT",
        "requirements.txt, Gemfile,       PYTHON,        RUBY",
        "Cargo.toml,       go.mod,        RUST,          GO",
        "pom.xml,          build.gradle,  JAVA_MAVEN,    JAVA_GRADLE"
    })
    void twoMarkerFiles_detectBothEcosystems(String file1, String file2,
                                              String eco1, String eco2,
                                              @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(file1), "# first");
        Files.writeString(tempDir.resolve(file2), "# second");
        Set<Ecosystem> detected = EcosystemDetector.detect(tempDir);
        assertTrue(detected.contains(Ecosystem.valueOf(eco1)),
                "Should contain " + eco1);
        assertTrue(detected.contains(Ecosystem.valueOf(eco2)),
                "Should contain " + eco2);
        assertEquals(2, detected.size());
    }
}
