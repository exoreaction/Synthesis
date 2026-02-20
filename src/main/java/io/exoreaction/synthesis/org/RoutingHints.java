package io.exoreaction.synthesis.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages routing hints learned from {@code synthesis staging resolve --learn}.
 *
 * <p>Routing hints are filename glob patterns associated with destination directories.
 * They are stored in {@code .synthesis/routing-hints.json} at the workspace level.
 * When loaded by {@link DirectoryIdentityRouter}, matching hints contribute to
 * routing decisions, enabling the system to learn from user actions.
 *
 * <p>Thread safety: This class is NOT thread-safe. Use from a single thread.
 *
 * @since v1.9.9
 */
public class RoutingHints {

    /**
     * A single routing hint learned from a user's resolve action.
     *
     * @param filenamePattern  glob pattern derived from the resolved file
     * @param destinationPath  where the user sent the file (absolute path)
     * @param learnedAt        when the hint was first learned
     * @param hitCount         how many times this hint has matched
     */
    public record RoutingHint(
            String filenamePattern,
            String destinationPath,
            Instant learnedAt,
            int hitCount
    ) {}

    private static final String HINTS_FILE = "routing-hints.json";

    /** Pattern matching ISO date fragments like 2026-02-20. */
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Pattern matching pure numeric tokens. */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+$");

    private final Path workspaceRoot;
    private List<RoutingHint> hints;

    public RoutingHints(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.hints = new ArrayList<>();
    }

    /**
     * Loads hints from {@code .synthesis/routing-hints.json}.
     * If the file does not exist, returns an empty list.
     *
     * @return the loaded hints (also cached internally)
     * @throws IOException if the file exists but cannot be read
     */
    public List<RoutingHint> load() throws IOException {
        Path hintsFile = getHintsFilePath();
        if (!Files.exists(hintsFile)) {
            hints = new ArrayList<>();
            return hints;
        }

        String json = Files.readString(hintsFile);
        hints = parseJson(json);
        return hints;
    }

    /**
     * Saves hints to {@code .synthesis/routing-hints.json}.
     * Creates the file and parent directories if needed.
     *
     * @param hints the hints to save
     * @throws IOException if the file cannot be written
     */
    public void save(List<RoutingHint> hints) throws IOException {
        this.hints = new ArrayList<>(hints);
        Path hintsFile = getHintsFilePath();
        Files.createDirectories(hintsFile.getParent());

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        List<HintJson> jsonHints = hints.stream()
                .map(h -> new HintJson(h.filenamePattern(), h.destinationPath(),
                        h.learnedAt().toString(), h.hitCount()))
                .toList();

        mapper.writeValue(hintsFile.toFile(), jsonHints);
    }

