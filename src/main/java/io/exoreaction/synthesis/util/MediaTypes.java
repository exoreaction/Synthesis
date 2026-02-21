package io.exoreaction.synthesis.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical media extension constants shared across routing, health checks,
 * directory signal extraction, and maintenance operations.
 *
 * <p>Prior to this class, four separate locations defined inline sets of media
 * extensions ({@code MaintainCommand}, {@code E010Check},
 * {@code DirectorySignalExtractor}, {@code MaintainOrchestrator}). This class
 * provides a single source of truth, including modern formats such as
 * {@code webp}, {@code heic}, {@code m4a}, {@code wma}, and {@code tiff}.
 *
 * @since v1.13.0 (P1-03)
 */
public final class MediaTypes {

    private MediaTypes() {
        // Utility class — no instantiation
    }

    // ---- Video ----

    /**
     * Video file extensions.
     */
    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "avi", "mkv", "webm"
    );

    // ---- Audio ----

    /**
     * Audio file extensions, including modern formats ({@code m4a}, {@code wma}).
     */
    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "flac", "ogg", "aac", "m4a", "wma"
    );

    // ---- Image ----

    /**
     * Image file extensions, including modern formats ({@code webp}, {@code heic},
     * {@code tiff}).
     */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "svg", "bmp", "webp", "heic", "tiff"
    );

    // ---- Combined ----

    /**
     * Union of all media extensions: video + audio + image.
     *
     * <p>This is the canonical set that replaces the inline {@code MEDIA_EXTENSIONS}
     * constants previously scattered across multiple classes.
     */
    public static final Set<String> MEDIA_EXTENSIONS;

    static {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(VIDEO_EXTENSIONS);
        all.addAll(AUDIO_EXTENSIONS);
        all.addAll(IMAGE_EXTENSIONS);
        MEDIA_EXTENSIONS = Collections.unmodifiableSet(all);
    }

    // =========================================================================
    // Extension-to-reject-type mapping (P1-04)
    // =========================================================================

    /**
     * Maps file extensions to broad type categories for {@code rejectsTypes}
     * hard-rejection in routing and health checks.
     *
     * <p>This was previously duplicated between {@code DirectoryScorer} and
     * {@code E010Check}. Both classes now reference this shared map as
     * the single source of truth.
     *
     * @since v1.13.0 (P1-04)
     */
    public static final Map<String, Set<String>> EXTENSION_REJECT_TYPE_MAP = Map.ofEntries(
            Map.entry("mp4", Set.of("video", "media")),
            Map.entry("mov", Set.of("video", "media")),
            Map.entry("avi", Set.of("video", "media")),
            Map.entry("mkv", Set.of("video", "media")),
            Map.entry("webm", Set.of("video", "media")),
            Map.entry("mp3", Set.of("audio", "media")),
            Map.entry("wav", Set.of("audio", "media")),
            Map.entry("flac", Set.of("audio", "media")),
            Map.entry("ogg", Set.of("audio", "media")),
            Map.entry("aac", Set.of("audio", "media")),
            Map.entry("jpg", Set.of("image", "media")),
            Map.entry("jpeg", Set.of("image", "media")),
            Map.entry("png", Set.of("image", "media")),
            Map.entry("gif", Set.of("image", "media")),
            Map.entry("svg", Set.of("image", "media")),
            Map.entry("bmp", Set.of("image", "media")),
            Map.entry("pdf", Set.of("document")),
            Map.entry("docx", Set.of("document")),
            Map.entry("doc", Set.of("document")),
            Map.entry("md", Set.of("document")),
            Map.entry("txt", Set.of("document"))
    );
}
