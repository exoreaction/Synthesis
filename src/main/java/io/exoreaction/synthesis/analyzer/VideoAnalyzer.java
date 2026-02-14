package io.exoreaction.synthesis.analyzer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FfprobeDetector;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Analyzes video files to extract metadata and companion transcripts.
 *
 * <p>Uses a two-tier metadata extraction strategy:
 * <ol>
 *   <li><strong>Primary:</strong> metadata-extractor (pure Java) -- handles MP4, MOV, AVI, M4V, 3GP
 *       (~90% of videos). Zero external dependencies.</li>
 *   <li><strong>Fallback:</strong> ffprobe (external binary) -- handles MKV, WebM, and edge cases
 *       where metadata-extractor fails. Optional; Synthesis works without it.</li>
 * </ol>
 *
 * <p>Additionally extracts companion transcript content (makes videos searchable):
 * <ul>
 *   <li>Companion transcripts are detected by looking for files with the same
 *       base name but different extension (e.g., video.mp4 -> video.txt, video.srt,
 *       video.vtt, video.md).</li>
 * </ul>
 *
 * @see FfprobeDetector
 */
public class VideoAnalyzer implements FileAnalyzer {

    /** Extensions that may contain video transcripts or metadata. */
    private static final Set<String> TRANSCRIPT_EXTENSIONS = Set.of(
            ".txt", ".srt", ".vtt", ".md", ".json", ".yaml", ".yml"
    );

    /**
     * Metadata extraction method used for a particular file.
     * Tracked for reporting purposes (verbose mode, scan summary).
     */
    public enum ExtractionMethod {
        /** Pure Java metadata-extractor succeeded. */
        METADATA_EXTRACTOR,
        /** External ffprobe binary succeeded. */
        FFPROBE,
        /** Basic metadata only (format, size). Neither extractor succeeded. */
        BASIC
    }

    /**
     * Result of the last analysis, including which extraction method was used.
     * Used by ScanCommand for verbose output and summary reporting.
     */
    private ExtractionMethod lastExtractionMethod = ExtractionMethod.BASIC;

    /**
     * Returns the extraction method used for the most recent {@link #analyze} call.
     */
    public ExtractionMethod getLastExtractionMethod() {
        return lastExtractionMethod;
    }

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.VIDEO
                || metadata.fileType() == FileUtils.FileType.AUDIO;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String ext = metadata.extension().toLowerCase();
        boolean isAudio = metadata.fileType() == FileUtils.FileType.AUDIO;
        String mediaType = isAudio ? "Audio" : "Video";
        String formatName = ext.replace(".", "").toUpperCase();

        List<String> keywords = new ArrayList<>();
        keywords.add(isAudio ? "audio" : "video");
        keywords.add(ext.replace(".", ""));

        // Look for companion transcript file
        String companionContent = "";
        Path companionPath = null;
        String baseName = getBaseName(metadata.fileName());

        if (metadata.path().getParent() != null) {
            for (String transcriptExt : TRANSCRIPT_EXTENSIONS) {
                Path candidate = metadata.path().getParent().resolve(baseName + transcriptExt);
                if (Files.exists(candidate) && Files.isReadable(candidate)) {
                    companionPath = candidate;
                    companionContent = readTranscript(candidate);
                    keywords.add("has-transcript");
                    break;
                }
            }
        }

        // ---- Smart fallback metadata extraction ----
        // Strategy 1: Try metadata-extractor first (pure Java, no external dependency)
        VideoMetadata videoMeta = tryMetadataExtractor(metadata.path());

        // Strategy 2: If metadata-extractor failed or file needs ffprobe, try ffprobe
        if (videoMeta == null && FfprobeDetector.isAvailable()) {
            FfprobeResult ffprobeResult = tryFfprobe(metadata.path());
            if (ffprobeResult != null) {
                videoMeta = new VideoMetadata(
                        ffprobeResult.duration(), ffprobeResult.width(), ffprobeResult.height());
                lastExtractionMethod = ExtractionMethod.FFPROBE;
            }
        } else if (videoMeta == null && FfprobeDetector.isFfprobeOnlyFormat(ext)) {
            // File needs ffprobe but it's not available
            lastExtractionMethod = ExtractionMethod.BASIC;
            keywords.add("ffprobe-needed");
        }

        if (videoMeta == null) {
            lastExtractionMethod = ExtractionMethod.BASIC;
        }

