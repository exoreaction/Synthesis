package io.exoreaction.synthesis.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Persistent scan state for incremental scanning.
 *
 * <p>Tracks the last scan timestamp and a per-file record of hash, size,
 * and last-modified time. This enables {@code synthesis maintain} to detect
 * new, modified, and deleted files without rescanning the entire workspace.
 *
 * <p>Persisted as JSON to {@code .synthesis/scan-state.json}.
 *
 * <p>Format (hand-serialized for zero extra dependencies):
 * <pre>
 * {
 *   "version": 1,
 *   "lastScanTime": "2026-02-14T10:00:00Z",
 *   "fileCount": 42,
 *   "entries": {
 *     "src/Main.java": { "hash": "abc123", "size": 1024, "lastModified": "2026-02-14T09:30:00Z" },
 *     ...
 *   }
 * }
 * </pre>
 */
public class ScanState {

    private static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private Instant lastScanTime;
    private final Map<String, FileEntry> entries;

    /** Creates a new empty scan state. */
    public ScanState() {
        this.lastScanTime = Instant.now();
        this.entries = new LinkedHashMap<>();
    }

    /** Creates a scan state from a completed scan result. */
    public static ScanState fromScanResult(ScanResult result) {
        ScanState state = new ScanState();
        state.lastScanTime = result.scanTime();
        for (FileMetadata file : result.files()) {
            state.entries.put(file.relativePath(), FileEntry.fromMetadata(file));
        }
        return state;
    }

    /**
     * Computes the diff between this saved state and a fresh scan result.
     *
     * @param freshScan the new scan result to compare against
     * @return the changes detected
     */
    public ChangeSet computeChanges(ScanResult freshScan) {
        Map<String, FileMetadata> freshFiles = new LinkedHashMap<>();
        for (FileMetadata fm : freshScan.files()) {
            freshFiles.put(fm.relativePath(), fm);
        }

        List<FileMetadata> added = new ArrayList<>();
        List<FileMetadata> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        // Find new and modified files
        for (var entry : freshFiles.entrySet()) {
            String path = entry.getKey();
            FileMetadata current = entry.getValue();
            FileEntry previous = entries.get(path);

            if (previous == null) {
                added.add(current);
            } else if (previous.isModified(current)) {
                modified.add(current);
            }
        }

        // Find deleted files
        for (String path : entries.keySet()) {
            if (!freshFiles.containsKey(path)) {
                deleted.add(path);
            }
        }

        return new ChangeSet(added, modified, deleted);
    }

    // --- Persistence (hand-serialized JSON to avoid extra dependency) ---

    /**
     * Saves this scan state to a JSON file.
     */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            writer.write("{\n");
            writer.write("  \"version\": " + version + ",\n");
            writer.write("  \"lastScanTime\": \"" + lastScanTime + "\",\n");
            writer.write("  \"fileCount\": " + entries.size() + ",\n");
            writer.write("  \"entries\": {\n");

