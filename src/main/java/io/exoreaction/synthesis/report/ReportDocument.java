package io.exoreaction.synthesis.report;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Represents a discovered business document with its content.
 *
 * <p>Business documents include pipeline status files, activity logs,
 * event files, strategy documents, and executive updates. Content
 * is truncated to fit within AI context windows.
 *
 * @param path         absolute path to the document
 * @param relativePath path relative to workspace root
 * @param category     document category (pipeline, activity, event, strategy, executive)
 * @param content      file content (truncated to maxChars)
 * @param lastModified when the file was last modified
 * @param sizeBytes    original file size in bytes
 */
public record ReportDocument(
    Path path,
    String relativePath,
    String category,
    String content,
    Instant lastModified,
    long sizeBytes
) {
    /**
     * Returns true if this document is in an archive or historical directory.
     *
     * <p>Archived documents are included in report context but marked as historical,
     * so the AI gives them lower weight than current documents.
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/51">#51</a>
     */
    public boolean isArchived() {
        String pathStr = path.toString().toLowerCase();
        return pathStr.contains("/archive/") || pathStr.contains("/archived/")
                || pathStr.contains("/legacy/") || pathStr.contains("/historical/")
                || pathStr.contains("/old/");
    }

    /**
     * Returns a brief description for display purposes.
     */
    public String briefDescription() {
        return category + ": " + relativePath
                + " (" + formatSize(sizeBytes) + ", modified " + formatAge(lastModified) + ")";
    }

    static String formatAge(Instant lastModified) {
        long days = ChronoUnit.DAYS.between(
                lastModified.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now());
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
