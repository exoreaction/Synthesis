package io.exoreaction.synthesis.enrichment;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompanionFileGenerator.
 */
class CompanionFileGeneratorTest {

    @TempDir
    Path tempDir;

    private CompanionFileGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new CompanionFileGenerator(EnrichmentLevel.BASIC, false);
    }

    // --- companionPathFor tests ---

    @Test
    void companionPathFor_addsCorrectSuffix() {
        Path original = Path.of("/project/video.mp4");
        Path companion = CompanionFileGenerator.companionPathFor(original);
        assertEquals("video.mp4.synthesis.md", companion.getFileName().toString());
        assertEquals("/project", companion.getParent().toString());
    }

    @Test
    void companionPathFor_handlesMultipleExtensions() {
        Path original = Path.of("/project/archive.tar.gz");
        Path companion = CompanionFileGenerator.companionPathFor(original);
        assertEquals("archive.tar.gz.synthesis.md", companion.getFileName().toString());
    }

    @Test
    void companionPathFor_handlesNoExtension() {
        Path original = Path.of("/project/Makefile");
        Path companion = CompanionFileGenerator.companionPathFor(original);
        assertEquals("Makefile.synthesis.md", companion.getFileName().toString());
    }

    // --- isCompanionFile tests ---

    @Test
    void isCompanionFile_recognizesCompanionFiles() {
        assertTrue(CompanionFileGenerator.isCompanionFile(Path.of("video.mp4.synthesis.md")));
        assertTrue(CompanionFileGenerator.isCompanionFile(Path.of("/path/image.png.synthesis.md")));
    }

    @Test
    void isCompanionFile_rejectsNonCompanionFiles() {
        assertFalse(CompanionFileGenerator.isCompanionFile(Path.of("video.mp4")));
        assertFalse(CompanionFileGenerator.isCompanionFile(Path.of("README.md")));
        assertFalse(CompanionFileGenerator.isCompanionFile(Path.of("synthesis.md")));
    }

    // --- sourcePathFor tests ---

    @Test
    void sourcePathFor_reversesCompanionPath() {
        Path companion = Path.of("/project/video.mp4.synthesis.md");
        Optional<Path> source = CompanionFileGenerator.sourcePathFor(companion);
        assertTrue(source.isPresent());
        assertEquals("video.mp4", source.get().getFileName().toString());
    }

    @Test
    void sourcePathFor_returnsEmptyForNonCompanion() {
        Optional<Path> source = CompanionFileGenerator.sourcePathFor(Path.of("README.md"));
        assertTrue(source.isEmpty());
    }

    @Test
    void sourcePathFor_returnsEmptyForBareCompanionSuffix() {
        Optional<Path> source = CompanionFileGenerator.sourcePathFor(Path.of("/project/.synthesis.md"));
        assertTrue(source.isEmpty());
    }

    // --- hasCompanion tests ---

    @Test
    void hasCompanion_detectsExistingCompanion() throws IOException {
        Path original = tempDir.resolve("photo.jpg");
        Files.createFile(original);
        Path companion = tempDir.resolve("photo.jpg.synthesis.md");
        Files.createFile(companion);

        assertTrue(CompanionFileGenerator.hasCompanion(original));
    }

    @Test
    void hasCompanion_returnsFalseForMissing() throws IOException {
        Path original = tempDir.resolve("photo.jpg");
        Files.createFile(original);

        assertFalse(CompanionFileGenerator.hasCompanion(original));
    }

    // --- generate tests ---

    @Test
    void generate_createsCompanionForImage() throws IOException {
        Path imageFile = tempDir.resolve("diagram.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}); // PNG header

        FileMetadata metadata = FileMetadata.of(
                imageFile, tempDir, 1024, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "A diagram image", List.of(), List.of(), List.of(), "",
                Map.of("dimensions", "1920x1080"), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent());
        assertTrue(Files.exists(result.get()));
        String content = Files.readString(result.get());
        assertTrue(content.contains("diagram.png"));
        assertTrue(content.contains("companion_for: diagram.png"));
        assertTrue(content.contains("type: IMAGE"));
        assertTrue(content.contains("enrichment_level: BASIC"));
    }

    @Test
    void generate_createsCompanionForVideo() throws IOException {
        Path videoFile = tempDir.resolve("demo.mp4");
        Files.createFile(videoFile);

        FileMetadata metadata = FileMetadata.of(
                videoFile, tempDir, 50_000_000, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "A demo video", List.of(), List.of(), List.of(), "",
                Map.of("duration", "5:30", "resolution", "1080p", "codec", "H.264"), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent());
        String content = Files.readString(result.get());
        assertTrue(content.contains("demo.mp4"));
        assertTrue(content.contains("Duration:"));
        assertTrue(content.contains("Resolution:"));
        assertTrue(content.contains("Codec:"));
    }

    @Test
    void generate_createsCompanionForPdf() throws IOException {
        Path pdfFile = tempDir.resolve("report.pdf");
        Files.createFile(pdfFile);

        FileMetadata metadata = FileMetadata.of(
                pdfFile, tempDir, 2_000_000, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "A business report", List.of("Introduction", "Results"),
                List.of(), List.of(), "",
                Map.of("pages", "24", "textPreview", "Executive summary..."), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent());
        String content = Files.readString(result.get());
        assertTrue(content.contains("report.pdf"));
        assertTrue(content.contains("type: PDF"));
        assertTrue(content.contains("Pages:"));
        assertTrue(content.contains("Content Preview"));
        assertTrue(content.contains("Headings"));
    }

    @Test
    void generate_skipsTextFiles() throws IOException {
        Path javaFile = tempDir.resolve("Main.java");
        Files.writeString(javaFile, "public class Main {}");

        FileMetadata metadata = FileMetadata.of(
                javaFile, tempDir, 100, Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.minimal("Java class", "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void generate_skipsIfCompanionExists() throws IOException {
        Path imageFile = tempDir.resolve("photo.png");
        Files.createFile(imageFile);
        Path companion = tempDir.resolve("photo.png.synthesis.md");
        Files.writeString(companion, "existing content");

        FileMetadata metadata = FileMetadata.of(
                imageFile, tempDir, 1024, Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.minimal("Photo", "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());
        assertTrue(result.isEmpty());

        // Verify existing content not overwritten
        assertEquals("existing content", Files.readString(companion));
    }

    @Test
    void generate_forcedOverwritesExisting() throws IOException {
        Path imageFile = tempDir.resolve("photo.png");
        Files.createFile(imageFile);
        Path companion = tempDir.resolve("photo.png.synthesis.md");
        Files.writeString(companion, "old content");

        CompanionFileGenerator forceGenerator = new CompanionFileGenerator(
                EnrichmentLevel.BASIC, true);

        FileMetadata metadata = FileMetadata.of(
                imageFile, tempDir, 1024, Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.minimal("Photo", "");

        Optional<Path> result = forceGenerator.generate(metadata, analysis, List.of());
        assertTrue(result.isPresent());

        // Verify content was regenerated
        String content = Files.readString(companion);
        assertNotEquals("old content", content);
        assertTrue(content.contains("photo.png"));
    }

    @Test
    void generate_includesRelatedFiles() throws IOException {
        Path videoFile = tempDir.resolve("presentation.mp4");
        Files.createFile(videoFile);

        FileMetadata metadata = FileMetadata.of(
                videoFile, tempDir, 50000, Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.minimal("A presentation", "");

        List<CompanionFileGenerator.RelatedFile> related = List.of(
                new CompanionFileGenerator.RelatedFile(
                        "presentation.srt", "presentation.srt", "subtitle/transcript"),
                new CompanionFileGenerator.RelatedFile(
                        "presentation-slides.pdf", "presentation-slides.pdf", "slides")
        );

        Optional<Path> result = generator.generate(metadata, analysis, related);

        assertTrue(result.isPresent());
        String content = Files.readString(result.get());
        assertTrue(content.contains("Related Files"));
        assertTrue(content.contains("presentation.srt"));
        assertTrue(content.contains("subtitle/transcript"));
        assertTrue(content.contains("presentation-slides.pdf"));
    }

    // --- regression tests for issue #80: disk check, not index check ---

    /**
     * Regression test for issue #80: generate() checks Files.exists() on disk,
     * not the search index. When companion is on disk → skip; when deleted → generate.
     */
    @Test
    void generate_skipsDiskCheckNotIndexCheck_existingCompanion() throws IOException {
        Path imageFile = tempDir.resolve("slide.png");
        Files.createFile(imageFile);
        // Create companion on disk (simulating it being indexed but also present on disk)
        Path companion = tempDir.resolve("slide.png.synthesis.md");
        Files.writeString(companion, "# existing companion");

        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 1024, Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.minimal("Slide image", "");

        // Should skip — companion exists on disk
        Optional<Path> result = generator.generate(metadata, analysis, List.of());
        assertTrue(result.isEmpty(), "generate() must skip when companion exists on disk");
    }

    @Test
    void generate_generatesAfterCompanionDeletedFromDisk() throws IOException {
        Path imageFile = tempDir.resolve("slide2.png");
        Files.createFile(imageFile);
        Path companion = tempDir.resolve("slide2.png.synthesis.md");
        Files.writeString(companion, "# old companion");

        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 1024, Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.minimal("Slide image", "");

        // First call: companion exists — should skip
        assertTrue(generator.generate(metadata, analysis, List.of()).isEmpty(),
                "Should skip when companion is on disk");

        // Delete companion from disk
        Files.delete(companion);
        assertFalse(Files.exists(companion), "Companion should be gone from disk");

        // Second call: companion gone from disk — should generate (disk check, not index check)
        Optional<Path> result = generator.generate(metadata, analysis, List.of());
        assertTrue(result.isPresent(), "generate() must generate when companion is missing from disk");
        assertTrue(Files.exists(result.get()), "New companion file must exist on disk");
    }

    // --- EnrichmentLevel tests ---

    @Test
    void enrichmentLevel_basicHasNoAI() {
        assertFalse(EnrichmentLevel.BASIC.hasAI());
        assertFalse(EnrichmentLevel.BASIC.hasLocalTools());
    }

    @Test
    void enrichmentLevel_localHasLocalToolsButNoAI() {
        assertFalse(EnrichmentLevel.LOCAL.hasAI());
        assertTrue(EnrichmentLevel.LOCAL.hasLocalTools());
    }

    @Test
    void enrichmentLevel_aiHasEverything() {
        assertTrue(EnrichmentLevel.AI.hasAI());
        assertTrue(EnrichmentLevel.AI.hasLocalTools());
    }

    @Test
    void enrichmentLevel_forEdition_coreIsBasic() {
        assertEquals(EnrichmentLevel.BASIC, EnrichmentLevel.forEdition("core"));
    }

    @Test
    void enrichmentLevel_forEdition_proIsAI() {
        assertEquals(EnrichmentLevel.AI, EnrichmentLevel.forEdition("pro"));
    }

    @Test
    void enrichmentLevel_forEdition_enterpriseIsBasic() {
        assertEquals(EnrichmentLevel.BASIC, EnrichmentLevel.forEdition("enterprise"));
    }

    // --- EnrichmentResult tests ---

    @Test
    void enrichmentResult_summaryContainsAllMetrics() {
        EnrichmentResult result = new EnrichmentResult(
                100, 75, 20, 5, List.of(), 3500, EnrichmentLevel.BASIC);

        String summary = result.summary();
        assertTrue(summary.contains("100"));
        assertTrue(summary.contains("75"));
        assertTrue(summary.contains("20"));
        assertTrue(summary.contains("5"));
        assertTrue(summary.contains("BASIC"));
    }

    @Test
    void enrichmentResult_isSuccessWhenNoErrors() {
        EnrichmentResult success = new EnrichmentResult(
                10, 5, 5, 0, List.of(), 1000, EnrichmentLevel.BASIC);
        assertTrue(success.isSuccess());

        EnrichmentResult failure = new EnrichmentResult(
                10, 5, 4, 1, List.of(), 1000, EnrichmentLevel.BASIC);
        assertFalse(failure.isSuccess());
    }

    // --- Oversized PDF companion tests (issue #254) ---

    @Test
    void generateOversizedPdfCompanion_createsMetadataOnlyCompanion() throws IOException {
        // Create a temporary PDF file (content doesn't matter -- we don't read it)
        Path largePdf = tempDir.resolve("large-report.pdf");
        // Write minimal content -- the method reads only file-system metadata
        Files.write(largePdf, new byte[100]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(largePdf);

        assertTrue(Files.exists(companion), "Companion file must be created");
        assertEquals("large-report.pdf.synthesis.md", companion.getFileName().toString());

        String content = Files.readString(companion);
        assertTrue(content.contains("large-report.pdf"), "Must include filename");
        assertTrue(content.contains("companion_for: large-report.pdf"), "Must have frontmatter companion_for");
        assertTrue(content.contains("type: PDF"), "Must declare type PDF");
        assertTrue(content.contains("enrichment_level: METADATA"), "Must use METADATA enrichment level");
        assertTrue(content.contains("**Type:** PDF"), "Must show type in body");
        assertTrue(content.contains("**Size:**"), "Must show file size");
        assertTrue(content.contains("**Modified:**"), "Must show modification date");
        assertTrue(content.contains("## Note"), "Must include Note section explaining size skip");
        assertTrue(content.contains("exceeds the AI enrichment size threshold"), "Must explain why enrichment was skipped");
        assertTrue(content.contains("## Keywords"), "Must include Keywords section");
        assertTrue(content.contains("large-document"), "Keywords must include large-document");
        assertTrue(content.contains("Generated by Synthesis"), "Must include generation footer");
        assertTrue(content.contains("enrichment: METADATA"), "Footer must reflect METADATA level");
    }

    @Test
    void generateOversizedPdfCompanion_companionPathFollowsConvention() throws IOException {
        Path largePdf = tempDir.resolve("subdir").resolve("big-file.pdf");
        Files.createDirectories(largePdf.getParent());
        Files.write(largePdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(largePdf);

        assertEquals(largePdf.getParent(), companion.getParent(),
                "Companion must reside in same directory as source PDF");
        assertEquals("big-file.pdf.synthesis.md", companion.getFileName().toString(),
                "Companion filename must follow .synthesis.md convention");
    }

    @Test
    void generateOversizedPdfCompanion_includesSizeThresholdInfo() throws IOException {
        Path largePdf = tempDir.resolve("report.pdf");
        Files.write(largePdf, new byte[200]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(largePdf);
        String content = Files.readString(companion);

        // The note must mention the threshold size (10 MB)
        assertTrue(content.contains("10"), "Note must reference the size threshold");
    }

    @Test
    void enrichmentLevel_metadataHasNoAIAndNoLocalTools() {
        assertFalse(EnrichmentLevel.METADATA.hasAI());
        assertFalse(EnrichmentLevel.METADATA.hasLocalTools());
    }

    @Test
    void aiEnrichmentMaxSizeBytes_isPositive() {
        assertTrue(CompanionFileGenerator.AI_ENRICHMENT_MAX_SIZE_BYTES > 0,
                "Size threshold must be positive");
        // Should be 10 MB
        assertEquals(10L * 1024 * 1024, CompanionFileGenerator.AI_ENRICHMENT_MAX_SIZE_BYTES);
    }

}
