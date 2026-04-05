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
import java.time.temporal.ChronoUnit;

/**
 * Persists the state of the last {@code synthesis topic-triage} run so that
 * the dual-threshold auto-trigger (24h elapsed AND 5+ new sessions) can be enforced.
 *
 * <p>State is stored at {@code ~/.synthesis/consolidate-state.json}.
 * All writes are atomic (temp file + rename) to avoid corruption on crash.
 */
public class ConsolidateState {

    private static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".synthesis", "consolidate-state.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static final long MIN_HOURS_BETWEEN_RUNS = 24;
    static final int MIN_NEW_SESSIONS = 5;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(
            @JsonProperty("lastConsolidatedAt") Instant lastConsolidatedAt,
            @JsonProperty("sessionCountAtLastConsolidate") int sessionCountAtLastConsolidate
    ) {
        public static State empty() {
            return new State(null, 0);
        }
    }

    public static State load() {
        return load(DEFAULT_PATH);
    }

    public static State load(Path path) {
        if (path == null || !Files.isRegularFile(path)) return State.empty();
        try {
            String json = Files.readString(path);
            if (json.isBlank()) return State.empty();
            return MAPPER.readValue(json, State.class);
        } catch (Exception e) {
            return State.empty();
        }
    }

    public static void save(State state) throws IOException {
        save(state, DEFAULT_PATH);
    }

    public static void save(State state, Path path) throws IOException {
        if (state == null || path == null) return;
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        String json = MAPPER.writeValueAsString(state);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Returns {@code true} if both thresholds are met:
     * <ul>
     *   <li>At least 24 hours since last topic-triage run</li>
     *   <li>At least 5 new sessions since last topic-triage run</li>
     * </ul>
     *
     * @param state                  loaded consolidate state
     * @param newSessionsSinceLastRun number of new sessions since last run
     */
    public static boolean isDue(State state, int newSessionsSinceLastRun) {
        if (state == null || state.lastConsolidatedAt() == null) return true;
        long hoursSince = ChronoUnit.HOURS.between(state.lastConsolidatedAt(), Instant.now());
        return hoursSince >= MIN_HOURS_BETWEEN_RUNS && newSessionsSinceLastRun >= MIN_NEW_SESSIONS;
    }
}
