package io.exoreaction.synthesis.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Persists the state of the last {@code synthesis reflect} run so that
 * subsequent invocations can skip re-analysis of already-processed sessions.
 *
 * <p>State is stored as a compact JSON file at
 * {@code ~/.synthesis/reflect-state.json}. All writes are atomic
 * (temp file + rename) to avoid corruption on crash.
 */
public class ReflectState {

    private static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".synthesis", "reflect-state.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Immutable snapshot of reflect state.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(
            @JsonProperty("lastReflectedAt") Instant lastReflectedAt,
            @JsonProperty("sessionsProcessed") int sessionsProcessed,
            @JsonProperty("skillsCreated") int skillsCreated,
            @JsonProperty("skillsUpdated") int skillsUpdated
    ) {
        /** Empty initial state (never reflected). */
        public static State empty() {
            return new State(null, 0, 0, 0);
        }
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    /** Loads state from the default path. Returns empty state if missing. */
    public static State load() {
        return load(DEFAULT_PATH);
    }

    /** Loads state from a specific path. Returns empty state if missing or unreadable. */
    public static State load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return State.empty();
        }
        try {
            String json = Files.readString(path);
            if (json.isBlank()) return State.empty();
            return MAPPER.readValue(json, State.class);
        } catch (Exception e) {
            return State.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    /** Saves state to the default path (atomic write). */
    public static void save(State state) throws IOException {
        save(state, DEFAULT_PATH);
    }

    /** Saves state to a specific path (atomic write via temp file + rename). */
    public static void save(State state, Path path) throws IOException {
        if (state == null || path == null) return;

        // Ensure parent directory exists
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }

        String json = MAPPER.writeValueAsString(state);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // -----------------------------------------------------------------------
    // Staleness check
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the state is stale relative to the given cutoff.
     * A state is stale if it has never been reflected or was last reflected before {@code since}.
     */
    public static boolean isStale(State state, Instant since) {
        if (state == null || state.lastReflectedAt() == null) return true;
        return state.lastReflectedAt().isBefore(since);
    }
}
