package io.exoreaction.synthesis.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * File system utility methods used throughout Synthesis.
 * Handles extension detection, content hashing, MIME type guessing,
 * and language classification.
 */
public final class FileUtils {

    private FileUtils() {}

    /** File extensions considered as code files. */
    private static final Map<String, String> CODE_EXTENSIONS = Map.ofEntries(
            Map.entry(".java", "Java"),
            Map.entry(".py", "Python"),
            Map.entry(".js", "JavaScript"),
            Map.entry(".ts", "TypeScript"),
            Map.entry(".tsx", "TypeScript"),
            Map.entry(".jsx", "JavaScript"),
            Map.entry(".go", "Go"),
            Map.entry(".rs", "Rust"),
            Map.entry(".rb", "Ruby"),
            Map.entry(".kt", "Kotlin"),
            Map.entry(".scala", "Scala"),
            Map.entry(".c", "C"),
            Map.entry(".cpp", "C++"),
            Map.entry(".h", "C"),
            Map.entry(".hpp", "C++"),
            Map.entry(".cs", "C#"),
            Map.entry(".swift", "Swift"),
            Map.entry(".php", "PHP"),
            Map.entry(".sh", "Shell"),
            Map.entry(".bash", "Shell"),
            Map.entry(".zsh", "Shell"),
            Map.entry(".pl", "Perl"),
            Map.entry(".r", "R"),
            Map.entry(".lua", "Lua"),
            Map.entry(".sql", "SQL"),
            Map.entry(".groovy", "Groovy"),
            Map.entry(".clj", "Clojure"),
            Map.entry(".ex", "Elixir"),
            Map.entry(".erl", "Erlang"),
            Map.entry(".hs", "Haskell"),
            Map.entry(".dart", "Dart")
    );

    /** File extensions considered as markup/documentation. */
    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of(
            ".md", ".markdown", ".mdown", ".mkd"
    );

    /** File extensions for YAML files. */
    private static final Set<String> YAML_EXTENSIONS = Set.of(
            ".yaml", ".yml"
    );

    /** File extensions for JSON files. */
    private static final Set<String> JSON_EXTENSIONS = Set.of(
            ".json", ".jsonc", ".json5"
    );

