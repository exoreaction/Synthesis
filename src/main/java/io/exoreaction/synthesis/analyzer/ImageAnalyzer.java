package io.exoreaction.synthesis.analyzer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.util.*;

/**
 * Analyzes image files to extract EXIF metadata, dimensions, and keywords.
 *
 * <p>Extracts the following from images:
 * <ul>
 *   <li>Dimensions (width x height)</li>
 *   <li>Camera info (make, model, settings)</li>
 *   <li>GPS coordinates (if present)</li>
 *   <li>IPTC keywords and descriptions</li>
 *   <li>Creation date</li>
 *   <li>File format details</li>
 * </ul>
 *
 * <p>Makes images searchable by their metadata. When combined with
 * Claude Vision (Phase 5), images also get AI-generated descriptions.
 */
public class ImageAnalyzer implements FileAnalyzer {

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.IMAGE;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String ext = metadata.extension().toLowerCase();

        // SVG files are text-based XML -- handle separately
        if (".svg".equals(ext)) {
            return analyzeSvg(metadata);
        }

        try {
            Metadata imageMetadata = ImageMetadataReader.readMetadata(metadata.path().toFile());

            int width = 0;
            int height = 0;
            String cameraMake = "";
            String cameraModel = "";
            String dateTime = "";
            String description = "";
            boolean hasGps = false;
            List<String> keywords = new ArrayList<>();
            keywords.add("image");
            keywords.add(ext.replace(".", ""));

            // Extract JPEG dimensions
            JpegDirectory jpegDir = imageMetadata.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpegDir != null) {
                if (jpegDir.containsTag(JpegDirectory.TAG_IMAGE_WIDTH)) {
                    width = jpegDir.getInt(JpegDirectory.TAG_IMAGE_WIDTH);
                }
                if (jpegDir.containsTag(JpegDirectory.TAG_IMAGE_HEIGHT)) {
                    height = jpegDir.getInt(JpegDirectory.TAG_IMAGE_HEIGHT);
                }
            }

            // Extract PNG dimensions
            PngDirectory pngDir = imageMetadata.getFirstDirectoryOfType(PngDirectory.class);
            if (pngDir != null) {
                if (pngDir.containsTag(PngDirectory.TAG_IMAGE_WIDTH)) {
                    width = pngDir.getInt(PngDirectory.TAG_IMAGE_WIDTH);
                }
                if (pngDir.containsTag(PngDirectory.TAG_IMAGE_HEIGHT)) {
                    height = pngDir.getInt(PngDirectory.TAG_IMAGE_HEIGHT);
                }
            }

