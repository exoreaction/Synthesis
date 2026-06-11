package io.exoreaction.synthesis.ai;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Shared image preparation for vision requests.
 *
 * <p>Images whose raw size would exceed the 5 MB base64 limit common to vision APIs
 * are downscaled to fit within {@value #MAX_DIMENSION}px and re-encoded as JPEG.
 */
final class ImagePayloads {

    /** Raw file threshold below the 5 MB base64 limit: 5,242,880 * 3/4 ≈ 3.75 MB. */
    static final long MAX_RAW_BYTES = 3_932_160;
    static final int MAX_DIMENSION = 2048;

    private static final Map<String, String> MEDIA_TYPES = Map.of(
            ".png", "image/png",
            ".gif", "image/gif",
            ".webp", "image/webp");

    private ImagePayloads() {}

    /** Image bytes ready for base64 encoding, with the media type to declare. */
    record Payload(byte[] bytes, String mediaType) {}

    static Payload read(Path imagePath) throws IOException {
        boolean oversized = Files.size(imagePath) > MAX_RAW_BYTES;
        String mediaType = oversized ? "image/jpeg" : mediaTypeFor(imagePath.getFileName().toString());
        return new Payload(oversized ? resize(imagePath) : Files.readAllBytes(imagePath), mediaType);
    }

    static String mediaTypeFor(String fileName) {
        String lower = fileName.toLowerCase();
        return MEDIA_TYPES.entrySet().stream()
                .filter(entry -> lower.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("image/jpeg");
    }

    private static byte[] resize(Path imagePath) throws IOException {
        BufferedImage original = ImageIO.read(imagePath.toFile());
        if (original == null) {
            // Can't decode (e.g., animated GIF) — send as-is and let the API reject if needed
            return Files.readAllBytes(imagePath);
        }

        int origW = original.getWidth();
        int origH = original.getHeight();
        double scale = Math.min((double) MAX_DIMENSION / origW, (double) MAX_DIMENSION / origH);
        if (scale >= 1.0) {
            return Files.readAllBytes(imagePath);
        }

        int newW = (int) (origW * scale);
        int newH = (int) (origH * scale);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(resized, "JPEG", baos);
        return baos.toByteArray();
    }
}
