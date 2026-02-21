package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EnrichmentSignatureExtractor}.
 */
class EnrichmentSignatureExtractorTest {

    private final EnrichmentSignatureExtractor extractor = new EnrichmentSignatureExtractor();

    @TempDir
    Path tempDir;

    // --- Tier 1: Companion file extraction ---

    @Test
    void extract_withCompanionFile_usesCompanion() throws IOException {
        Path file = tempDir.resolve("demo-video.mp4");
        Files.writeString(file, "binary content");

        Path companion = tempDir.resolve("demo-video.mp4.synthesis.md");
        Files.writeString(companion, """
                # demo-video.mp4

                ```yaml
                companion_for: demo-video.mp4
                type: VIDEO
                enrichment_level: BASIC
                ```

                **Type:** VIDEO (MP4)
                **Keywords:** renewable energy, SDD methodology, workshop
                **Size:** 45.2 MB

                ## AI Summary

                This video shows a demonstration of GreenField Energy solutions.
                Jane Smith presents the renewable energy approach.
                """);

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertFalse(sig.isEmpty());
        assertEquals("companion", sig.source());
        assertTrue(sig.topics().contains("renewable energy"));
        assertTrue(sig.topics().contains("sdd methodology"));
        assertTrue(sig.topics().contains("workshop"));
        assertEquals("video", sig.documentType());
    }

    @Test
    void extract_withCompanionFile_extractsEntities() throws IOException {
        Path file = tempDir.resolve("proposal.pdf");
        Files.writeString(file, "binary");

        Path companion = tempDir.resolve("proposal.pdf.synthesis.md");
        Files.writeString(companion, """
                # proposal.pdf

                ```yaml
                type: PDF
                ```

                This document references GreenField Energy and Jane Smith.
                Also mentions SpareBank One development team.
                """);

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertFalse(sig.isEmpty());
        assertTrue(sig.entities().contains("GreenField Energy"),
                "Should extract 'GreenField Energy' as entity, found: " + sig.entities());
        assertTrue(sig.entities().contains("Jane Smith"),
                "Should extract 'Jane Smith' as entity, found: " + sig.entities());
    }

    // --- Tier 2: Content header extraction ---

    @Test
    void extract_markdownFile_extractsFromHeaders() throws IOException {
        Path file = tempDir.resolve("strategy-doc.md");
        Files.writeString(file, """
                # Renewable Energy Strategy

                ## Proposal for GreenField Energy

                This document outlines our approach to delivering
                renewable energy solutions for the Nordic market.

                ### Implementation Plan

                The plan covers Q1 2026 deployment milestones.
                """);

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertFalse(sig.isEmpty());
        assertEquals("content-headers", sig.source());
        assertTrue(sig.topics().contains("renewable"),
                "Should contain 'renewable', found: " + sig.topics());
        assertTrue(sig.topics().contains("energy"),
                "Should contain 'energy', found: " + sig.topics());
        // "strategy" is detected before "proposal" because it appears in the first heading
        assertTrue(sig.documentType() != null,
                "Should detect a document type from headings");
        assertTrue("strategy".equals(sig.documentType()) || "proposal".equals(sig.documentType()),
                "Should detect 'strategy' or 'proposal' from headings, got: " + sig.documentType());
    }

    @Test
    void extract_markdownFile_extractsEntitiesFromBody() throws IOException {
        Path file = tempDir.resolve("meeting-notes.md");
        Files.writeString(file, """
                # Meeting Notes

                Attendees: Thor Henning discussed with Vidar Moe
                about the SpareBank One rollout.
                """);

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertFalse(sig.isEmpty());
        assertTrue(sig.entities().contains("Thor Henning"),
                "Should find 'Thor Henning', found: " + sig.entities());
        assertTrue(sig.entities().contains("Vidar Moe"),
                "Should find 'Vidar Moe', found: " + sig.entities());
    }

    // --- Tier 3: Filename heuristic ---

    @Test
    void extract_unknownBinaryFile_usesFilenameHeuristic() throws IOException {
        Path file = tempDir.resolve("greenfield-proposal-2026.xlsx");
        Files.writeString(file, "binary content");

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertFalse(sig.isEmpty());
        assertEquals("filename-heuristic", sig.source());
        assertTrue(sig.topics().contains("greenfield"),
                "Should tokenize filename to 'greenfield', found: " + sig.topics());
        assertTrue(sig.topics().contains("proposal"),
                "Should tokenize filename to 'proposal', found: " + sig.topics());
        assertEquals("proposal", sig.documentType(),
                "Should detect 'proposal' document type from filename");
    }

