package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.util.FileUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregate result of scanning a workspace directory.
 * Contains all discovered file metadata plus summary statistics.
 *
 * @param files        all discovered file metadata
 * @param scanTime     when the scan was performed
 * @param duration     how long the scan took
 * @param rootPath     workspace root that was scanned
 */
public record ScanResult(
        List<FileMetadata> files,
        Instant scanTime,
        Duration duration,
        String rootPath
) {

    /** Total number of files discovered. */
    public int fileCount() {
        return files.size();
    }

    /** Total size of all files in bytes. */
    public long totalSizeBytes() {
        return files.stream().mapToLong(FileMetadata::sizeBytes).sum();
    }

    /** Count of files by type. */
    public Map<FileUtils.FileType, Long> countByType() {
        return files.stream()
                .collect(Collectors.groupingBy(FileMetadata::fileType, Collectors.counting()));
    }

    /** Count of files by extension. */
    public Map<String, Long> countByExtension() {
        return files.stream()
                .filter(f -> !f.extension().isEmpty())
                .collect(Collectors.groupingBy(FileMetadata::extension, Collectors.counting()));
    }

    /** Count of files by detected programming language. */
    public Map<String, Long> countByLanguage() {
        return files.stream()
                .filter(f -> f.language() != null)
                .collect(Collectors.groupingBy(FileMetadata::language, Collectors.counting()));
    }

    /** Number of files whose content should be indexed. */
    public long indexableFileCount() {
        return files.stream().filter(FileMetadata::isIndexableContent).count();
    }
}
