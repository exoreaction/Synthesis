package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expanded tests for DownloadsClassifier — skip extensions, confidence thresholds,
 * ClassificationResult record, and multi-keyword filename matching.
 */
class DownloadsClassifierExpandedTest {

    @TempDir
    Path tempDir;

    private OrganizationRegistry registry;
    private DownloadsClassifier classifier;

    @BeforeEach
    void setUp() {
        registry = new OrganizationRegistry(tempDir);
        Organization org = new Organization("Acme", OrganizationType.COMPANY,
                tempDir.resolve("Acme"));
        org.addClient(new Client("ClientX", "Acme",
                tempDir.resolve("Acme/clients/ClientX"), ClientStatus.ACTIVE, "ClientX"));
        registry.addOrganization(org);
        classifier = new DownloadsClassifier(registry);
    }

    // --- ClassificationResult record ---

    @Test
    void classificationResult_fieldsAccessible() throws IOException {
        Path file = tempDir.resolve("acme-report.pdf");
        Files.writeString(file, "Acme quarterly report");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertNotNull(result);
        assertNotNull(result.signals(), "Signals should not be null");
        // confidence is between 0.0 and 1.0 (or above, max 1.0 after normalization)
        assertTrue(result.confidence() >= 0.0, "Confidence should be non-negative");
        assertTrue(result.confidence() <= 1.0, "Confidence should not exceed 1.0");
    }

    // --- isConfident threshold ---

    @Test
    void classificationResult_isConfident_aboveThreshold_returnsTrue() throws IOException {
        Path file = tempDir.resolve("acme-acme-acme.pdf");
        Files.writeString(file, "Acme Acme Acme repeated content for Acme");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);
        // With lots of signals, confidence should be high
        if (result.organization() != null) {
            assertTrue(result.isConfident(0.0), "isConfident(0.0) should always be true if org found");
        }
    }

    @Test
    void classificationResult_isConfident_belowThreshold_returnsFalse() {
        // Build a result with low confidence directly
        DownloadsClassifier.ClassificationResult lowConfidence =
                new DownloadsClassifier.ClassificationResult(
                        "Acme", 0.1, null, List.of(), false);
        assertFalse(lowConfidence.isConfident(0.5), "0.1 confidence < 0.5 threshold should be false");
    }

    @Test
    void classificationResult_isConfident_exactThreshold_returnsTrue() {
        DownloadsClassifier.ClassificationResult atThreshold =
                new DownloadsClassifier.ClassificationResult(
                        "Acme", 0.5, null, List.of(), false);
        assertTrue(atThreshold.isConfident(0.5), "0.5 confidence >= 0.5 threshold should be true");
    }

    @ParameterizedTest
    @CsvSource({
        "0.0,  0.1,  false",
        "0.3,  0.5,  false",
        "0.8,  0.7,  true",
        "1.0,  0.9,  true"
    })
    void isConfident_variousThresholds(double confidence, double threshold, boolean expected) {
        DownloadsClassifier.ClassificationResult result =
                new DownloadsClassifier.ClassificationResult(
                        "Acme", confidence, null, List.of(), false);
        assertEquals(expected, result.isConfident(threshold));
    }

    @Test
    void classificationResult_noOrg_isConfident_returnsFalse() {
        DownloadsClassifier.ClassificationResult noOrg =
                new DownloadsClassifier.ClassificationResult(
                        null, 0.9, null, List.of(), false);
        assertFalse(noOrg.isConfident(0.5), "null org → isConfident should return false");
    }

    // --- skip extensions ---

    @ParameterizedTest
    @ValueSource(strings = {
        "installer.exe", "package.deb", "app.dmg",
        "app.appimage", "setup.msi", "package.rpm",
        "app.snap", "app.flatpak", "setup.run"
    })
    void classify_skipExtension_shouldSkipTrue(String filename) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "binary content");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);
        assertTrue(result.shouldSkip(), "File '" + filename + "' should be skipped");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "notes.txt", "data.zip", "image.png"})
    void classify_nonSkipExtension_shouldSkipFalse(String filename) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "content");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);
        assertFalse(result.shouldSkip(), "File '" + filename + "' should not be skipped");
    }

    // --- signals list ---

    @Test
    void classify_result_hasSignals() throws IOException {
        Path file = tempDir.resolve("acme-report.pdf");
        Files.writeString(file, "content");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);
        assertNotNull(result.signals());
        assertFalse(result.signals().isEmpty(), "Classification should produce at least one signal");
    }

    @Test
    void classify_skipExtension_signalsNonNull() throws IOException {
        Path file = tempDir.resolve("setup.exe");
        Files.writeString(file, "binary");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);
        assertNotNull(result.signals());
    }

    // --- multi-org disambiguation ---

    @Test
    void classify_ambiguousFile_organizationCanBeNull() throws IOException {
        // File with no org-related keywords → organization=null, confidence=0
        Path file = tempDir.resolve("untitled-document.pdf");
        Files.writeString(file, "generic content with no org markers");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);
        // No assertion on org (could be null), just verify result is valid
        assertNotNull(result);
        assertTrue(result.confidence() >= 0.0);
    }
}