    @Test
    void extract_filenameWithDatePattern_infersTimeframe() throws IOException {
        Path file = tempDir.resolve("report-2026-Q1.pdf");
        Files.writeString(file, "binary");

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertEquals("2026-Q1", sig.timeframe(),
                "Should infer timeframe from filename date pattern");
    }

    @Test
    void extract_filenameWithYearMonth_infersQuarter() throws IOException {
        Path file = tempDir.resolve("summary-2026-02.pdf");
        Files.writeString(file, "binary");

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        assertEquals("2026-Q1", sig.timeframe(),
                "February should map to Q1");
    }

    // --- Edge cases ---

    @Test
    void extract_nullFile_returnsEmpty() {
        EnrichmentSignature sig = extractor.extract(null, tempDir);
        assertTrue(sig.isEmpty());
    }

    @Test
    void extract_nonExistentFile_returnsEmpty() {
        Path file = tempDir.resolve("nonexistent.txt");
        EnrichmentSignature sig = extractor.extract(file, tempDir);
        assertTrue(sig.isEmpty());
    }

    @Test
    void extract_directory_returnsEmpty() {
        EnrichmentSignature sig = extractor.extract(tempDir, tempDir);
        assertTrue(sig.isEmpty());
    }

    @Test
    void extract_emptyCompanion_fallsBackToFilename() throws IOException {
        Path file = tempDir.resolve("report-summary.docx");
        Files.writeString(file, "binary");

        Path companion = tempDir.resolve("report-summary.docx.synthesis.md");
        Files.writeString(companion, "");

        EnrichmentSignature sig = extractor.extract(file, tempDir);

        // Empty companion should fall through to filename heuristic
        assertEquals("filename-heuristic", sig.source());
        assertTrue(sig.topics().contains("report"));
        assertTrue(sig.topics().contains("summary"));
    }

    // --- Timeframe inference ---

    @Test
    void inferTimeframeFromFilename_quarterPattern() {
        assertEquals("2026-Q1", EnrichmentSignatureExtractor.inferTimeframeFromFilename("report-2026-Q1.pdf"));
        assertEquals("2025-Q4", EnrichmentSignatureExtractor.inferTimeframeFromFilename("Q4-2025-summary.md"));
    }

    @Test
    void inferTimeframeFromFilename_yearMonthPattern() {
        assertEquals("2026-Q1", EnrichmentSignatureExtractor.inferTimeframeFromFilename("data-2026-01.csv"));
        assertEquals("2026-Q2", EnrichmentSignatureExtractor.inferTimeframeFromFilename("report_2026_04.pdf"));
        assertEquals("2026-Q3", EnrichmentSignatureExtractor.inferTimeframeFromFilename("stats-2026-09.xlsx"));
        assertEquals("2026-Q4", EnrichmentSignatureExtractor.inferTimeframeFromFilename("summary-2026-12.md"));
    }

    @Test
    void inferTimeframeFromFilename_noPattern_returnsNull() {
        assertNull(EnrichmentSignatureExtractor.inferTimeframeFromFilename("README.md"));
        assertNull(EnrichmentSignatureExtractor.inferTimeframeFromFilename("photo.jpg"));
    }

    // --- Companion content parsing ---

    @Test
    void parseCompanionContent_nullOrBlank_returnsNull() {
        assertNull(extractor.parseCompanionContent(null));
        assertNull(extractor.parseCompanionContent(""));
        assertNull(extractor.parseCompanionContent("   "));
    }

    @Test
    void parseCompanionContent_withKeywordsAndType() {
        String content = """
                # video.mp4

                ```yaml
                type: VIDEO
                ```

                **Keywords:** ai security, compliance, gdpr
                """;

        EnrichmentSignature sig = extractor.parseCompanionContent(content);

        assertNotNull(sig);
        assertEquals("video", sig.documentType());
        assertEquals(3, sig.topics().size());
        assertTrue(sig.topics().contains("ai security"));
        assertTrue(sig.topics().contains("compliance"));
        assertTrue(sig.topics().contains("gdpr"));
    }
}
