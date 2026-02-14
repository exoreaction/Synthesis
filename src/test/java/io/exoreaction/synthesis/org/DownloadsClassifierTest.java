package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DownloadsClassifier}.
 */
class DownloadsClassifierTest {

    @TempDir
    Path tempDir;

    private OrganizationRegistry registry;
    private DownloadsClassifier classifier;

    @BeforeEach
    void setUp() throws IOException {
        registry = new OrganizationRegistry(tempDir);

        // Create test organizations
        Organization exo = new Organization("eXOReaction", OrganizationType.COMPANY,
                tempDir.resolve("eXOReaction"));
        exo.setKeywords(List.of("eXOReaction", "SDD", "workshop", "lib-pcb"));
        exo.addClient(new Client("SpareBank1", "eXOReaction",
                tempDir.resolve("eXOReaction/clients/opportunity-SpareBank1"),
                ClientStatus.OPPORTUNITY, "opportunity-SpareBank1"));
        Files.createDirectories(tempDir.resolve("eXOReaction"));
        registry.addOrganization(exo);

        Organization quadim = new Organization("Quadim", OrganizationType.COMPANY,
                tempDir.resolve("Quadim"));
        quadim.setKeywords(List.of("Quadim", "competence", "skill library"));
        Files.createDirectories(tempDir.resolve("Quadim"));
        registry.addOrganization(quadim);

        Organization cantara = new Organization("Cantara", OrganizationType.FOUNDATION,
                tempDir.resolve("Cantara"));
        cantara.setKeywords(List.of("Cantara", "Xorcery", "Whydah"));
        Files.createDirectories(tempDir.resolve("Cantara"));
        registry.addOrganization(cantara);

        classifier = new DownloadsClassifier(registry);
    }

    // --- Filename-based classification ---

    @Test
    void classify_filenameWithOrgName_highConfidence() throws IOException {
        Path file = tempDir.resolve("Quadim-Analysis-V2.pdf");
        Files.writeString(file, "dummy content");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertEquals("Quadim", result.organization());
        assertTrue(result.confidence() >= 0.4);
        assertFalse(result.shouldSkip());
    }

    @Test
    void classify_filenameWithKeyword_detectsOrg() throws IOException {
        Path file = tempDir.resolve("Xorcery_Framework_Docs.pdf");
        Files.writeString(file, "dummy content");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertEquals("Cantara", result.organization());
        assertTrue(result.confidence() > 0);
    }

    @Test
    void classify_filenameWithMultipleKeywords_highestWins() throws IOException {
        Path file = tempDir.resolve("SDD_workshop_plan_v3.md");
        Files.writeString(file, "dummy content");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertEquals("eXOReaction", result.organization());
        assertTrue(result.confidence() >= 0.5);
    }

    // --- Content-based classification ---

    @Test
    void classify_textContentWithOrgName_addsConfidence() throws IOException {
        Path file = tempDir.resolve("report.md");
        Files.writeString(file, "This report is about Quadim skill library and competence management.");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertEquals("Quadim", result.organization());
        assertTrue(result.confidence() > 0);
        assertTrue(result.signals().stream().anyMatch(s -> s.contains("Content")));
    }

    @Test
    void classify_textContentWithMultipleOrgs_highestScoreWins() throws IOException {
        Path file = tempDir.resolve("report.md");
        Files.writeString(file,
                "eXOReaction provides SDD workshop training. " +
                "The workshop covers lib-pcb development methodology.");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertEquals("eXOReaction", result.organization());
    }

    // --- Skip patterns ---

    @Test
    void classify_debFile_skipped() throws IOException {
        Path file = tempDir.resolve("editor_amd64.deb");
        Files.writeString(file, "binary");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertTrue(result.shouldSkip());
        assertNull(result.organization());
    }

    @Test
    void classify_exeFile_skipped() throws IOException {
        Path file = tempDir.resolve("installer.exe");
        Files.writeString(file, "binary");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertTrue(result.shouldSkip());
    }

    @Test
    void classify_dmgFile_skipped() throws IOException {
        Path file = tempDir.resolve("app.dmg");
        Files.writeString(file, "binary");

        assertTrue(classifier.classify(file).shouldSkip());
    }

    // --- Unknown files ---

    @Test
    void classify_unknownFile_noOrganization() throws IOException {
        Path file = tempDir.resolve("random_notes.txt");
        Files.writeString(file, "nothing relevant here");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertFalse(result.shouldSkip());
        assertNull(result.organization());
        assertEquals(0.0, result.confidence());
    }

    // --- Confidence threshold ---

    @Test
    void isConfident_aboveThreshold_returnsTrue() throws IOException {
        Path file = tempDir.resolve("Quadim-Analysis.md");
        Files.writeString(file, "Quadim competence skill library analysis");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertTrue(result.isConfident(0.3));
    }

    @Test
    void isConfident_belowThreshold_returnsFalse() throws IOException {
        Path file = tempDir.resolve("vague.txt");
        Files.writeString(file, "some text");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertFalse(result.isConfident(0.5));
    }

    // --- Destination computation ---

    @Test
    void classify_pdfFile_suggestsBusinessSubdir() throws IOException {
        Path file = tempDir.resolve("Quadim-Report.pdf");
        Files.writeString(file, "dummy");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertNotNull(result.suggestedDestination());
        assertTrue(result.suggestedDestination().toString().contains("business"));
    }

    @Test
    void classify_pngFile_suggestsMediaSubdir() throws IOException {
        Path file = tempDir.resolve("Quadim-Screenshot.png");
        Files.writeString(file, "dummy");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertNotNull(result.suggestedDestination());
        assertTrue(result.suggestedDestination().toString().contains("media"));
    }

    @Test
    void classify_zipFile_suggestsArchiveSubdir() throws IOException {
        Path file = tempDir.resolve("Xorcery.zip");
        Files.writeString(file, "dummy");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertNotNull(result.suggestedDestination());
        assertTrue(result.suggestedDestination().toString().contains("archive"));
    }

    // --- Filename analysis details ---

    @Test
    void analyzeFilename_handlesUnderscoresAndDashes() {
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> signals = new java.util.ArrayList<>();

        classifier.analyzeFilename("Knowledge_Infrastructure_Quadim.pdf", scores, signals);

        assertTrue(scores.containsKey("Quadim"));
        assertTrue(scores.get("Quadim") > 0);
    }

    @Test
    void analyzeFilename_caseInsensitive() {
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> signals = new java.util.ArrayList<>();

        classifier.analyzeFilename("EXOREACTION_REPORT.pdf", scores, signals);

        assertTrue(scores.containsKey("eXOReaction"));
    }

    // --- Signals ---

    @Test
    void classify_providesDetailedSignals() throws IOException {
        Path file = tempDir.resolve("Quadim-Analysis.md");
        Files.writeString(file, "Analysis of Quadim competence platform.");

        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

        assertFalse(result.signals().isEmpty());
        assertTrue(result.signals().stream().anyMatch(s -> s.contains("Filename")));
    }
}