            // Extract EXIF IFD0 (camera make/model)
            ExifIFD0Directory exifDir = imageMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null) {
                cameraMake = safeString(exifDir.getString(ExifIFD0Directory.TAG_MAKE));
                cameraModel = safeString(exifDir.getString(ExifIFD0Directory.TAG_MODEL));
                dateTime = safeString(exifDir.getString(ExifIFD0Directory.TAG_DATETIME));

                // Get dimensions from EXIF if not found yet
                if (width == 0 && exifDir.containsTag(ExifIFD0Directory.TAG_IMAGE_WIDTH)) {
                    width = exifDir.getInt(ExifIFD0Directory.TAG_IMAGE_WIDTH);
                }
                if (height == 0 && exifDir.containsTag(ExifIFD0Directory.TAG_IMAGE_HEIGHT)) {
                    height = exifDir.getInt(ExifIFD0Directory.TAG_IMAGE_HEIGHT);
                }
            }

            // Extract EXIF SubIFD (detailed settings)
            ExifSubIFDDirectory subIfd = imageMetadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                if (dateTime.isEmpty()) {
                    dateTime = safeString(subIfd.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
                }
                // Get dimensions from SubIFD if still not found
                if (width == 0 && subIfd.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)) {
                    width = subIfd.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
                }
                if (height == 0 && subIfd.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)) {
                    height = subIfd.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
                }
            }

            // Extract GPS data
            GpsDirectory gpsDir = imageMetadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDir != null && gpsDir.getGeoLocation() != null) {
                hasGps = true;
                keywords.add("geotagged");
            }

            // Extract IPTC keywords and description
            IptcDirectory iptcDir = imageMetadata.getFirstDirectoryOfType(IptcDirectory.class);
            if (iptcDir != null) {
                String iptcKeywords = safeString(iptcDir.getString(IptcDirectory.TAG_KEYWORDS));
                if (!iptcKeywords.isEmpty()) {
                    Arrays.stream(iptcKeywords.split("[;,]"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(String::toLowerCase)
                            .forEach(keywords::add);
                }
                description = safeString(iptcDir.getString(IptcDirectory.TAG_CAPTION));
            }

            // Add camera info as keywords
            if (!cameraMake.isEmpty()) keywords.add(cameraMake.toLowerCase());
            if (!cameraModel.isEmpty()) keywords.add(cameraModel.toLowerCase());

            // Classify image type from dimensions
            String imageClass = classifyImage(width, height, metadata.sizeBytes());
            if (!imageClass.isEmpty()) keywords.add(imageClass);

            // Build summary
            StringBuilder summaryBuilder = new StringBuilder();
            summaryBuilder.append("Image");
            if (width > 0 && height > 0) {
                summaryBuilder.append(" ").append(width).append("x").append(height);
            }
            summaryBuilder.append(" (").append(ext.replace(".", "").toUpperCase()).append(")");
            if (!cameraMake.isEmpty() || !cameraModel.isEmpty()) {
                summaryBuilder.append(" taken with ");
                if (!cameraMake.isEmpty()) summaryBuilder.append(cameraMake);
                if (!cameraModel.isEmpty()) summaryBuilder.append(" ").append(cameraModel);
            }
            if (!description.isEmpty()) {
                summaryBuilder.append(": ").append(truncate(description, 100));
            }
            String summary = summaryBuilder.toString();

            // Build structure
            StringBuilder structBuilder = new StringBuilder();
            structBuilder.append("Image, ").append(FileUtils.formatSize(metadata.sizeBytes()));
            if (width > 0 && height > 0) {
                structBuilder.append(", ").append(width).append("x").append(height);
            }
            if (!dateTime.isEmpty()) structBuilder.append(", date: ").append(dateTime);
            if (hasGps) structBuilder.append(", GPS: yes");

            // Build headings (use description and filename)
            List<String> headings = new ArrayList<>();
            if (!description.isEmpty()) headings.add(description);
            headings.add(metadata.fileName());

            // Build metrics
            Map<String, Object> metrics = new LinkedHashMap<>();
            if (width > 0) metrics.put("width", width);
            if (height > 0) metrics.put("height", height);
            metrics.put("format", ext.replace(".", "").toUpperCase());
            if (!cameraMake.isEmpty()) metrics.put("cameraMake", cameraMake);
            if (!cameraModel.isEmpty()) metrics.put("cameraModel", cameraModel);
            if (hasGps) metrics.put("hasGps", true);

            // Content preview: combine description + metadata for searchability
            StringBuilder contentPreview = new StringBuilder();
            if (!description.isEmpty()) {
                contentPreview.append(description).append("\n");
            }
            contentPreview.append("Image file: ").append(metadata.fileName()).append("\n");
            if (width > 0 && height > 0) {
                contentPreview.append("Dimensions: ").append(width).append("x").append(height).append("\n");
            }
            if (!cameraMake.isEmpty() || !cameraModel.isEmpty()) {
                contentPreview.append("Camera: ").append(cameraMake).append(" ").append(cameraModel).append("\n");
            }
            // Add all readable metadata tags for search
            for (Directory directory : imageMetadata.getDirectories()) {
                for (Tag tag : directory.getTags()) {
                    String tagDesc = tag.getDescription();
                    if (tagDesc != null && !tagDesc.isEmpty() && tagDesc.length() < 200) {
                        contentPreview.append(tag.getTagName()).append(": ").append(tagDesc).append("\n");
                    }
                }
            }

            return AnalysisResult.builder()
                    .summary(summary)
                    .headings(headings)
                    .keywords(keywords)
                    .structure(structBuilder.toString())
                    .metrics(metrics)
                    .contentPreview(truncate(contentPreview.toString(), 10000))
                    .build();

        } catch (Exception e) {
            // Image metadata extraction failed -- return minimal result
            return AnalysisResult.builder()
                    .summary("Image file: " + metadata.fileName() +
                            " (" + FileUtils.formatSize(metadata.sizeBytes()) + ")")
                    .keywords(List.of("image", ext.replace(".", "")))
                    .structure("Image, " + FileUtils.formatSize(metadata.sizeBytes()))
                    .metrics(Map.of("format", ext.replace(".", "").toUpperCase()))
                    .build();
        }
    }

    /**
     * Analyzes SVG files as text-based XML images.
     */
    private AnalysisResult analyzeSvg(FileMetadata metadata) throws IOException {
        String content = FileUtils.readPreview(metadata.path(), 10240);
        List<String> keywords = new ArrayList<>(List.of("image", "svg", "vector"));

        // Try to extract viewBox dimensions
        int width = 0, height = 0;
        if (content.contains("viewBox")) {
            String viewBox = extractAttribute(content, "viewBox");
            if (!viewBox.isEmpty()) {
                String[] parts = viewBox.trim().split("\\s+");
                if (parts.length == 4) {
                    try {
                        width = (int) Double.parseDouble(parts[2]);
                        height = (int) Double.parseDouble(parts[3]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (width == 0) {
            String w = extractAttribute(content, "width");
            String h = extractAttribute(content, "height");
            try {
                if (!w.isEmpty()) width = (int) Double.parseDouble(w.replaceAll("[^0-9.]", ""));
                if (!h.isEmpty()) height = (int) Double.parseDouble(h.replaceAll("[^0-9.]", ""));
            } catch (NumberFormatException ignored) {}
        }

        // Detect SVG contents
        if (content.contains("<text")) keywords.add("text");
        if (content.contains("<path")) keywords.add("path");
        if (content.contains("<circle") || content.contains("<rect") || content.contains("<polygon")) {
            keywords.add("shapes");
        }

        String summary = "SVG vector image";
        if (width > 0 && height > 0) {
            summary += " " + width + "x" + height;
        }
        summary += " (" + FileUtils.formatSize(metadata.sizeBytes()) + ")";

        Map<String, Object> metrics = new LinkedHashMap<>();
        if (width > 0) metrics.put("width", width);
        if (height > 0) metrics.put("height", height);
        metrics.put("format", "SVG");

        return AnalysisResult.builder()
                .summary(summary)
                .headings(List.of(metadata.fileName()))
                .keywords(keywords)
                .structure("SVG, " + FileUtils.formatSize(metadata.sizeBytes()))
                .metrics(metrics)
                .contentPreview(content)
                .build();
    }

    /**
     * Classifies an image based on dimensions and size.
     */
    static String classifyImage(int width, int height, long sizeBytes) {
        if (width == 0 || height == 0) return "";

        // Icon/favicon
        if (width <= 128 && height <= 128) return "icon";
        // Thumbnail
        if (width <= 256 && height <= 256) return "thumbnail";
        // Screenshot (typical screen resolutions)
        if ((width == 1920 && height == 1080) || (width == 2560 && height == 1440)
                || (width == 3840 && height == 2160) || (width == 1440 && height == 900)
                || (width == 1366 && height == 768) || (width == 2880 && height == 1800)) {
            return "screenshot";
        }
        // Photo (high-res, large file)
        if (width >= 2000 && height >= 1500 && sizeBytes > 500_000) return "photo";
        // Banner/wide (very wide aspect ratio)
        if (width > 0 && height > 0 && (double) width / height > 3.0) return "banner";
        // Square (likely social media or avatar)
        if (width == height) return "square";
        // Diagram/illustration (moderate size, not photo-sized)
        if (width >= 400 && height >= 300 && sizeBytes < 500_000) return "diagram";

        return "";
    }

    private static String safeString(String value) {
        return value != null ? value.trim() : "";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Extracts an XML/SVG attribute value from content.
     */
    static String extractAttribute(String content, String attrName) {
        int idx = content.indexOf(attrName + "=\"");
        if (idx < 0) {
            idx = content.indexOf(attrName + "='");
        }
        if (idx < 0) return "";

        int start = idx + attrName.length() + 2;
        char quote = content.charAt(start - 1);
        int end = content.indexOf(quote, start);
        if (end < 0) return "";

        return content.substring(start, end);
    }
}