        // Build summary
        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append(mediaType).append(" file: ").append(metadata.fileName());
        summaryBuilder.append(" (").append(formatName).append(", ");
        summaryBuilder.append(FileUtils.formatSize(metadata.sizeBytes())).append(")");

        if (videoMeta != null) {
            if (videoMeta.duration > 0) {
                summaryBuilder.append(" [").append(formatDuration(videoMeta.duration)).append("]");
                keywords.add(categorizeDuration(videoMeta.duration));
            }
            if (videoMeta.width > 0 && videoMeta.height > 0 && !isAudio) {
                summaryBuilder.append(" ").append(videoMeta.width).append("x").append(videoMeta.height);
            }
        }

        if (companionPath != null) {
            summaryBuilder.append(" (has transcript)");
        }

        // Build structure
        StringBuilder structBuilder = new StringBuilder();
        structBuilder.append(mediaType).append(", ").append(FileUtils.formatSize(metadata.sizeBytes()));
        structBuilder.append(", ").append(formatName);
        if (videoMeta != null && videoMeta.duration > 0) {
            structBuilder.append(", ").append(formatDuration(videoMeta.duration));
        }
        if (videoMeta != null && videoMeta.width > 0 && !isAudio) {
            structBuilder.append(", ").append(videoMeta.width).append("x").append(videoMeta.height);
        }
        if (companionPath != null) {
            structBuilder.append(", transcript: ").append(companionPath.getFileName());
        }

        // Build metrics
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("format", formatName);
        metrics.put("mediaType", isAudio ? "audio" : "video");
        if (videoMeta != null) {
            if (videoMeta.duration > 0) metrics.put("durationSeconds", videoMeta.duration);
            if (videoMeta.width > 0 && !isAudio) {
                metrics.put("width", videoMeta.width);
                metrics.put("height", videoMeta.height);
            }
        }
        metrics.put("extractionMethod", lastExtractionMethod.name().toLowerCase());
        if (companionPath != null) {
            metrics.put("companionFile", companionPath.getFileName().toString());
        }

        // Content preview: transcript content makes video searchable
        String contentPreview = "";
        if (!companionContent.isEmpty()) {
            contentPreview = mediaType + " file: " + metadata.fileName() + "\n"
                    + "Transcript:\n" + companionContent;
        } else {
            contentPreview = mediaType + " file: " + metadata.fileName()
                    + " (" + formatName + ", " + FileUtils.formatSize(metadata.sizeBytes()) + ")";
        }

        // Headings
        List<String> headings = new ArrayList<>();
        headings.add(metadata.fileName());

