package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.util.FileUtils;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Immutable metadata for a single file discovered during scanning.
 * This is the fundamental data unit flowing through the Synthesis pipeline:
 * Scanner produces these, Analyzers enrich them, Indexer consumes them.
 *
 * @param path           absolute path to the file
 * @param relativePath   path relative to the workspace root
 * @param fileName       the file name (without directory)
 * @param extension      file extension including the dot (e.g., ".java"), or empty string
 * @param fileType       classified file type (MARKDOWN, CODE, YAML, etc.)
 * @param language       detected programming language, or null if not code
 * @param sizeBytes      file size in bytes
 * @param lastModified   last modification timestamp
 * @param contentHash    MD5 hash of file content (null if hashing disabled or failed)
 */
public record FileMetadata(
        Path path,
        String relativePath,
        String fileName,
        String extension,
        FileUtils.FileType fileType,
        String language,
        long sizeBytes,
        Instant lastModified,
        String contentHash
) {

    /**
     * Creates FileMetadata from a path and workspace root.
     * Computes extension, file type, and language automatically.
     *
     * @param path          absolute path to file
     * @param workspaceRoot workspace root for relative path computation
     * @param sizeBytes     file size
     * @param lastModified  last modification time
     * @param contentHash   MD5 hash or null
     */
    public static FileMetadata of(Path path, Path workspaceRoot, long sizeBytes,
                                   Instant lastModified, String contentHash) {
        String extension = FileUtils.getExtension(path);
        FileUtils.FileType fileType = FileUtils.classifyFile(path);
        String language = FileUtils.detectLanguage(path);
        String relativePath = workspaceRoot.relativize(path).toString();

        return new FileMetadata(
                path,
                relativePath,
                path.getFileName().toString(),
                extension,
                fileType,
                language,
                sizeBytes,
                lastModified,
                contentHash
        );
    }

    /**
     * Whether this file's content should be indexed for full-text search.
     * Binary files and pure binary archives are excluded.
     * Media files (images, video, audio) are indexable when they have
     * metadata or AI-generated descriptions.
     */
    public boolean isIndexableContent() {
        return fileType != FileUtils.FileType.BINARY;
    }

    /**
     * Whether this file is a media file (image, video, or audio).
     */
    public boolean isMedia() {
        return fileType.isMedia();
    }

    /**
     * Whether this file is an image.
     */
    public boolean isImage() {
        return fileType == FileUtils.FileType.IMAGE;
    }

    /**
     * Whether this file is a video.
     */
    public boolean isVideo() {
        return fileType == FileUtils.FileType.VIDEO;
    }

    /**
     * Whether this file is an audio file.
     */
    public boolean isAudio() {
        return fileType == FileUtils.FileType.AUDIO;
    }
}
