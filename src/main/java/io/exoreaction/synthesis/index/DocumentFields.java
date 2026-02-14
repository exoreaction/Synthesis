package io.exoreaction.synthesis.index;

/**
 * Constants for Lucene document field names.
 * Centralized here to avoid magic strings scattered across the codebase.
 */
public final class DocumentFields {

    private DocumentFields() {}

    // --- Identity fields (stored, not tokenized) ---

    /** Absolute file path (stored for retrieval, not searched). */
    public static final String PATH = "path";

    /** Relative path from workspace root (stored and searchable). */
    public static final String RELATIVE_PATH = "relativePath";

    /** File name without directory (searchable, stored). */
    public static final String FILENAME = "filename";

    /** File extension including dot, e.g. ".java" (keyword field, stored). */
    public static final String EXTENSION = "extension";

    // --- Classification fields (keyword/facet) ---

    /** File type classification: MARKDOWN, CODE, YAML, etc. */
    public static final String FILE_TYPE = "fileType";

    /** Programming language if detected: Java, Python, etc. */
    public static final String LANGUAGE = "language";

    // --- Content fields (tokenized, searchable) ---

    /** Full text content of the file (main search field). */
    public static final String CONTENT = "content";

    /** Document headings / titles (boosted for relevance). */
    public static final String HEADINGS = "headings";

    /** Extracted keywords (boosted for relevance). */
    public static final String KEYWORDS = "keywords";

    /** Analysis summary (boosted for relevance). */
    public static final String SUMMARY = "summary";

    // --- Metadata fields (stored for display) ---

    /** File size in bytes (stored as string for display). */
    public static final String SIZE = "size";

    /** Last modified timestamp as epoch millis (stored for sorting). */
    public static final String LAST_MODIFIED = "lastModified";

    /** Content hash for duplicate detection. */
    public static final String CONTENT_HASH = "contentHash";

    /** Structural description of the file. */
    public static final String STRUCTURE = "structure";

    /** Repository identifier for multi-repo workspaces. */
    public static final String REPOSITORY = "repository";

    /** Organization identifier for multi-org workspaces (e.g., "eXOReaction"). */
    public static final String ORGANIZATION = "organization";

    /** Client identifier for client-scoped files (e.g., "SpareBank1"). */
    public static final String CLIENT = "client";

    // --- Media-specific fields ---

    /** Media type for presentation detection: "presentation", "document", "spreadsheet", etc. */
    public static final String MEDIA_TYPE = "mediaType";

    /** Image dimensions as "WxH" string (e.g., "1920x1080"). */
    public static final String DIMENSIONS = "dimensions";

    /** Duration of audio/video in seconds (stored as string). */
    public static final String DURATION = "duration";

    /** AI-generated description of an image or slide. */
    public static final String AI_DESCRIPTION = "aiDescription";

    /** Companion file path (e.g., transcript for video, metadata sidecar). */
    public static final String COMPANION_FILE = "companionFile";
}
