package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Classifies files from the Downloads directory into organizations.
 *
 * <p>Uses multiple signals to determine which organization a file belongs to:
 * <ul>
 *   <li>Filename analysis: keywords in the filename</li>
 *   <li>Content analysis: keywords found in text content (markdown, text files)</li>
 *   <li>File type: routing hints based on extension</li>
 * </ul>
 *
 * <p>Returns a {@link ClassificationResult} with organization, confidence score,
 * and suggested destination path.
 */
public class DownloadsClassifier {

    /** Extensions that should be skipped (software, not documents). */
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            ".deb", ".exe", ".dmg", ".appimage", ".msi", ".rpm",
            ".snap", ".flatpak", ".run"
    );

    /** Extension to subdirectory mapping for routing. */
    private static final Map<String, String> EXTENSION_SUBDIRS = Map.of(
            ".pdf", "business",
            ".md", "business",
            ".txt", "business",
            ".png", "media",
            ".jpg", "media",
            ".jpeg", "media",
            ".mp4", "media",
            ".mov", "media",
            ".zip", "archive",
            ".tar", "archive"
    );

    private final OrganizationRegistry registry;
    private final Map<String, String> keywordIndex;

    /**
     * Creates a classifier using the given organization registry.
     */
    public DownloadsClassifier(OrganizationRegistry registry) {
        this.registry = registry;
        this.keywordIndex = registry.buildKeywordIndex();
    }

    /**
     * Result of classifying a file.
     *
     * @param organization       detected organization name (null if unknown)
     * @param confidence         confidence score (0.0 to 1.0)
     * @param suggestedDestination suggested destination path (null if unknown)
     * @param signals            classification signals that contributed to the result
     * @param shouldSkip         whether this file should be skipped (e.g., software installer)
     */
    public record ClassificationResult(
            String organization,
            double confidence,
            Path suggestedDestination,
            List<String> signals,
            boolean shouldSkip
    ) {
        public boolean isConfident(double threshold) {
            return organization != null && confidence >= threshold;
        }
    }

    /**
     * Classifies a file from the Downloads directory.
     *
     * @param file the file to classify
     * @return classification result with organization and confidence
     */
    public ClassificationResult classify(Path file) {
        List<String> signals = new ArrayList<>();

        // Check if this file should be skipped
        String ext = getExtension(file).toLowerCase();
        if (SKIP_EXTENSIONS.contains(ext)) {
            return new ClassificationResult(null, 0.0, null,
                    List.of("Software installer, skipped"), true);
        }

        // Signal 1: Filename analysis
        Map<String, Double> orgScores = new LinkedHashMap<>();
        analyzeFilename(file.getFileName().toString(), orgScores, signals);

        // Signal 2: Content analysis (for text-readable files)
        if (isTextReadable(ext)) {
            try {
                analyzeContent(file, orgScores, signals);
            } catch (IOException e) {
                signals.add("Content analysis failed: " + e.getMessage());
            }
        }

        // Find the top-scoring organization
        String topOrg = null;
        double topScore = 0.0;
        for (Map.Entry<String, Double> entry : orgScores.entrySet()) {
            if (entry.getValue() > topScore) {
                topScore = entry.getValue();
                topOrg = entry.getKey();
            }
        }

        // Normalize confidence to 0.0-1.0 range
        double confidence = Math.min(topScore, 1.0);

        // Determine suggested destination
        Path destination = null;
        if (topOrg != null) {
            destination = computeDestination(file, topOrg, ext);
        }

        return new ClassificationResult(topOrg, confidence, destination, signals, false);
    }

    /**
     * Classifies a file using both its own content and an enriched companion file.
     *
     * <p>Same logic as {@link #classify(Path)}, but if {@code companionFile} is non-null
     * and exists, also runs content analysis on the companion (which is always text-readable,
     * even when the main file is a binary PDF or image). This unlocks classification of
     * PDFs and images via enriched {@code .synthesis.md} companion text.
     *
     * @param file          the file to classify
     * @param companionFile path to the companion {@code .synthesis.md} file, or {@code null}
     * @return classification result with organization and confidence
     */
    public ClassificationResult classifyWithCompanion(Path file, Path companionFile) {
        List<String> signals = new ArrayList<>();

        // Check if this file should be skipped
        String ext = getExtension(file).toLowerCase();
        if (SKIP_EXTENSIONS.contains(ext)) {
            return new ClassificationResult(null, 0.0, null,
                    List.of("Software installer, skipped"), true);
        }

        // Signal 1: Filename analysis
        Map<String, Double> orgScores = new LinkedHashMap<>();
        analyzeFilename(file.getFileName().toString(), orgScores, signals);

        // Signal 2: Content analysis (for text-readable files)
        if (isTextReadable(ext)) {
            try {
                analyzeContent(file, orgScores, signals);
            } catch (IOException e) {
                signals.add("Content analysis failed: " + e.getMessage());
            }
        }

        // Signal 3: Companion file content (always text-readable, enables PDF/image classification)
        if (companionFile != null && Files.exists(companionFile)) {
            try {
                analyzeContent(companionFile, orgScores, signals);
            } catch (IOException e) {
                signals.add("Companion analysis failed: " + e.getMessage());
            }
        }

        // Find the top-scoring organization
        String topOrg = null;
        double topScore = 0.0;
        for (Map.Entry<String, Double> entry : orgScores.entrySet()) {
            if (entry.getValue() > topScore) {
                topScore = entry.getValue();
                topOrg = entry.getKey();
            }
        }

        // Normalize confidence to 0.0-1.0 range
        double confidence = Math.min(topScore, 1.0);

        // Determine suggested destination
        Path destination = null;
        if (topOrg != null) {
            destination = computeDestination(file, topOrg, ext);
        }

        return new ClassificationResult(topOrg, confidence, destination, signals, false);
    }

    /**
     * Analyzes a filename for organization keywords.
     */
    void analyzeFilename(String filename, Map<String, Double> orgScores, List<String> signals) {
        String filenameLower = filename.toLowerCase()
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ');

        for (Map.Entry<String, String> entry : keywordIndex.entrySet()) {
            String keyword = entry.getKey();
            String orgName = entry.getValue();

            if (filenameLower.contains(keyword.toLowerCase())) {
                double currentScore = orgScores.getOrDefault(orgName, 0.0);
                orgScores.put(orgName, currentScore + 0.5);
                signals.add("Filename contains '" + keyword + "' -> " + orgName);
            }
        }
    }

    /**
     * Analyzes file content for organization keywords.
     * Only reads the first portion of the file to keep analysis fast.
     */
    void analyzeContent(Path file, Map<String, Double> orgScores, List<String> signals)
            throws IOException {
        if (!Files.exists(file) || Files.size(file) > 5_000_000) {
            return; // Skip very large files
        }

        String content;
        try {
            // Read first 5000 characters
            byte[] bytes = Files.readAllBytes(file);
            int limit = Math.min(bytes.length, 5000);
            content = new String(bytes, 0, limit).toLowerCase();
        } catch (Exception e) {
            return; // Binary file or encoding issue
        }

        Set<String> foundOrgs = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : keywordIndex.entrySet()) {
            String keyword = entry.getKey();
            String orgName = entry.getValue();

            if (content.contains(keyword.toLowerCase()) && !foundOrgs.contains(orgName)) {
                double currentScore = orgScores.getOrDefault(orgName, 0.0);
                orgScores.put(orgName, currentScore + 0.3);
                foundOrgs.add(orgName);
                signals.add("Content mentions '" + keyword + "' -> " + orgName);
            }
        }
    }

    /**
     * Computes the suggested destination path for a classified file.
     */
    Path computeDestination(Path file, String orgName, String extension) {
        Optional<Organization> org = registry.findOrganization(orgName);
        if (org.isEmpty()) return null;

        String subDir = EXTENSION_SUBDIRS.getOrDefault(extension.toLowerCase(), "business");
        return org.get().resolvedPath().resolve(subDir).resolve(file.getFileName());
    }

    /**
     * Returns whether a file extension indicates text-readable content.
     */
    private boolean isTextReadable(String extension) {
        return Set.of(".md", ".txt", ".csv", ".json", ".yaml", ".yml",
                ".xml", ".html", ".htm", ".log", ".cfg", ".properties",
                ".java", ".py", ".js", ".ts", ".sh", ".sql")
                .contains(extension.toLowerCase());
    }

    /**
     * Extracts the file extension (including the dot).
     */
    private String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