            var it = entries.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                String key = escapeJson(entry.getKey());
                FileEntry fe = entry.getValue();
                writer.write("    \"" + key + "\": {");
                writer.write("\"hash\": " + (fe.hash != null ? "\"" + escapeJson(fe.hash) + "\"" : "null"));
                writer.write(", \"size\": " + fe.size);
                writer.write(", \"lastModified\": \"" + fe.lastModified + "\"");
                writer.write("}");
                if (it.hasNext()) writer.write(",");
                writer.write("\n");
            }

            writer.write("  }\n");
            writer.write("}\n");
        }
    }

    /**
     * Loads a scan state from a JSON file.
     * Returns empty state if file doesn't exist or is corrupt.
     */
    public static ScanState load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new ScanState();
        }

        String json = Files.readString(path);
        return parse(json);
    }

    /**
     * Checks if a scan state file exists at the given path.
     */
    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    // --- Accessors ---

    public Instant getLastScanTime() { return lastScanTime; }
    public int getFileCount() { return entries.size(); }
    public Map<String, FileEntry> getEntries() { return Collections.unmodifiableMap(entries); }

    /**
     * A single file's tracked state.
     */
    public record FileEntry(String hash, long size, Instant lastModified) {

        static FileEntry fromMetadata(FileMetadata metadata) {
            return new FileEntry(
                    metadata.contentHash(),
                    metadata.sizeBytes(),
                    metadata.lastModified()
            );
        }

        /**
         * Checks if the file has been modified compared to fresh metadata.
         * Uses hash comparison when available, falls back to size + timestamp.
         */
        boolean isModified(FileMetadata current) {
            // If we have hashes, use them (most reliable)
            if (hash != null && current.contentHash() != null) {
                return !hash.equals(current.contentHash());
            }
            // Fall back to size + timestamp comparison
            return size != current.sizeBytes()
                    || !lastModified.equals(current.lastModified());
        }
    }

    /**
     * The set of changes detected between a saved scan state and a fresh scan.
     */
    public record ChangeSet(
            List<FileMetadata> added,
            List<FileMetadata> modified,
            List<String> deleted
    ) {
        /** Total number of changes. */
        public int totalChanges() {
            return added.size() + modified.size() + deleted.size();
        }

        /** Whether there are any changes. */
        public boolean hasChanges() {
            return totalChanges() > 0;
        }
    }

    // --- JSON parsing (minimal, dependency-free) ---

    private static ScanState parse(String json) {
        ScanState state = new ScanState();
        state.entries.clear();

        try {
            // Extract version
            String versionStr = extractJsonValue(json, "version");
            if (versionStr != null) {
                state.version = Integer.parseInt(versionStr.trim());
            }

            // Extract lastScanTime
            String timeStr = extractJsonStringValue(json, "lastScanTime");
            if (timeStr != null) {
                state.lastScanTime = Instant.parse(timeStr);
            }

            // Extract entries block
            int entriesStart = json.indexOf("\"entries\"");
            if (entriesStart < 0) return state;

            int braceStart = json.indexOf('{', entriesStart + 9);
            if (braceStart < 0) return state;

            // Find matching closing brace for entries
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceEnd < 0) return state;

            String entriesBlock = json.substring(braceStart + 1, braceEnd);

            // Parse each entry: "path": {"hash": "...", "size": ..., "lastModified": "..."}
            int pos = 0;
            while (pos < entriesBlock.length()) {
                int keyStart = entriesBlock.indexOf('"', pos);
                if (keyStart < 0) break;
                int keyEnd = findClosingQuote(entriesBlock, keyStart + 1);
                if (keyEnd < 0) break;

                String filePath = unescapeJson(entriesBlock.substring(keyStart + 1, keyEnd));

                int valueStart = entriesBlock.indexOf('{', keyEnd);
                if (valueStart < 0) break;
                int valueEnd = entriesBlock.indexOf('}', valueStart);
                if (valueEnd < 0) break;

                String valueBlock = entriesBlock.substring(valueStart + 1, valueEnd);

                String hash = extractJsonStringValue(valueBlock, "hash");
                String sizeStr = extractJsonValue(valueBlock, "size");
                String modifiedStr = extractJsonStringValue(valueBlock, "lastModified");

                long size = sizeStr != null ? Long.parseLong(sizeStr.trim()) : 0;
                Instant modified = modifiedStr != null ? Instant.parse(modifiedStr) : Instant.EPOCH;

                state.entries.put(filePath, new FileEntry(hash, size, modified));

                pos = valueEnd + 1;
            }
        } catch (Exception e) {
            // If parsing fails, return empty state -- will trigger full rescan
            return new ScanState();
        }

        return state;
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;

        int start = colonIdx + 1;
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        // Find end of value (comma, closing brace, or newline)
        if (json.charAt(start) == '"') {
            // String value -- extract using extractJsonStringValue instead
            return null;
        }

        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != '\n') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static String extractJsonStringValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;

        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == 'n') {
            // null value
            return null;
        }

        if (json.charAt(start) != '"') return null;

        int end = findClosingQuote(json, start + 1);
        if (end < 0) return null;

        return unescapeJson(json.substring(start + 1, end));
    }

    private static int findClosingQuote(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String json, int openBrace) {
        int depth = 1;
        boolean inString = false;
        for (int i = openBrace + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