        return AnalysisResult.builder()
                .summary(summaryBuilder.toString())
                .headings(headings)
                .keywords(keywords)
                .structure(structBuilder.toString())
                .metrics(metrics)
                .contentPreview(truncate(contentPreview, 50000))
                .build();
    }

    /**
     * Tries to extract video metadata using the pure Java metadata-extractor library.
     * Works well for MP4, MOV, AVI, M4V, 3GP containers.
     *
     * @return VideoMetadata if extraction succeeded, null otherwise
     */
    VideoMetadata tryMetadataExtractor(Path filePath) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(filePath.toFile());

            double duration = 0;
            int width = 0;
            int height = 0;

            // Iterate through all directories looking for video-relevant metadata
            for (Directory directory : metadata.getDirectories()) {
                for (Tag tag : directory.getTags()) {
                    String tagName = tag.getTagName();
                    String desc = tag.getDescription();

                    if (desc == null || desc.isEmpty()) continue;

                    // Duration (various directory types store this differently)
                    if (duration == 0 && tagName != null &&
                            (tagName.toLowerCase().contains("duration") ||
                             tagName.equals("Length"))) {
                        duration = parseDurationString(desc);
                    }

                    // Width
                    if (width == 0 && tagName != null &&
                            (tagName.contains("Width") || tagName.contains("width"))) {
                        width = parseIntFromDescription(desc);
                    }

                    // Height
                    if (height == 0 && tagName != null &&
                            (tagName.contains("Height") || tagName.contains("height"))) {
                        height = parseIntFromDescription(desc);
                    }
                }
            }

            if (duration > 0 || width > 0) {
                lastExtractionMethod = ExtractionMethod.METADATA_EXTRACTOR;
                return new VideoMetadata(duration, width, height);
            }

            return null;
        } catch (Exception e) {
            // metadata-extractor couldn't handle this format
            return null;
        }
    }

    /**
     * Tries to run ffprobe to get video/audio metadata.
     * Uses the ffprobe path from {@link FfprobeDetector}, which may be a bundled
     * binary extracted from the JAR or a system-installed one.
     *
     * @return FfprobeResult if extraction succeeded, null otherwise
     */
    FfprobeResult tryFfprobe(Path filePath) {
        if (!FfprobeDetector.isAvailable()) {
            return null;
        }

        String ffprobeCommand = FfprobeDetector.getFfprobePath();
        if (ffprobeCommand == null) {
            ffprobeCommand = "ffprobe"; // Fallback to PATH
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobeCommand, "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams",
                    filePath.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0 || output.isBlank()) {
                return null;
            }

            return parseFfprobeOutput(output);
        } catch (Exception e) {
            // ffprobe not available or failed
            return null;
        }
    }

    /**
     * Parses ffprobe JSON output to extract key metadata.
     * Simple parsing without a full JSON library (avoids dependency).
     */
    FfprobeResult parseFfprobeOutput(String json) {
        double duration = 0;
        int width = 0;
        int height = 0;

        // Extract duration from format section
        duration = extractJsonDouble(json, "duration");

        // Extract width/height from first video stream
        width = extractJsonInt(json, "width");
        height = extractJsonInt(json, "height");

        if (duration > 0 || width > 0) {
            return new FfprobeResult(duration, width, height);
        }
        return null;
    }

    /**
     * Reads the first 50KB of a companion transcript file.
     */
    private String readTranscript(Path path) {
        try {
            return FileUtils.readPreview(path, 50_000);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Gets the base name of a file (without extension).
     */
    static String getBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Formats a duration in seconds as human-readable string.
     */
    public static String formatDuration(double seconds) {
        if (seconds < 60) return String.format("%.0fs", seconds);
        long mins = (long) (seconds / 60);
        long secs = (long) (seconds % 60);
        if (mins < 60) return String.format("%dm %ds", mins, secs);
        long hours = mins / 60;
        mins = mins % 60;
        return String.format("%dh %dm", hours, mins);
    }

    /**
     * Categorizes a video by duration for keyword tagging.
     */
    static String categorizeDuration(double seconds) {
        if (seconds < 30) return "clip";
        if (seconds < 300) return "short-video";
        if (seconds < 1800) return "medium-video";
        return "long-video";
    }

    /**
     * Extracts a double value from JSON by key name (simple parsing).
     */
    static double extractJsonDouble(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;

        // Find the colon after the key
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return 0;

        // Find the value (may be quoted)
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }

        if (end > start) {
            try {
                return Double.parseDouble(json.substring(start, end));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Extracts an int value from JSON by key name (simple parsing).
     */
    static int extractJsonInt(String json, String key) {
        return (int) extractJsonDouble(json, key);
    }

    /**
     * Parses a duration string from metadata-extractor into seconds.
     * Handles formats like "00:05:23", "5:23", "323.5", "323 sec", etc.
     */
    static double parseDurationString(String desc) {
        if (desc == null || desc.isEmpty()) return 0;

        desc = desc.trim();

        // Try HH:MM:SS or MM:SS format
        if (desc.contains(":")) {
            String[] parts = desc.split(":");
            try {
                if (parts.length == 3) {
                    return Integer.parseInt(parts[0].trim()) * 3600
                            + Integer.parseInt(parts[1].trim()) * 60
                            + Double.parseDouble(parts[2].trim());
                } else if (parts.length == 2) {
                    return Integer.parseInt(parts[0].trim()) * 60
                            + Double.parseDouble(parts[1].trim());
                }
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        // Try pure numeric (seconds)
        String numericPart = desc.replaceAll("[^0-9.]", "");
        if (!numericPart.isEmpty()) {
            try {
                return Double.parseDouble(numericPart);
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        return 0;
    }

    /**
     * Extracts an integer from a metadata description string.
     * Handles formats like "1920", "1920 pixels", etc.
     */
    static int parseIntFromDescription(String desc) {
        if (desc == null || desc.isEmpty()) return 0;
        String numericPart = desc.replaceAll("[^0-9]", "");
        if (!numericPart.isEmpty()) {
            try {
                return Integer.parseInt(numericPart);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Result from ffprobe metadata extraction.
     */
    record FfprobeResult(double duration, int width, int height) {}

    /**
     * Unified video metadata result used internally regardless of extraction source.
     */
    record VideoMetadata(double duration, int width, int height) {}
}
