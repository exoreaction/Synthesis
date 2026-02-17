package io.exoreaction.synthesis.enrichment;

import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates {@code .synthesis.md} companion files for binary assets.
 *
 * <p>Companion files make binary content (images, videos, PDFs, audio) fully
 * text-searchable by generating structured metadata in Markdown format alongside
 * each asset. The companion files are automatically picked up by standard scanning
 * and indexed into Lucene.
 *
 * <p>Three enrichment tiers:
 * <ul>
 *   <li><b>BASIC</b> -- Deterministic metadata only (works in air-gapped mode)</li>
 *   <li><b>LOCAL</b> -- Adds local tool output (Whisper transcripts, pdftoppm slides)</li>
 *   <li><b>AI</b> -- Adds Claude Vision descriptions and AI summaries</li>
 * </ul>
 *
 * <p>Design principles (from validated skills analysis):
 * <ul>
 *   <li>Idempotent: never overwrites existing companion files unless forced</li>
 *   <li>Incremental: later enrichment passes add sections, not replace</li>
 *   <li>Campaign-aware: groups related files for batch efficiency</li>
 * </ul>
 *
 * @see EnrichmentLevel
 */
public class CompanionFileGenerator {

    private final EnrichmentLevel level;
    private final boolean forceRegenerate;
    private final ClaudeClient aiClient;

    /**
     * Creates a generator with the specified enrichment level (no AI client).
     *
     * @param level           enrichment tier (BASIC, LOCAL, or AI)
     * @param forceRegenerate if true, overwrite existing companion files
     */
    public CompanionFileGenerator(EnrichmentLevel level, boolean forceRegenerate) {
        this(level, forceRegenerate, null);
    }

    /**
     * Creates a generator with the specified enrichment level and optional AI client.
     *
     * @param level           enrichment tier (BASIC, LOCAL, or AI)
     * @param forceRegenerate if true, overwrite existing companion files
     * @param aiClient        Claude client for AI enrichment (may be null)
     */
    public CompanionFileGenerator(EnrichmentLevel level, boolean forceRegenerate,
                                   ClaudeClient aiClient) {
        this.level = level;
        this.forceRegenerate = forceRegenerate;
        this.aiClient = aiClient;
    }

    /**
     * Generates a {@code .synthesis.md} companion file for the given media file.
     *
     * <p>Only generates for non-text file types (IMAGE, VIDEO, AUDIO, PDF).
     * Text files (CODE, MARKDOWN, YAML, JSON, CONFIG) are already fully indexed.
     *
     * @param metadata     the file metadata from scanning
     * @param analysis     the analysis result from the appropriate analyzer
     * @param relatedFiles list of related files discovered via relationships
     * @return path to the generated companion file, or empty if not applicable
     * @throws IOException if the companion file cannot be written
     */
    public Optional<Path> generate(FileMetadata metadata, AnalysisResult analysis,
                                    List<RelatedFile> relatedFiles) throws IOException {
        // Only generate for non-text files
        if (isTextFile(metadata.fileType())) {
            return Optional.empty();
        }

        // Check if companion file already exists (idempotent)
        Path companionPath = companionPathFor(metadata.path());
        if (Files.exists(companionPath) && !forceRegenerate) {
            return Optional.empty();
        }

        // Generate content from template based on file type
        String content = switch (metadata.fileType()) {
            case VIDEO, AUDIO -> generateMediaCompanion(metadata, analysis, relatedFiles);
            case IMAGE -> generateImageCompanion(metadata, analysis, relatedFiles);
            case PDF -> generatePdfCompanion(metadata, analysis, relatedFiles);
            default -> generateDefaultCompanion(metadata, analysis, relatedFiles);
        };

        // Ensure parent directory exists
        Files.createDirectories(companionPath.getParent());
        Files.writeString(companionPath, content);
        return Optional.of(companionPath);
    }

    /**
     * Returns the companion file path for a given original file.
     * Convention: {@code filename.ext.synthesis.md}
     *
     * @param originalFile path to the original binary file
     * @return path where the companion file should be created
     */
    public static Path companionPathFor(Path originalFile) {
        String baseName = originalFile.getFileName().toString();
        return originalFile.getParent().resolve(baseName + ".synthesis.md");
    }

    /**
     * Checks whether a companion file exists for the given file.
     *
     * @param originalFile path to the original file
     * @return true if a companion file already exists
     */
    public static boolean hasCompanion(Path originalFile) {
        return Files.exists(companionPathFor(originalFile));
    }

    /**
     * Checks whether a given file IS a companion file (ends with .synthesis.md).
     *
     * @param filePath path to check
     * @return true if this is a companion file
     */
    public static boolean isCompanionFile(Path filePath) {
        return filePath.getFileName().toString().endsWith(".synthesis.md");
    }

