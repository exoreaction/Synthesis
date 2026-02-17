package io.exoreaction.synthesis.report;

import java.nio.file.Path;
import java.time.Instant;

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
     * Returns a brief description for display purposes.
     */
    public String briefDescription() {
        return category + ": " + relativePath + " (" + formatSize(sizeBytes) + ")";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
