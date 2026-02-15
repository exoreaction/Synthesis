package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PdfToImageConverter.
 * Most tests require pdftoppm to be installed and are only run if available.
 */
class PdfToImageConverterTest {

    @Test
    void testConverterCreation() {
        // Should be able to create converter with default DPI
        PdfToImageConverter converter = new PdfToImageConverter();
        assertNotNull(converter);

        // Should be able to create converter with custom DPI
        PdfToImageConverter customConverter = new PdfToImageConverter(150);
        assertNotNull(customConverter);
    }

    @Test
    void testConvertToImagesThrowsWhenPdftoppmNotAvailable() {
        if (PdftoppmDetector.isAvailable()) {
            // Skip this test if pdftoppm is actually available
            return;
        }

        PdfToImageConverter converter = new PdfToImageConverter();
        Path dummyFile = Path.of("/tmp/dummy.pdf");

        assertThrows(IllegalStateException.class, () -> {
            converter.convertToImages(dummyFile);
        }, "Should throw IllegalStateException when pdftoppm is not available");
    }

    @Test
    void testConvertToImagesThrowsOnMissingFile() {
        if (!PdftoppmDetector.isAvailable()) {
            return; // Skip if pdftoppm not available
        }

        PdfToImageConverter converter = new PdfToImageConverter();
        Path nonExistentFile = Path.of("/tmp/nonexistent-" + System.currentTimeMillis() + ".pdf");

        assertThrows(IOException.class, () -> {
            converter.convertToImages(nonExistentFile);
        }, "Should throw IOException when file doesn't exist");
    }

    @Test
    void testEstimatePageCount() throws IOException {
        // Create a temporary file to test estimation
        Path tempFile = Files.createTempFile("test", ".pdf");
        try {
            // Write some bytes (e.g., 100KB)
            Files.write(tempFile, new byte[100_000]);

            int estimate = PdfToImageConverter.estimatePageCount(tempFile);
            assertTrue(estimate >= 1, "Should estimate at least 1 page");
            assertTrue(estimate <= 10, "Should estimate reasonable page count for 100KB file");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testCleanupImages() throws IOException {
        // Create temporary image files
        Path tempDir = Files.createTempDirectory("test-cleanup");
        List<Path> images = List.of(
                Files.createFile(tempDir.resolve("page-1.png")),
                Files.createFile(tempDir.resolve("page-2.png"))
        );

        // Verify files exist
        assertTrue(Files.exists(images.get(0)));
        assertTrue(Files.exists(images.get(1)));

        // Cleanup
        PdfToImageConverter.cleanupImages(images);

        // Verify files are deleted
        assertFalse(Files.exists(images.get(0)));
        assertFalse(Files.exists(images.get(1)));

        // Cleanup directory
        Files.deleteIfExists(tempDir);
    }

    @Test
    void testConversionException() {
        PdfToImageConverter.ConversionException ex =
                new PdfToImageConverter.ConversionException("Test error");

        assertEquals("Test error", ex.getMessage());

        Exception cause = new IOException("Underlying error");
        PdfToImageConverter.ConversionException exWithCause =
                new PdfToImageConverter.ConversionException("Test error with cause", cause);

        assertEquals("Test error with cause", exWithCause.getMessage());
        assertEquals(cause, exWithCause.getCause());
    }

    /**
     * Integration test that creates a simple PDF and converts it to images.
     * Only runs if pdftoppm is available and gs (Ghostscript) is available to generate PDF.
     */
    @Test
    @EnabledIf("io.exoreaction.synthesis.util.PdfToImageConverterTest#canRunIntegrationTest")
    void testConvertToImagesIntegration() throws IOException, InterruptedException {
        // Create a temporary PDF using Ghostscript
        Path tempDir = Files.createTempDirectory("pdf-test");
        Path pdfFile = tempDir.resolve("test.pdf");

        try {
            // Generate a simple 1-page PDF
            ProcessBuilder pb = new ProcessBuilder(
                    "gs",
                    "-dBATCH",
                    "-dNOPAUSE",
                    "-sDEVICE=pdfwrite",
                    "-sOutputFile=" + pdfFile.toAbsolutePath(),
                    "-c", "<</PageSize [612 792]>> setpagedevice",
                    "-f", "-"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getOutputStream().close(); // No input needed
            process.getInputStream().readAllBytes(); // consume output
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                // Ghostscript not available or failed, skip test
                return;
            }

            // Convert PDF to images
            PdfToImageConverter converter = new PdfToImageConverter();
            List<Path> images = converter.convertToImages(pdfFile);

            // Verify results
            assertNotNull(images);
            assertFalse(images.isEmpty(), "Should generate at least one image");
            assertEquals(1, images.size(), "Should generate 1 image for 1-page PDF");

            // Verify image file exists and is PNG
            Path image = images.get(0);
            assertTrue(Files.exists(image), "Image file should exist");
            assertTrue(image.getFileName().toString().endsWith(".png"),
                    "Image should be PNG format");
            assertTrue(Files.size(image) > 0, "Image should have content");

            // Cleanup
            PdfToImageConverter.cleanupImages(images);
            assertFalse(Files.exists(image), "Image should be cleaned up");

        } finally {
            // Cleanup
            Files.deleteIfExists(pdfFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * Helper method for @EnabledIf annotation.
     * Returns true if both pdftoppm and Ghostscript are available for integration testing.
     */
    public static boolean canRunIntegrationTest() {
        if (!PdftoppmDetector.isAvailable()) {
            return false;
        }

        // Check if Ghostscript is available
        try {
            ProcessBuilder pb = new ProcessBuilder("gs", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
