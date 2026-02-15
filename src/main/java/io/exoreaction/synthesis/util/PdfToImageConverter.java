package io.exoreaction.synthesis.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts PDF pages to images using pdftoppm (from Poppler utilities).
 *
 * <p>This converter enables OCR extraction from scanned PDFs and image-based PDFs
 * by converting each page to a high-resolution PNG image, which can then be processed
 * by Tesseract OCR.
 *
 * <p>Workflow for scanned PDFs:
 * <ol>
 *   <li>Convert PDF pages to PNG images using pdftoppm</li>
 *   <li>Run Tesseract OCR on each image</li>
 *   <li>Combine extracted text into a single .synthesis.md companion file</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   if (PdftoppmDetector.isAvailable()) {
 *       PdfToImageConverter converter = new PdfToImageConverter();
 *       List&lt;Path&gt; images = converter.convertToImages(pdfPath);
 *       // Now run OCR on each image
 *   }
 * </pre>
 *
 * <p>Output format:
 * <ul>
 *   <li>PNG images at 300 DPI (good balance of quality and speed)</li>
 *   <li>Named: {@code filename-1.png}, {@code filename-2.png}, etc.</li>
 *   <li>Temporary files (cleaned up after OCR)</li>
 * </ul>
 *
 * @see PdftoppmDetector
 * @see TesseractOcrExtractor
 */
public class PdfToImageConverter {

    /** Default DPI for PDF to image conversion (300 = standard OCR quality). */
    private static final int DEFAULT_DPI = 300;

    /** Timeout for conversion in milliseconds per page (30 seconds). */
    private static final long TIMEOUT_PER_PAGE_MS = 30_000;

    private final int dpi;

    /**
     * Creates a converter with default DPI (300).
     */
    public PdfToImageConverter() {
        this(DEFAULT_DPI);
    }

    /**
     * Creates a converter with custom DPI.
     *
     * @param dpi dots per inch (150 = fast/low-quality, 300 = standard, 600 = high-quality)
     */
    public PdfToImageConverter(int dpi) {
        this.dpi = dpi;
    }

