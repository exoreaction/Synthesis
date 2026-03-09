package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.skills.ReflectState.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReflectState}: load/save round-trip, missing file handling,
 * and staleness checks.
 */
class ReflectStateTest {

    @TempDir
    Path tempDir;

    @Test
    void testRoundTrip() throws Exception {
        Path stateFile = tempDir.resolve("reflect-state.json");
        Instant now = Instant.now();
        State original = new State(now, 12, 3, 5);

        ReflectState.save(original, stateFile);
        assertTrue(Files.exists(stateFile), "State file should exist after save");

        State loaded = ReflectState.load(stateFile);
        assertEquals(original.sessionsProcessed(), loaded.sessionsProcessed(),
                "sessionsProcessed should round-trip");
        assertEquals(original.skillsCreated(), loaded.skillsCreated(),
                "skillsCreated should round-trip");
        assertEquals(original.skillsUpdated(), loaded.skillsUpdated(),
                "skillsUpdated should round-trip");
        // Instant comparison: compare epoch seconds (JSON serialization may lose nanos)
        assertEquals(original.lastReflectedAt().getEpochSecond(),
                loaded.lastReflectedAt().getEpochSecond(),
                "lastReflectedAt should round-trip (epoch seconds)");
    }

    @Test
    void testMissingFileReturnsEmpty() {
        Path missing = tempDir.resolve("does-not-exist.json");
        State state = ReflectState.load(missing);

        assertNotNull(state, "Load should never return null");
        assertNull(state.lastReflectedAt(), "Missing file should have null lastReflectedAt");
        assertEquals(0, state.sessionsProcessed(), "Missing file should have 0 sessionsProcessed");
    }

    @Test
    void testStalenessCheck() {
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);

        State state = new State(oneHourAgo, 5, 1, 2);

        // State reflected 1 hour ago should NOT be stale relative to 2 hours ago
        assertFalse(ReflectState.isStale(state, twoHoursAgo),
                "State reflected after 'since' should not be stale");

        // State reflected 1 hour ago SHOULD be stale relative to now
        assertTrue(ReflectState.isStale(state, Instant.now()),
                "State reflected before 'since' should be stale");

        // Null state is always stale
        assertTrue(ReflectState.isStale(State.empty(), Instant.now()),
                "Empty state (null lastReflectedAt) should always be stale");
        assertTrue(ReflectState.isStale(null, Instant.now()),
                "Null state should always be stale");
    }
}