    /**
     * Returns the source file path for a companion file.
     * Reverses the {@link #companionPathFor(Path)} operation.
     *
     * @param companionPath path to the companion file
     * @return the original file path, or empty if not a companion file
     */
    public static Optional<Path> sourcePathFor(Path companionPath) {
        String name = companionPath.getFileName().toString();
        if (!name.endsWith(".synthesis.md")) {
            return Optional.empty();
        }
        String sourceName = name.substring(0, name.length() - ".synthesis.md".length());
        if (sourceName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(companionPath.getParent().resolve(sourceName));
    }

    // --- Template methods ---

    /**
     * Generates companion content for video and audio files.
     * Includes: duration, resolution, codec, companion transcript if present.
     */
    String generateMediaCompanion(FileMetadata metadata, AnalysisResult analysis,
                                   List<RelatedFile> relatedFiles) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> metrics = analysis.metrics();

        sb.append("# ").append(metadata.fileName()).append("\n\n");

        // YAML-like frontmatter for machine readability
        sb.append("```yaml\n");
        sb.append("companion_for: ").append(metadata.fileName()).append("\n");
        sb.append("type: ").append(metadata.fileType()).append("\n");
        sb.append("enrichment_level: ").append(level.name()).append("\n");
        sb.append("```\n\n");

        // Metadata table
        sb.append("**Type:** ").append(metadata.fileType()).append(" (")
                .append(metadata.extension().toUpperCase()).append(")\n");

        if (metrics.containsKey("duration")) {
            sb.append("**Duration:** ").append(metrics.get("duration")).append("\n");
        }
        if (metrics.containsKey("resolution")) {
            sb.append("**Resolution:** ").append(metrics.get("resolution")).append("\n");
        }
        if (metrics.containsKey("codec")) {
            sb.append("**Codec:** ").append(metrics.get("codec")).append("\n");
        }
        sb.append("**Size:** ").append(FileUtils.formatSize(metadata.sizeBytes())).append("\n");
        sb.append("**Modified:** ").append(formatTimestamp(metadata.lastModified())).append("\n");
        sb.append("\n");

        // AI summary if available
        if (level.hasAI() && aiClient != null && !analysis.summary().isEmpty()) {
            sb.append("## AI Summary\n");
            sb.append(analysis.summary()).append("\n\n");
        }

        // Keywords for search
        sb.append("## Keywords\n");
        sb.append(metadata.fileType().name().toLowerCase()).append(", ")
                .append(metadata.extension().replace(".", "")).append(", ");
        if (metrics.containsKey("durationCategory")) {
            sb.append(metrics.get("durationCategory")).append(", ");
        }
        if (metrics.containsKey("resolution")) {
            sb.append(metrics.get("resolution"));
        }
        sb.append("\n\n");

        // Companion transcript if present
        if (metrics.containsKey("transcriptPath")) {
            sb.append("## Transcript\n");
            sb.append("Transcript available: [")
                    .append(metrics.get("transcriptPath")).append("](")
                    .append(metrics.get("transcriptPath")).append(")\n\n");
        }

        appendRelatedFiles(sb, relatedFiles);
        appendEnrichmentHistory(sb, level.name());

        return sb.toString();
    }

    /**
     * Generates companion content for image files.
     * Includes: dimensions, format classification, EXIF data, AI description.
     */
    String generateImageCompanion(FileMetadata metadata, AnalysisResult analysis,
                                   List<RelatedFile> relatedFiles) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> metrics = analysis.metrics();

        sb.append("# ").append(metadata.fileName()).append("\n\n");

        // YAML frontmatter
        sb.append("```yaml\n");
        sb.append("companion_for: ").append(metadata.fileName()).append("\n");
        sb.append("type: IMAGE\n");
        sb.append("enrichment_level: ").append(level.name()).append("\n");
        sb.append("```\n\n");

        sb.append("**Type:** Image (").append(metadata.extension().toUpperCase()).append(")\n");
        if (metrics.containsKey("dimensions")) {
            sb.append("**Dimensions:** ").append(metrics.get("dimensions")).append("\n");
        }
        if (metrics.containsKey("imageType")) {
            sb.append("**Classification:** ").append(metrics.get("imageType")).append("\n");
        }
        sb.append("**Size:** ").append(FileUtils.formatSize(metadata.sizeBytes())).append("\n");
        sb.append("**Modified:** ").append(formatTimestamp(metadata.lastModified())).append("\n");
        sb.append("\n");

