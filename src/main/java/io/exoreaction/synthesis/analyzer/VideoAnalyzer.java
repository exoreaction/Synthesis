package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Analyzes video files to extract metadata and companion transcripts.
 *
 * <p>Extracts the following from video files:
 * <ul>
 *   <li>Basic file metadata (format, size)</li>
 *   <li>Duration and resolution via ffprobe (when available)</li>
 *   <li>Companion transcript content (makes videos searchable)</li>
 * </ul>
 *
 * <p>Companion transcripts are detected by looking for files with the same
 * base name but different extension (e.g., video.mp4 -> video.txt, video.srt,
 * video.vtt, video.md).
 */
public class VideoAnalyzer implements FileAnalyzer {

    /** Extensions that may contain video transcripts or metadata. */
    private static final Set<String> TRANSCRIPT_EXTENSIONS = Set.of(
            ".txt", ".srt", ".vtt", ".md", ".json", ".yaml", ".yml"
    );

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

        // Try ffprobe for duration and resolution
        FfprobeResult ffprobe = tryFfprobe(metadata.path());

        // Build summary
        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append(mediaType).append(" file: ").append(metadata.fileName());
        summaryBuilder.append(" (").append(formatName).append(", ");
        summaryBuilder.append(FileUtils.formatSize(metadata.sizeBytes())).append(")");

        if (ffprobe != null) {
            if (ffprobe.duration > 0) {
                summaryBuilder.append(" [").append(formatDuration(ffprobe.duration)).append("]");
                keywords.add(categorizeDuration(ffprobe.duration));
            }
            if (ffprobe.width > 0 && ffprobe.height > 0 && !isAudio) {
                summaryBuilder.append(" ").append(ffprobe.width).append("x").append(ffprobe.height);
            }
        }

        if (companionPath != null) {
            summaryBuilder.append(" (has transcript)");
        }

        // Build structure
        StringBuilder structBuilder = new StringBuilder();
        structBuilder.append(mediaType).append(", ").append(FileUtils.formatSize(metadata.sizeBytes()));
        structBuilder.append(", ").append(formatName);
        if (ffprobe != null && ffprobe.duration > 0) {
            structBuilder.append(", ").append(formatDuration(ffprobe.duration));
        }
        if (ffprobe != null && ffprobe.width > 0 && !isAudio) {
            structBuilder.append(", ").append(ffprobe.width).append("x").append(ffprobe.height);
        }
        if (companionPath != null) {
            structBuilder.append(", transcript: ").append(companionPath.getFileName());
        }

        // Build metrics
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("format", formatName);
        metrics.put("mediaType", isAudio ? "audio" : "video");
        if (ffprobe != null) {
            if (ffprobe.duration > 0) metrics.put("durationSeconds", ffprobe.duration);
            if (ffprobe.width > 0 && !isAudio) {
                metrics.put("width", ffprobe.width);
                metrics.put("height", ffprobe.height);
            }
        }
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
     * Tries to run ffprobe to get video/audio metadata.
     * Returns null if ffprobe is not available.
     */
    FfprobeResult tryFfprobe(Path filePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
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
    static String formatDuration(double seconds) {
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

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Result from ffprobe metadata extraction.
     */
    record FfprobeResult(double duration, int width, int height) {}
}