    /**
     * Converts all pages of a PDF to PNG images.
     *
     * <p>Images are created in a temporary directory and should be cleaned up
     * after OCR processing.
     *
     * @param pdfFile path to PDF file
     * @return list of paths to generated PNG images (one per page)
     * @throws IOException              if file cannot be read or conversion fails
     * @throws IllegalStateException    if pdftoppm is not available
     * @throws ConversionException      if conversion fails
     */
    public List<Path> convertToImages(Path pdfFile) throws IOException {
        if (!PdftoppmDetector.isAvailable()) {
            throw new IllegalStateException(
                    "pdftoppm is not available. Install with: " + PdftoppmDetector.getInstallHint());
        }

        if (!Files.exists(pdfFile)) {
            throw new IOException("PDF file not found: " + pdfFile);
        }

        // Create temporary output directory
        Path outputDir = Files.createTempDirectory("synthesis-pdf-");
        String baseName = pdfFile.getFileName().toString();
        baseName = baseName.substring(0, baseName.lastIndexOf('.'));

        // Build pdftoppm command
        List<String> command = new ArrayList<>();
        command.add(PdftoppmDetector.getPdftoppmPath());

        // Output format: PNG
        command.add("-png");

        // Resolution (DPI)
        command.add("-r");
        command.add(String.valueOf(dpi));

        // Input PDF
        command.add(pdfFile.toAbsolutePath().toString());

        // Output prefix
        String outputPrefix = outputDir.resolve(baseName).toString();
        command.add(outputPrefix);

        // Execute conversion
        try {
            executeConversion(command, pdfFile);
        } catch (Exception e) {
            throw new ConversionException(
                    "Failed to convert PDF to images: " + e.getMessage(), e);
        }

        // Collect generated image files
        List<Path> images = new ArrayList<>();
        try (var stream = Files.list(outputDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted() // Ensure page order
                    .forEach(images::add);
        }

        if (images.isEmpty()) {
            throw new ConversionException("No images were generated from PDF");
        }

        return images;
    }

    /**
     * Converts a specific page range of a PDF to PNG images.
     *
     * @param pdfFile  path to PDF file
     * @param firstPage first page to convert (1-indexed)
     * @param lastPage  last page to convert (1-indexed, inclusive)
     * @return list of paths to generated PNG images
     * @throws IOException if conversion fails
     */
    public List<Path> convertPagesToImages(Path pdfFile, int firstPage, int lastPage)
            throws IOException {
        if (!PdftoppmDetector.isAvailable()) {
            throw new IllegalStateException(
                    "pdftoppm is not available. Install with: " + PdftoppmDetector.getInstallHint());
        }

        if (!Files.exists(pdfFile)) {
            throw new IOException("PDF file not found: " + pdfFile);
        }

        // Create temporary output directory
        Path outputDir = Files.createTempDirectory("synthesis-pdf-");
        String baseName = pdfFile.getFileName().toString();
        baseName = baseName.substring(0, baseName.lastIndexOf('.'));

        // Build pdftoppm command with page range
        List<String> command = new ArrayList<>();
        command.add(PdftoppmDetector.getPdftoppmPath());

        // Output format: PNG
        command.add("-png");

        // Resolution (DPI)
        command.add("-r");
        command.add(String.valueOf(dpi));

        // Page range
        command.add("-f");
        command.add(String.valueOf(firstPage));
        command.add("-l");
        command.add(String.valueOf(lastPage));

        // Input PDF
        command.add(pdfFile.toAbsolutePath().toString());

        // Output prefix
        String outputPrefix = outputDir.resolve(baseName).toString();
        command.add(outputPrefix);

        // Execute conversion
        try {
            executeConversion(command, pdfFile);
        } catch (Exception e) {
            throw new ConversionException(
                    "Failed to convert PDF pages to images: " + e.getMessage(), e);
        }

        // Collect generated image files
        List<Path> images = new ArrayList<>();
        try (var stream = Files.list(outputDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted() // Ensure page order
                    .forEach(images::add);
        }

        return images;
    }

    /**
     * Executes the pdftoppm conversion command.
     *
     * @param command list of command arguments
     * @param pdfFile path to PDF file (for context in error messages)
     * @throws IOException if execution fails
     */
    private void executeConversion(List<String> command, Path pdfFile)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        Process process = pb.start();

        // Read output
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = process.getInputStream().read(buffer)) != -1) {
            output.append(new String(buffer, 0, bytesRead));

            // Check timeout (per page estimate)
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > TIMEOUT_PER_PAGE_MS * 100) { // Max 100 pages * 30s
                process.destroyForcibly();
                throw new ConversionException(
                        "Conversion timed out after " + (elapsed / 1000) + " seconds");
            }
        }

        // Wait for completion
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversion interrupted", e);
        }

        if (exitCode != 0) {
            throw new ConversionException(
                    "Conversion failed with exit code " + exitCode + ": " + output);
        }
    }

    /**
     * Cleans up temporary image files after OCR processing.
     *
     * @param images list of image paths to delete
     */
    public static void cleanupImages(List<Path> images) {
        if (images == null || images.isEmpty()) {
            return;
        }

        // Delete all image files
        for (Path image : images) {
            try {
                Files.deleteIfExists(image);
            } catch (IOException e) {
                // Best effort cleanup
            }
        }

        // Delete parent directory if empty
        try {
            Path parentDir = images.get(0).getParent();
            if (parentDir != null && Files.isDirectory(parentDir)) {
                try (var stream = Files.list(parentDir)) {
                    if (stream.findAny().isEmpty()) {
                        Files.deleteIfExists(parentDir);
                    }
                }
            }
        } catch (IOException e) {
            // Best effort cleanup
        }
    }

    /**
     * Estimates the number of pages in a PDF by checking file size.
     * This is a rough heuristic (assumes 50KB per page average).
     *
     * @param pdfFile path to PDF file
     * @return estimated page count
     * @throws IOException if file cannot be read
     */
    public static int estimatePageCount(Path pdfFile) throws IOException {
        long fileSizeBytes = Files.size(pdfFile);
        // Rough estimate: 50KB per page
        int estimate = (int) (fileSizeBytes / 50_000);
        return Math.max(1, estimate); // At least 1 page
    }

    /**
     * Exception thrown when PDF to image conversion fails.
     */
    public static class ConversionException extends RuntimeException {
        public ConversionException(String message) {
            super(message);
        }

        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