        // EXIF/IPTC metadata if present
        if (metrics.containsKey("camera") || metrics.containsKey("gps") ||
                metrics.containsKey("iptcKeywords")) {
            sb.append("## Metadata\n");
            if (metrics.containsKey("camera")) {
                sb.append("- **Camera:** ").append(metrics.get("camera")).append("\n");
            }
            if (metrics.containsKey("gps")) {
                sb.append("- **Location:** ").append(metrics.get("gps")).append("\n");
            }
            if (metrics.containsKey("iptcKeywords")) {
                sb.append("- **IPTC Keywords:** ").append(metrics.get("iptcKeywords")).append("\n");
            }
            sb.append("\n");
        }

        // AI Vision description
        if (level.hasAI() && aiClient != null) {
            String visionDescription = generateVisionDescription(metadata);
            if (visionDescription != null && !visionDescription.isEmpty()) {
                sb.append("## AI Description\n");
                sb.append(visionDescription).append("\n\n");
            }
        }

        // Keywords for search
        sb.append("## Keywords\n");
        sb.append("image, ").append(metadata.extension().replace(".", ""));
        if (metrics.containsKey("imageType")) {
            sb.append(", ").append(metrics.get("imageType"));
        }
        sb.append("\n\n");

        appendRelatedFiles(sb, relatedFiles);
        appendEnrichmentHistory(sb, level.name());

