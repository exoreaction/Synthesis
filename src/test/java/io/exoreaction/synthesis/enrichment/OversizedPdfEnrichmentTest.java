package io.exoreaction.synthesis.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for oversized PDF companion generation (issue #254).
 *
 * <p>Large PDFs (18-20 MB) exceed the DirectoryScanner size limit (default 10 MB)
 * and are never added to the index. This means the standard enrichment loop in
 * {@code EnrichCommand} never sees them and never generates companions.
 *
 * <p>The fix adds a dedicated pass in {@code EnrichCommand} that walks the
 * filesystem for oversized PDFs and calls
 * {@link CompanionFileGenerator#generateOversizedPdfCompanion(Path)} to generate
 * a metadata-only companion (enrichment_level: METADATA).
 */
class OversizedPdfEnrichmentTest {

    @TempDir
    Path workspaceRoot;

    // ---- generateOversizedPdfCompanion content tests ----

    @Test
    void oversizedPdf_companionUsesMetadataEnrichmentLevel() throws IOException {
        Path pdf = workspaceRoot.resolve("big-document.pdf");
        Files.write(pdf, new byte[100]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        String content = Files.readString(companion);
        assertTrue(content.contains("enrichment_level: METADATA"),
                "Companion must declare enrichment_level: METADATA");
    }

    @Test
    void oversizedPdf_companionContainsFilename() throws IOException {
        Path pdf = workspaceRoot.resolve("quarterly-report-2026.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        String content = Files.readString(companion);
        assertTrue(content.contains("quarterly-report-2026.pdf"),
                "Companion must reference the source filename");
        assertTrue(content.contains("# quarterly-report-2026.pdf"),
                "Companion must use filename as top-level heading");
    }

    @Test
    void oversizedPdf_companionExplainsSkip() throws IOException {
        Path pdf = workspaceRoot.resolve("large.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        String content = Files.readString(companion);
        assertTrue(content.contains("exceeds the AI enrichment size threshold"),
                "Must explain why AI enrichment was skipped");
    }

    @Test
    void oversizedPdf_companionContainsSizeAndDate() throws IOException {
        Path pdf = workspaceRoot.resolve("sized.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        String content = Files.readString(companion);
        assertTrue(content.contains("**Size:**"), "Companion must include file size");
        assertTrue(content.contains("**Modified:**"), "Companion must include modification date");
    }

    @Test
    void oversizedPdf_companionHasKeywordsSection() throws IOException {
        Path pdf = workspaceRoot.resolve("keywords-test.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        String content = Files.readString(companion);
        assertTrue(content.contains("## Keywords"), "Must have Keywords section");
        assertTrue(content.contains("pdf"), "Keywords must include 'pdf'");
        assertTrue(content.contains("large-document"), "Keywords must include 'large-document'");
    }

    @Test
    void oversizedPdf_companionHasFrontmatter() throws IOException {
        Path pdf = workspaceRoot.resolve("frontmatter-check.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        String content = Files.readString(companion);
        // Check the YAML frontmatter block
        assertTrue(content.contains("```yaml"), "Must have YAML frontmatter block");
        assertTrue(content.contains("companion_for: frontmatter-check.pdf"),
                "Frontmatter must reference source file");
        assertTrue(content.contains("type: PDF"), "Frontmatter must declare type");
    }

    @Test
    void oversizedPdf_companionPathFollowsConvention() throws IOException {
        Path pdf = workspaceRoot.resolve("convention-test.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        assertEquals(pdf.getParent(), companion.getParent(),
                "Companion must be in the same directory as the PDF");
        assertEquals("convention-test.pdf.synthesis.md", companion.getFileName().toString(),
                "Companion filename must end with .synthesis.md");
    }

    @Test
    void oversizedPdf_companionInSubdirectory() throws IOException {
        Path subDir = workspaceRoot.resolve("reports").resolve("2026");
        Files.createDirectories(subDir);
        Path pdf = subDir.resolve("annual-report.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        assertTrue(Files.exists(companion), "Companion must be created in subdirectory");
        assertEquals(subDir, companion.getParent(), "Companion must be co-located with PDF");
    }

    @Test
    void oversizedPdf_generatedCompanionIsDetectedByHasCompanion() throws IOException {
        Path pdf = workspaceRoot.resolve("detection-test.pdf");
        Files.write(pdf, new byte[50]);

        // Before generation
        assertFalse(CompanionFileGenerator.hasCompanion(pdf),
                "hasCompanion must return false before generation");

        CompanionFileGenerator.generateOversizedPdfCompanion(pdf);

        // After generation
        assertTrue(CompanionFileGenerator.hasCompanion(pdf),
                "hasCompanion must return true after generateOversizedPdfCompanion");
    }

    @Test
    void oversizedPdf_companionHasGenerationFooter() throws IOException {
        Path pdf = workspaceRoot.resolve("footer-check.pdf");
        Files.write(pdf, new byte[50]);

        Path companion = CompanionFileGenerator.generateOversizedPdfCompanion(pdf);
        String content = Files.readString(companion);

        assertTrue(content.contains("Generated by Synthesis"),
                "Companion must have Synthesis generation footer");
        assertTrue(content.contains("enrichment: METADATA"),
                "Footer must reflect METADATA enrichment level");
    }

    // ---- EnrichmentLevel.METADATA tests ----

    @Test
    void enrichmentLevel_metadataHasNoAI() {
        assertFalse(EnrichmentLevel.METADATA.hasAI(),
                "METADATA level must not have AI capability");
    }

    @Test
    void enrichmentLevel_metadataHasNoLocalTools() {
        assertFalse(EnrichmentLevel.METADATA.hasLocalTools(),
                "METADATA level must not have local tool capability");
    }

    @Test
    void enrichmentLevel_metadataIsDistinctFromBasic() {
        assertNotEquals(EnrichmentLevel.METADATA, EnrichmentLevel.BASIC,
                "METADATA must be a distinct enum value from BASIC");
    }

    // ---- Threshold constant tests ----

    @Test
    void aiEnrichmentMaxSizeBytes_isPositiveAndReasonable() {
        long threshold = CompanionFileGenerator.AI_ENRICHMENT_MAX_SIZE_BYTES;
        assertTrue(threshold > 0, "Threshold must be positive");
        // Should be between 1 MB and 100 MB to be reasonable
        assertTrue(threshold >= 1L * 1024 * 1024, "Threshold must be at least 1 MB");
        assertTrue(threshold <= 100L * 1024 * 1024, "Threshold must be at most 100 MB");
    }

    @Test
    void aiEnrichmentMaxSizeBytes_matches10MB() {
        assertEquals(10L * 1024 * 1024, CompanionFileGenerator.AI_ENRICHMENT_MAX_SIZE_BYTES,
                "Default threshold must match DirectoryScanner default of 10 MB");
    }
}
