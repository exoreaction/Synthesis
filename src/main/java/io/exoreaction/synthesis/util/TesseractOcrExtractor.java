package io.exoreaction.synthesis.util;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text from images using Tesseract OCR.
 *
 * <p>Tesseract is an open-source OCR engine that recognizes text in images
 * across 100+ languages. This extractor uses the tess4j Java wrapper to
 * interface with native Tesseract libraries.
 *
 * <p>Supported image formats:
 * <ul>
 *   <li>PNG, JPEG, TIFF (best quality for OCR)</li>
 *   <li>BMP, GIF, WebP (also supported)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   if (TesseractDetector.isAvailable()) {
 *       TesseractOcrExtractor extractor = new TesseractOcrExtractor();
 *       OcrResult result = extractor.extractText(imagePath);
 *       if (result.success()) {
 *           String text = result.text();
 *           int confidence = result.confidence();
 *       }
 *   }
 * </pre>
 *
 * <p>Language support:
 * <ul>
 *   <li>Default: English (eng)</li>
 *   <li>Multi-language: Pass multiple ISO 639-2 codes (e.g., "eng+nor+swe")</li>
 *   <li>Install language data: {@code tesseract-ocr-<lang>} packages</li>
 * </ul>
 *
 * @see TesseractDetector
 */
public class TesseractOcrExtractor {

    /** Default language for OCR (English). */
    private static final String DEFAULT_LANGUAGE = "eng";

    /** Default page segmentation mode (auto). */
    private static final int DEFAULT_PSM = 3; // Fully automatic page segmentation

    /** Minimum confidence threshold for accepting OCR results (0-100). */
    private static final int MIN_CONFIDENCE = 30;

    private final String language;
    private final int psm;

    /**
     * Creates an OCR extractor with default settings (English, auto PSM).
     */
    public TesseractOcrExtractor() {
        this(DEFAULT_LANGUAGE, DEFAULT_PSM);
    }

    /**
     * Creates an OCR extractor with custom language.
     *
     * @param language Tesseract language code (e.g., "eng", "nor", "eng+nor")
     */
    public TesseractOcrExtractor(String language) {
        this(language, DEFAULT_PSM);
    }

    /**
     * Creates an OCR extractor with custom language and page segmentation mode.
     *
     * @param language Tesseract language code
     * @param psm      Page segmentation mode (0-13)
     *                 <ul>
     *                   <li>0: Orientation and script detection only</li>
     *                   <li>3: Fully automatic page segmentation (default)</li>
     *                   <li>6: Assume uniform block of text</li>
     *                   <li>11: Sparse text (find as much text as possible)</li>
     *                 </ul>
     */
    public TesseractOcrExtractor(String language, int psm) {
        this.language = language;
        this.psm = psm;
    }

    /**
     * Extracts text from an image file using OCR.
     *
     * <p>This method is synchronous and may take 1-5 seconds per page depending on
     * image size and complexity.
     *
     * @param imageFile path to image file (PNG, JPEG, TIFF, etc.)
     * @return OCR result with extracted text and confidence
     * @throws IOException           if file cannot be read
     * @throws IllegalStateException if Tesseract is not available
     */
    public OcrResult extractText(Path imageFile) throws IOException {
        if (!TesseractDetector.isAvailable()) {
            throw new IllegalStateException(
                    "Tesseract is not available. Install with: " + TesseractDetector.getInstallHint());
        }

        if (!Files.exists(imageFile)) {
            throw new IOException("Image file not found: " + imageFile);
        }

        // Configure Tesseract instance
        Tesseract tesseract = new Tesseract();

        // Set data path if available
        String dataPath = TesseractDetector.getDataPath();
        if (dataPath != null) {
            tesseract.setDatapath(dataPath);
        }

        // Set language
        tesseract.setLanguage(language);

        // Set page segmentation mode
        tesseract.setPageSegMode(psm);

        // Set OEM (OCR Engine Mode): LSTM only (fastest, most accurate)
        tesseract.setOcrEngineMode(1);

        long startTime = System.currentTimeMillis();

        try {
            // Perform OCR
            String text = tesseract.doOCR(imageFile.toFile());

            // Get confidence (average word confidence)
            // Note: tess4j doesn't expose confidence easily, so we estimate
            int confidence = estimateConfidence(text);

            long duration = System.currentTimeMillis() - startTime;

            return new OcrResult(
                    true,
                    text != null ? text.trim() : "",
                    confidence,
                    language,
                    duration,
                    null
            );

        } catch (TesseractException e) {
            long duration = System.currentTimeMillis() - startTime;
            return OcrResult.failed("OCR failed: " + e.getMessage(), duration);
        }
    }

    /**
     * Extracts text from multiple images (batch processing).
     *
     * @param imageFiles list of image file paths
     * @return list of OCR results (same order as input)
     * @throws IOException if any file cannot be read
     */
    public List<OcrResult> extractTextBatch(List<Path> imageFiles) throws IOException {
        List<OcrResult> results = new ArrayList<>();
        for (Path imageFile : imageFiles) {
            results.add(extractText(imageFile));
        }
        return results;
    }

    /**
     * Estimates OCR confidence based on text characteristics.
     * This is a heuristic since tess4j doesn't easily expose per-word confidence.
     *
     * @param text extracted text
     * @return estimated confidence (0-100)
     */
    private int estimateConfidence(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int score = 60; // Base score for non-empty text

        // Bonus: Contains alphabetic characters
        if (text.matches(".*[a-zA-Z]+.*")) {
            score += 10;
        }

        // Bonus: Contains words (not just random characters)
        if (text.matches(".*\\b[a-zA-Z]{3,}\\b.*")) {
            score += 10;
        }

        // Bonus: Has proper spacing and punctuation
        if (text.matches(".*[.!?].*") && text.contains(" ")) {
            score += 10;
        }

        // Penalty: Too many special characters (likely noise)
        long specialChars = text.chars().filter(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)).count();
        if (specialChars > text.length() * 0.3) {
            score -= 20;
        }

        return Math.min(100, Math.max(0, score));
    }

    /**
     * Result of an OCR extraction.
     *
     * @param success      true if OCR succeeded
     * @param text         extracted text
     * @param confidence   estimated confidence (0-100)
     * @param language     language used for OCR
     * @param durationMs   extraction duration in milliseconds
     * @param errorMessage error message if failed
     */
    public record OcrResult(
            boolean success,
            String text,
            int confidence,
            String language,
            long durationMs,
            String errorMessage
    ) {
        /**
         * Creates a failed OCR result.
         */
        public static OcrResult failed(String errorMessage, long durationMs) {
            return new OcrResult(false, null, 0, null, durationMs, errorMessage);
        }

        /**
         * Returns true if the OCR result has sufficient confidence.
         */
        public boolean hasGoodConfidence() {
            return success && confidence >= MIN_CONFIDENCE;
        }
    }

    /**
     * Returns a list of available Tesseract languages on the system.
     *
     * @return list of language codes (e.g., ["eng", "nor", "swe"])
     * @throws IOException if language detection fails
     */
    public static List<String> getAvailableLanguages() throws IOException {
        if (!TesseractDetector.isAvailable()) {
            return List.of();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    TesseractDetector.getTesseractPath(),
                    "--list-langs"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // Parse output: skip header line, then each line is a language
                return output.lines()
                        .skip(1) // Skip "List of available languages"
                        .filter(line -> !line.isBlank() && !line.startsWith("tessdata"))
                        .map(String::trim)
                        .toList();
            }
        } catch (Exception e) {
            throw new IOException("Failed to detect available languages", e);
        }

        return List.of();
    }
}