    /**
     * Derives a glob pattern from a filename.
     *
     * <p>Takes significant tokens (excluding ISO dates, pure numbers, and tokens shorter
     * than 4 characters). The resulting pattern is of the form {@code *token1*token2*.ext}.
     * If no meaningful tokens are found, falls back to {@code *.ext}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "mynder-meeting-2026-02-20.pdf"} becomes {@code "*mynder*meeting*.pdf"}</li>
     *   <li>{@code "abc.pdf"} becomes {@code "*.pdf"}</li>
     *   <li>{@code "invoice-Q4-2025.xlsx"} becomes {@code "*invoice*.xlsx"}</li>
     * </ul>
     *
     * @param filename the filename to derive a pattern from (just the name, not path)
     * @return the derived glob pattern
     */
    public static String derivePattern(String filename) {
        // Separate extension
        int dotIdx = filename.lastIndexOf('.');
        String ext = dotIdx >= 0 ? filename.substring(dotIdx) : "";
        String baseName = dotIdx >= 0 ? filename.substring(0, dotIdx) : filename;

        // Split by common separators
        String[] tokens = baseName.split("[-_. ]+");

        // Filter tokens: keep those that are >= 4 chars, not pure numbers, not ISO dates
        List<String> meaningful = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() < 4) continue;
            if (NUMERIC_PATTERN.matcher(token).matches()) continue;
            if (ISO_DATE_PATTERN.matcher(token).matches()) continue;
            meaningful.add(token.toLowerCase());
        }

        if (meaningful.isEmpty()) {
            return "*" + ext;
        }

        // Build pattern: *token1*token2*.ext
        StringBuilder sb = new StringBuilder("*");
        for (int i = 0; i < meaningful.size(); i++) {
            sb.append(meaningful.get(i));
            sb.append("*");
        }
        sb.append(ext);
        return sb.toString();
    }

    /**
     * Returns hints whose filename pattern matches the given filename.
     *
     * <p>Uses Java's {@code glob:} syntax for matching.
     *
     * @param filename the filename to match against (just the name, not path)
     * @return matching hints
     */
    public List<RoutingHint> matchingHints(String filename) {
        List<RoutingHint> matches = new ArrayList<>();
        Path filenamePath = Path.of(filename);
        for (RoutingHint hint : hints) {
            try {
                java.nio.file.PathMatcher matcher = java.nio.file.FileSystems.getDefault()
                        .getPathMatcher("glob:" + hint.filenamePattern());
                if (matcher.matches(filenamePath)) {
                    matches.add(hint);
                }
            } catch (Exception e) {
                // Skip invalid patterns
            }
        }
        return matches;
    }

    /**
     * Adds or updates a hint. If a hint with the same filename pattern already exists,
     * its hit count is incremented. Otherwise, the new hint is appended.
     *
     * @param hint the hint to add or update
     * @throws IOException if saving fails
     */
    public void addOrUpdate(RoutingHint hint) throws IOException {
        if (hints == null) {
            hints = new ArrayList<>();
        }

        // Check for existing hint with same pattern
        for (int i = 0; i < hints.size(); i++) {
            if (hints.get(i).filenamePattern().equals(hint.filenamePattern())) {
                RoutingHint existing = hints.get(i);
                hints.set(i, new RoutingHint(
                        existing.filenamePattern(),
                        hint.destinationPath(),
                        existing.learnedAt(),
                        existing.hitCount() + 1
                ));
                save(hints);
                return;
            }
        }

        // New hint
        hints.add(hint);
        save(hints);
    }

    /**
     * Deletes a hint by index (1-based, as shown in 'hints list' output).
     *
     * @param index the 1-based index of the hint to delete
     * @throws IOException              if saving fails
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public void delete(int index) throws IOException {
        if (hints == null || index < 1 || index > hints.size()) {
            throw new IndexOutOfBoundsException("Invalid hint index: " + index
                    + " (have " + (hints != null ? hints.size() : 0) + " hints)");
        }
        hints.remove(index - 1);
        save(hints);
    }

    /**
     * Returns the current list of hints (must call {@link #load()} first).
     */
    public List<RoutingHint> getHints() {
        return hints != null ? List.copyOf(hints) : List.of();
    }

    /**
     * Returns the path to the hints file.
     */
    public Path getHintsFilePath() {
        return workspaceRoot.resolve(".synthesis").resolve(HINTS_FILE);
    }

    // --- JSON parsing ---

    private List<RoutingHint> parseJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            HintJson[] hintJsons = mapper.readValue(json, HintJson[].class);
            List<RoutingHint> result = new ArrayList<>();
            for (HintJson hj : hintJsons) {
                result.add(new RoutingHint(
                        hj.filenamePattern,
                        hj.destinationPath,
                        hj.learnedAt != null ? Instant.parse(hj.learnedAt) : Instant.now(),
                        hj.hitCount
                ));
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Simple DTO for Jackson serialization/deserialization.
     */
    static class HintJson {
        public String filenamePattern;
        public String destinationPath;
        public String learnedAt;
        public int hitCount;

        public HintJson() {}

        public HintJson(String filenamePattern, String destinationPath, String learnedAt, int hitCount) {
            this.filenamePattern = filenamePattern;
            this.destinationPath = destinationPath;
            this.learnedAt = learnedAt;
            this.hitCount = hitCount;
        }
    }
}