    /** File extensions for config/data files. */
    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            ".toml", ".ini", ".cfg", ".conf", ".properties",
            ".env", ".xml", ".plist"
    );

    /** File extensions for image files. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".svg", ".ico", ".webp",
            ".tiff", ".tif", ".heic", ".heif", ".raw", ".cr2", ".nef", ".dng"
    );

    /** File extensions for video files. */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".avi", ".mov", ".mkv", ".webm", ".flv", ".wmv",
            ".m4v", ".mpg", ".mpeg", ".3gp", ".ogv"
    );

    /** File extensions for audio files. */
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".flac", ".ogg", ".aac", ".wma", ".m4a",
            ".opus", ".aiff", ".ape", ".alac"
    );

    /** File extensions for document files (non-PDF). */
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".odt", ".ods", ".odp", ".rtf"
    );

    /** File extensions for binary/archive files that should not be indexed for content. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar",
            ".jar", ".war", ".ear", ".class",
            ".exe", ".dll", ".so", ".dylib",
            ".bin", ".dat", ".db", ".sqlite"
    );

    /**
     * Classifies a file into a broad category based on its extension.
     */
    public static FileType classifyFile(Path path) {
        String ext = getExtension(path).toLowerCase();

        if (MARKDOWN_EXTENSIONS.contains(ext)) return FileType.MARKDOWN;
        if (CODE_EXTENSIONS.containsKey(ext)) return FileType.CODE;
        if (YAML_EXTENSIONS.contains(ext)) return FileType.YAML;
        if (JSON_EXTENSIONS.contains(ext)) return FileType.JSON;
        if (CONFIG_EXTENSIONS.contains(ext)) return FileType.CONFIG;
        if (ext.equals(".pdf")) return FileType.PDF;
        if (IMAGE_EXTENSIONS.contains(ext)) return FileType.IMAGE;
        if (VIDEO_EXTENSIONS.contains(ext)) return FileType.VIDEO;
        if (AUDIO_EXTENSIONS.contains(ext)) return FileType.AUDIO;
        if (DOCUMENT_EXTENSIONS.contains(ext)) return FileType.DOCUMENT;
        if (BINARY_EXTENSIONS.contains(ext)) return FileType.BINARY;

        return FileType.OTHER;
    }

    /**
     * Detects the programming language from a file extension.
     *
     * @return the language name, or null if not a recognized code file
     */
    public static String detectLanguage(Path path) {
        String ext = getExtension(path).toLowerCase();
        return CODE_EXTENSIONS.get(ext);
    }

    /**
     * Returns the file extension including the dot, or empty string if none.
     */
    public static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    /**
     * Computes the MD5 hash of a file's contents.
     * Used for duplicate detection and change tracking.
     */
    public static String md5Hash(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * Reads the first N bytes of a file as a String (for content preview).
     * Returns empty string for binary files.
     */
    public static String readPreview(Path path, int maxBytes) throws IOException {
        if (isBinaryFile(path)) {
            return "";
        }
        byte[] bytes = new byte[maxBytes];
        int read;
        try (InputStream is = Files.newInputStream(path)) {
            read = is.read(bytes);
        }
        if (read <= 0) return "";
        return new String(bytes, 0, read);
    }

    /** Text-based file extensions that happen to be in media categories. */
    private static final Set<String> TEXT_BASED_MEDIA = Set.of(".svg");

    /**
     * Checks if a file is likely binary by examining first bytes.
     */
    public static boolean isBinaryFile(Path path) {
        String ext = getExtension(path).toLowerCase();
        // SVG files are text-based XML images -- never treat as binary
        if (TEXT_BASED_MEDIA.contains(ext)) return false;
        if (BINARY_EXTENSIONS.contains(ext)) return true;
        if (IMAGE_EXTENSIONS.contains(ext)) return true;
        if (VIDEO_EXTENSIONS.contains(ext)) return true;
        if (AUDIO_EXTENSIONS.contains(ext)) return true;
        if (DOCUMENT_EXTENSIONS.contains(ext)) return true;

        // Check first 512 bytes for null bytes
        try (InputStream is = Files.newInputStream(path)) {
            byte[] header = new byte[512];
            int read = is.read(header);
            if (read <= 0) return false;
            for (int i = 0; i < read; i++) {
                if (header[i] == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true; // Assume binary if unreadable
        }
    }

    /**
     * Returns true if the file type represents a media file (image, video, or audio).
     */
    public static boolean isMediaFile(Path path) {
        FileType type = classifyFile(path);
        return type == FileType.IMAGE || type == FileType.VIDEO || type == FileType.AUDIO;
    }

    /**
     * Returns the image extension set for use by analyzers.
     */
    public static Set<String> getImageExtensions() {
        return IMAGE_EXTENSIONS;
    }

    /**
     * Returns the video extension set for use by analyzers.
     */
    public static Set<String> getVideoExtensions() {
        return VIDEO_EXTENSIONS;
    }

    /**
     * Returns the audio extension set for use by analyzers.
     */
    public static Set<String> getAudioExtensions() {
        return AUDIO_EXTENSIONS;
    }

    /**
     * Formats a byte count as a human-readable size string.
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * File type classification.
     */
    public enum FileType {
        MARKDOWN,
        CODE,
        YAML,
        JSON,
        CONFIG,
        PDF,
        IMAGE,
        VIDEO,
        AUDIO,
        DOCUMENT,
        BINARY,
        OTHER;

        /**
         * Returns true if this type represents a media file (image, video, or audio).
         */
        public boolean isMedia() {
            return this == IMAGE || this == VIDEO || this == AUDIO;
        }

        /**
         * Returns true if this type can potentially be analyzed for searchable content.
         * Images can be described by vision AI, videos may have companion transcripts.
         */
        public boolean isAnalyzable() {
            return this != BINARY && this != OTHER;
        }
    }
}
