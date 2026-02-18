package io.exoreaction.synthesis.changelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for SignificanceClassifier — boundary values, pattern matching, mass deletion.
 */
class SignificanceClassifierParameterizedTest {

    private final SignificanceClassifier classifier = new SignificanceClassifier();

    // --- Noise file patterns ---

    @ParameterizedTest
    @ValueSource(strings = {
        ".DS_Store",
        "Thumbs.db",
        "package-lock.json",
        "yarn.lock",
        "pnpm-lock.yaml"
    })
    void classify_noiseFilenames_returnsNoise(String filename) {
        ChangeSignificance result = classifier.classify(
                filename, "TEXT", 100, ChangeEvent.ChangeType.MODIFIED);
        assertEquals(ChangeSignificance.NOISE, result, filename + " should be NOISE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ".synthesis/scan-state.json",
        "target/classes/Foo.class",
        "node_modules/lodash/index.js",
        ".git/COMMIT_EDITMSG"
    })
    void classify_noisePaths_returnsNoise(String path) {
        ChangeSignificance result = classifier.classify(
                path, "TEXT", 200, ChangeEvent.ChangeType.ADDED);
        assertEquals(ChangeSignificance.NOISE, result, path + " should be NOISE");
    }

    // --- Critical file patterns ---

    @ParameterizedTest
    @ValueSource(strings = {
        ".env",
        ".env.local",
        "credentials.json",
        "server.key",
        "private.pem"
    })
    void classify_criticalFilenames_returnsCritical(String filename) {
        ChangeSignificance result = classifier.classify(
                filename, "TEXT", 100, ChangeEvent.ChangeType.ADDED);
        assertEquals(ChangeSignificance.CRITICAL, result, filename + " should be CRITICAL");
    }

    // --- Notable file patterns ---

    @ParameterizedTest
    @ValueSource(strings = {
        "README.md",
        "pom.xml",
        "Dockerfile",
        "docker-compose.yml",
        ".gitignore"
    })
    void classify_notableFilenames_returnsNotable(String filename) {
        ChangeSignificance result = classifier.classify(
                filename, "TEXT", 500, ChangeEvent.ChangeType.MODIFIED);
        assertEquals(ChangeSignificance.NOTABLE, result, filename + " should be NOTABLE");
    }

    // --- Large file boundary ---

    @ParameterizedTest
    @CsvSource({
        "1048575, NORMAL",   // just below 1 MiB threshold (strict >)
        "1048576, NORMAL",   // exactly at threshold (not > threshold)
        "1048577, NOTABLE",  // just above threshold
        "5000000, NOTABLE"   // well above threshold
    })
    void classify_largeFileThreshold(long sizeBytes, String expectedLevel) {
        ChangeSignificance result = classifier.classify(
                "some-document.pdf", "PDF", sizeBytes, ChangeEvent.ChangeType.ADDED);
        ChangeSignificance expected = ChangeSignificance.valueOf(expectedLevel);
        assertEquals(expected, result, "File of size " + sizeBytes + " should be " + expectedLevel);
    }

    // --- Normal files ---

    @ParameterizedTest
    @ValueSource(strings = {
        "src/Main.java",
        "docs/guide.md",
        "config/settings.properties",
        "tests/UnitTest.java"
    })
    void classify_normalFiles_returnsNormal(String path) {
        ChangeSignificance result = classifier.classify(
                path, "CODE", 500, ChangeEvent.ChangeType.MODIFIED);
        assertEquals(ChangeSignificance.NORMAL, result, path + " should be NORMAL");
    }

    // --- Change type variation ---

    @ParameterizedTest
    @CsvSource({
        "ADDED",
        "MODIFIED",
        "DELETED"
    })
    void classify_normalFile_allChangeTypes_returnsNormal(String changeType) {
        ChangeSignificance result = classifier.classify(
                "src/Service.java", "CODE", 200,
                ChangeEvent.ChangeType.valueOf(changeType));
        assertEquals(ChangeSignificance.NORMAL, result);
    }

    // --- isMassDeletion ---

    @ParameterizedTest
    @CsvSource({
        "0,  false",
        "9,  false",
        "10, true",    // default threshold = 10
        "11, true",
        "100, true"
    })
    void isMassDeletion_defaultThreshold(int deletedCount, boolean expected) {
        assertEquals(expected, classifier.isMassDeletion(deletedCount),
                "isMassDeletion(" + deletedCount + ") should be " + expected);
    }

    @ParameterizedTest
    @CsvSource({
        "3, 2, false",
        "3, 3, true",
        "3, 5, true"
    })
    void isMassDeletion_customThreshold(int threshold, int deletedCount, boolean expected) {
        SignificanceClassifier custom = new SignificanceClassifier(List.of(), List.of(), threshold);
        assertEquals(expected, custom.isMassDeletion(deletedCount));
    }

    // --- Custom noise paths ---

    @ParameterizedTest
    @ValueSource(strings = {
        "logs/app.log",
        "logs/2024/jan.log",
        "cache/response.json"
    })
    void customNoisePaths_matchPattern(String path) {
        SignificanceClassifier custom = new SignificanceClassifier(
                List.of("**/logs/**", "**/cache/**"), List.of(), 10);
        assertEquals(ChangeSignificance.NOISE, custom.classify(
                path, "TEXT", 100, ChangeEvent.ChangeType.MODIFIED));
    }

    // --- Custom critical paths ---

    @ParameterizedTest
    @ValueSource(strings = {
        "production.config",
        "production-db.config"
    })
    void customCriticalPaths_matchPattern(String path) {
        SignificanceClassifier custom = new SignificanceClassifier(
                List.of(), List.of("**production*"), 10);
        assertEquals(ChangeSignificance.CRITICAL, custom.classify(
                path, "TEXT", 100, ChangeEvent.ChangeType.MODIFIED));
    }
}
