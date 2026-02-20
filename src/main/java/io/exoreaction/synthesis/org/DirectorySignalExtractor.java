package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Extracts content signals from files in a directory to infer directory identity.
 *
 * <p>Scans immediate children (non-recursive), analyzes filename tokens and file
 * extensions, and produces a {@link DirectorySignals} record with inferred content
 * types, format distributions, filename patterns, and a confidence score.
 */
public class DirectorySignalExtractor {

    /**
     * Content signals extracted from the files in a directory.
     *
     * @param inferredTypes    content types (e.g., "meeting-notes", "presentation")
     * @param inferredFormats  file extensions found (e.g., ["md", "pdf"])
     * @param inferredPatterns filename glob patterns (e.g., ["*meeting*"])
     * @param formatCounts     extension to count
     * @param fileCount        number of eligible files scanned
     * @param confidence       based on file count and pattern consistency
     */
    public record DirectorySignals(
            List<String> inferredTypes,
            List<String> inferredFormats,
            List<String> inferredPatterns,
            Map<String, Integer> formatCounts,
            int fileCount,
            double confidence
    ) {}

    private static final Set<String> IMAGE_VIDEO_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "mp4"
    );

    /**
     * Extracts content signals from the immediate children of the given directory.
     *
     * <p>Skips hidden files (names starting with {@code .}), {@code .synthesis.md}
     * companion files, and subdirectories.
     *
     * @param directory the directory to scan
     * @return extracted signals; never {@code null}
     */
    public DirectorySignals extract(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return emptySignals();
        }

        List<Path> eligibleFiles = collectEligibleFiles(directory);
        int fileCount = eligibleFiles.size();

        if (fileCount == 0) {
            return emptySignals();
        }

        // Count extensions
        Map<String, Integer> formatCounts = new LinkedHashMap<>();
        for (Path file : eligibleFiles) {
            String ext = extensionOf(file);
            if (ext != null) {
                formatCounts.merge(ext, 1, Integer::sum);
            }
        }

        // Collect unique extensions as inferredFormats
        List<String> inferredFormats = List.copyOf(formatCounts.keySet());

        // Tokenize filenames and count token frequency
        Map<String, Integer> tokenFrequency = new LinkedHashMap<>();
        for (Path file : eligibleFiles) {
            Set<String> uniqueTokens = tokenize(file.getFileName().toString());
            for (String token : uniqueTokens) {
                tokenFrequency.merge(token, 1, Integer::sum);
            }
        }

        // Tokens appearing in >50% of files (minimum 2 files) become patterns
        List<String> inferredPatterns = new ArrayList<>();
        if (fileCount >= 2) {
            double threshold = fileCount * 0.5;
            for (Map.Entry<String, Integer> entry : tokenFrequency.entrySet()) {
                if (entry.getValue() > threshold) {
                    inferredPatterns.add("*" + entry.getKey() + "*");
                }
            }
        }

        // Infer types
        Set<String> inferredTypesSet = new LinkedHashSet<>();
        inferTypesFromTokens(tokenFrequency, inferredTypesSet);
        inferTypesFromFormats(formatCounts, fileCount, inferredTypesSet);

        List<String> inferredTypes = List.copyOf(inferredTypesSet);

        double confidence = computeConfidence(fileCount);

        return new DirectorySignals(
                inferredTypes,
                inferredFormats,
                List.copyOf(inferredPatterns),
                Map.copyOf(formatCounts),
                fileCount,
                confidence
        );
    }

    // ---- Internal helpers ----

    private List<Path> collectEligibleFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                // Skip hidden files
                if (name.startsWith(".")) {
                    continue;
                }
                // Skip .synthesis.md companion files
                if (name.endsWith(".synthesis.md")) {
                    continue;
                }
                files.add(entry);
            }
        } catch (IOException e) {
            // Return whatever we collected so far
        }
        return files;
    }

    /**
     * Returns the lowercase extension without the dot, or {@code null} if none.
     */
    private String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Tokenizes a filename: splits on {@code -}, {@code _}, space, {@code .};
     * lowercases; filters out tokens shorter than 3 chars and pure numbers.
     * Returns a set (unique tokens per file) to avoid double-counting.
     */
    private Set<String> tokenize(String filename) {
        // Remove the extension first
        int dot = filename.lastIndexOf('.');
        String base = (dot > 0) ? filename.substring(0, dot) : filename;

        String[] parts = base.split("[-_\\s.]+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.length() < 3) {
                continue;
            }
            if (lower.matches("\\d+")) {
                continue;
            }
            tokens.add(lower);
        }
        return tokens;
    }

    private void inferTypesFromTokens(Map<String, Integer> tokenFrequency, Set<String> types) {
        for (String token : tokenFrequency.keySet()) {
            if (token.contains("meeting") || token.contains("minutes") || token.contains("notes")) {
                types.add("meeting-notes");
            }
            if (token.contains("invoice") || token.contains("faktura") || token.contains("billing")) {
                types.add("invoice");
            }
            if (token.contains("presentation") || token.contains("slide") || token.contains("deck")) {
                types.add("presentation");
            }
            if (token.equals("report") || token.equals("analysis") || token.equals("summary")) {
                types.add("report");
            }
        }
    }

    private void inferTypesFromFormats(Map<String, Integer> formatCounts, int fileCount, Set<String> types) {
        int mediaCount = 0;
        for (Map.Entry<String, Integer> entry : formatCounts.entrySet()) {
            if (IMAGE_VIDEO_EXTENSIONS.contains(entry.getKey())) {
                mediaCount += entry.getValue();
            }
        }
        if (fileCount > 0 && (double) mediaCount / fileCount > 0.6) {
            types.add("media");
        }
    }

    private double computeConfidence(int fileCount) {
        if (fileCount == 0) return 0.0;
        if (fileCount <= 3) return 0.5;
        if (fileCount <= 10) return 0.7;
        if (fileCount <= 19) return 0.85;
        return 0.94;
    }

    private DirectorySignals emptySignals() {
        return new DirectorySignals(
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                0,
                0.0
        );
    }
}
