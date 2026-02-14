package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.*;

/**
 * Analyzes PDF files to extract text content, metadata, and structure.
 *
 * <p>Uses Apache PDFBox to extract:
 * <ul>
 *   <li>Text content (for full-text search indexing)</li>
 *   <li>Document metadata (title, author, subject, creation date)</li>
 *   <li>Page count and structural information</li>
 * </ul>
 *
 * <p>Handles encrypted and corrupted PDFs gracefully by returning
 * minimal metadata without text content.
 */
public class PdfAnalyzer implements FileAnalyzer {

    /** Maximum characters to extract from PDF text for indexing. */
    private static final int MAX_TEXT_LENGTH = 50_000;

    /** Maximum pages to attempt text extraction from. */
    private static final int MAX_PAGES_TO_EXTRACT = 100;

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.PDF;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        try (PDDocument document = Loader.loadPDF(metadata.path().toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            int pageCount = document.getNumberOfPages();

            // Extract metadata
            String title = safeString(info.getTitle());
            String author = safeString(info.getAuthor());
            String subject = safeString(info.getSubject());
            String creator = safeString(info.getCreator());

            // Build summary
            StringBuilder summaryBuilder = new StringBuilder();
            summaryBuilder.append("PDF document");
            if (!title.isEmpty()) {
                summaryBuilder.append(": ").append(truncate(title, 120));
            }
            summaryBuilder.append(" (").append(pageCount).append(pageCount == 1 ? " page" : " pages").append(")");
            if (!author.isEmpty()) {
                summaryBuilder.append(" by ").append(truncate(author, 60));
            }
            String summary = summaryBuilder.toString();

            // Extract text content for indexing
            String textContent = extractText(document, pageCount);

            // Keywords from metadata
            List<String> keywords = new ArrayList<>();
            keywords.add("pdf");
            if (!title.isEmpty()) {
                // Add title words as keywords
                Arrays.stream(title.split("\\s+"))
                        .filter(w -> w.length() > 2)
                        .map(String::toLowerCase)
                        .limit(10)
                        .forEach(keywords::add);
            }
            if (!subject.isEmpty()) {
                keywords.add(subject.toLowerCase());
            }
            if (!author.isEmpty()) {
                keywords.add(author.toLowerCase());
            }

            // Headings: title + first few lines that look like headings
            List<String> headings = new ArrayList<>();
            if (!title.isEmpty()) {
                headings.add(title);
            }
            extractPotentialHeadings(textContent, headings);

            // Structure
            String structure = String.format("PDF, %d pages, %s",
                    pageCount, FileUtils.formatSize(metadata.sizeBytes()));
            if (!author.isEmpty()) structure += ", author: " + author;
            if (!creator.isEmpty()) structure += ", creator: " + creator;

            // Metrics
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("pageCount", pageCount);
            metrics.put("textLength", textContent.length());
            if (!title.isEmpty()) metrics.put("title", title);
            if (!author.isEmpty()) metrics.put("author", author);

            return AnalysisResult.builder()
                    .summary(summary)
                    .headings(headings)
                    .keywords(keywords)
                    .structure(structure)
                    .metrics(metrics)
                    .contentPreview(textContent)
                    .build();

        } catch (IOException e) {
            // PDF is encrypted, corrupted, or password-protected
            return AnalysisResult.builder()
                    .summary("PDF document (unreadable: " + e.getMessage() + ")")
                    .keywords(List.of("pdf"))
                    .structure("PDF, unreadable")
                    .build();
        }
    }

    /**
     * Extracts text content from the PDF document.
     * Limits extraction to avoid memory issues with very large PDFs.
     */
    private String extractText(PDDocument document, int pageCount) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            // Limit pages to extract
            int maxPage = Math.min(pageCount, MAX_PAGES_TO_EXTRACT);
            stripper.setEndPage(maxPage);

            String text = stripper.getText(document);

            // Truncate if too long
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }

            return text.strip();
        } catch (Exception e) {
            // Text extraction failed -- return empty
            return "";
        }
    }

    /**
     * Extracts potential headings from the first part of the text.
     * Looks for short, uppercase or title-case lines at the beginning.
     */
    private void extractPotentialHeadings(String text, List<String> headings) {
        if (text.isEmpty()) return;

        String[] lines = text.split("\n");
        int count = 0;
        for (String line : lines) {
            if (count >= 10) break;

            String trimmed = line.strip();
            // Skip empty lines and very long lines
            if (trimmed.isEmpty() || trimmed.length() > 100) continue;

            // Heuristic: lines that are shorter, don't end with periods,
            // and are near the top are likely headings
            if (count < 5 && !trimmed.endsWith(".") && !trimmed.endsWith(",")
                    && trimmed.length() < 80) {
                headings.add(trimmed);
            }
            count++;
        }
    }

    private static String safeString(String value) {
        return value != null ? value.strip() : "";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