        return sb.toString();
    }

    /**
     * Generates companion content for PDF files.
     * Includes: page count, extracted text preview, media type detection.
     */
    String generatePdfCompanion(FileMetadata metadata, AnalysisResult analysis,
                                 List<RelatedFile> relatedFiles) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> metrics = analysis.metrics();

        sb.append("# ").append(metadata.fileName()).append("\n\n");

        // YAML frontmatter
        sb.append("```yaml\n");
        sb.append("companion_for: ").append(metadata.fileName()).append("\n");
        sb.append("type: PDF\n");
        sb.append("enrichment_level: ").append(level.name()).append("\n");
        sb.append("```\n\n");

        sb.append("**Type:** PDF\n");
        if (metrics.containsKey("pages")) {
            sb.append("**Pages:** ").append(metrics.get("pages")).append("\n");
        }
        if (metrics.containsKey("mediaType")) {
            sb.append("**Media Type:** ").append(metrics.get("mediaType")).append("\n");
        }
        if (metrics.containsKey("creator")) {
            sb.append("**Creator:** ").append(metrics.get("creator")).append("\n");
        }
        sb.append("**Size:** ").append(FileUtils.formatSize(metadata.sizeBytes())).append("\n");
        sb.append("**Modified:** ").append(formatTimestamp(metadata.lastModified())).append("\n");
        sb.append("\n");

        // Text content preview (from PdfAnalyzer extraction)
        if (metrics.containsKey("textPreview")) {
            sb.append("## Content Preview\n");
            String preview = String.valueOf(metrics.get("textPreview"));
            if (preview.length() > 2000) {
                preview = preview.substring(0, 2000) + "\n[... truncated]";
            }
            sb.append(preview).append("\n\n");
        }

        // AI summary if available and text content was extractable
        if (level.hasAI() && aiClient != null && !analysis.summary().isEmpty()
                && metrics.containsKey("textPreview")) {
            sb.append("## AI Summary\n");
            sb.append(analysis.summary()).append("\n\n");
        }

        // AI description from filename when no extractable text (e.g., visual/image-based PDFs).
        // The PdfAnalyzer produces a thin "PDF presentation (N pages)" summary for visual PDFs;
        // we replace it with a richer Claude text-based description from the filename.
        if (level.hasAI() && aiClient != null && !metrics.containsKey("textPreview")) {
            String aiDesc = generateDescriptionFromFilename(metadata, metrics);
            if (aiDesc != null && !aiDesc.isBlank()) {
                sb.append("## AI Description\n");
                sb.append(aiDesc).append("\n\n");
            }
        }

        // Headings if extracted
        if (analysis.headings() != null && !analysis.headings().isEmpty()) {
            sb.append("## Headings\n");
            for (String heading : analysis.headings()) {
                sb.append("- ").append(heading).append("\n");
            }
            sb.append("\n");
        }

        // Keywords
        sb.append("## Keywords\n");
        sb.append("pdf");
        if (metrics.containsKey("mediaType")) {
            sb.append(", ").append(metrics.get("mediaType"));
        }
        sb.append("\n\n");

        appendRelatedFiles(sb, relatedFiles);
        appendEnrichmentHistory(sb, level.name());

        return sb.toString();
    }

    /**
     * Generates companion content for unrecognized binary file types.
     */
    String generateDefaultCompanion(FileMetadata metadata, AnalysisResult analysis,
                                     List<RelatedFile> relatedFiles) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(metadata.fileName()).append("\n\n");

        sb.append("```yaml\n");
        sb.append("companion_for: ").append(metadata.fileName()).append("\n");
        sb.append("type: ").append(metadata.fileType()).append("\n");
        sb.append("enrichment_level: ").append(level.name()).append("\n");
        sb.append("```\n\n");

        sb.append("**Type:** ").append(metadata.fileType()).append("\n");
        sb.append("**Size:** ").append(FileUtils.formatSize(metadata.sizeBytes())).append("\n");
        sb.append("**Modified:** ").append(formatTimestamp(metadata.lastModified())).append("\n\n");

        sb.append("## Keywords\n");
        sb.append(metadata.extension().replace(".", "")).append("\n\n");

        appendRelatedFiles(sb, relatedFiles);
        appendEnrichmentHistory(sb, level.name());

        return sb.toString();
    }

    // --- AI enrichment ---

    /**
     * Uses Claude Vision to generate a description of an image file.
     *
     * @param metadata the image file metadata
     * @return AI-generated description, or null if vision analysis fails
     */
    private String generateVisionDescription(FileMetadata metadata) {
        if (aiClient == null) return null;

        String ext = metadata.extension().toLowerCase();
        if (!ClaudeClient.isVisionSupported(ext)) return null;

        try {
            return aiClient.generateFromImage(
                    metadata.path(),
                    "Describe this image concisely for a search index. Include what the image shows, "
                    + "what type of image it is (screenshot, diagram, photo, chart), key text visible, "
                    + "and technical details if relevant. Respond with 2-4 sentences followed by "
                    + "Keywords: keyword1, keyword2, ...",
                    512);
        } catch (Exception e) {
            // Vision analysis is best-effort; don't fail enrichment
            return null;
        }
    }

    /**
     * Generates a description for a PDF using its filename when no text content is extractable
     * (e.g., visual/image-based PDFs from NotebookLM, slide exports, presentations).
     *
     * @param metadata the PDF file metadata
     * @param metrics  analysis metrics (may include pages, mediaType)
     * @return AI-generated description with Keywords line, or null on failure
     */
    private String generateDescriptionFromFilename(FileMetadata metadata, Map<String, Object> metrics) {
        if (aiClient == null) return null;

        String name = metadata.fileName();
        // Strip extension and convert separators to readable form
        String readable = name.replaceAll("\\.[^.]+$", "")
                .replace("_", " ").replace("-", " ");

        String pages = metrics.containsKey("pages")
                ? String.valueOf(metrics.get("pages")) : "unknown";
        String size = io.exoreaction.synthesis.util.FileUtils.formatSize(metadata.sizeBytes());

        String prompt = """
                This is a PDF file named "%s" (%s pages, %s).
                The readable title is: "%s"
                It is a visual presentation PDF (no extractable text content).
                Based only on the filename, generate a concise description for a search index.
                Describe what topics this document likely covers and what audience it's for.
                Respond with 2-4 sentences followed by a line in the exact format:
                Keywords: keyword1, keyword2, keyword3, keyword4, keyword5
                """.formatted(name, pages, size, readable);

        try {
            return aiClient.generate(prompt, 256);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Helper methods ---

    private void appendRelatedFiles(StringBuilder sb, List<RelatedFile> relatedFiles) {
        if (relatedFiles != null && !relatedFiles.isEmpty()) {
            sb.append("## Related Files\n");
            for (RelatedFile related : relatedFiles) {
                sb.append("- [").append(related.fileName()).append("](")
                        .append(related.relativePath()).append(")");
                if (related.relationship() != null && !related.relationship().isEmpty()) {
                    sb.append(" -- ").append(related.relationship());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    private void appendEnrichmentHistory(StringBuilder sb, String tier) {
        sb.append("---\n");
        sb.append("*Generated by Synthesis (enrichment: ").append(tier).append(") on ")
                .append(formatTimestamp(Instant.now())).append("*\n");
    }

    private boolean isTextFile(FileUtils.FileType fileType) {
        return fileType == FileUtils.FileType.CODE
                || fileType == FileUtils.FileType.MARKDOWN
                || fileType == FileUtils.FileType.YAML
                || fileType == FileUtils.FileType.JSON
                || fileType == FileUtils.FileType.CONFIG
                || fileType == FileUtils.FileType.DOCUMENT;
    }

    private String formatTimestamp(Instant instant) {
        if (instant == null) return "unknown";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    /**
     * Returns the enrichment level of this generator.
     */
    public EnrichmentLevel getLevel() {
        return level;
    }

    /**
     * A file related to the target file.
     *
     * @param fileName     the file name
     * @param relativePath relative path from workspace root
     * @param relationship description of the relationship (e.g., "transcript", "slides")
     */
    public record RelatedFile(String fileName, String relativePath, String relationship) {}
}
