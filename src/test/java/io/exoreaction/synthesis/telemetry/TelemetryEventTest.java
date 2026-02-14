package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TelemetryEvent} -- event creation, formatting, and privacy.
 */
class TelemetryEventTest {

    private static final String TEST_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void installEventContainsSystemInfo() {
        TelemetryEvent event = TelemetryEvent.install(TEST_UUID, "1.0.0");

        assertEquals(TelemetryEvent.EventType.INSTALL, event.getType());
        assertEquals(TEST_UUID, event.getClientUuid());
        assertNotNull(event.getTimestamp());
        assertTrue(event.getProperties().containsKey("synthesis_version"));
        assertTrue(event.getProperties().containsKey("os"));
        assertTrue(event.getProperties().containsKey("java_version"));
        assertEquals("1.0.0", event.getProperties().get("synthesis_version"));
    }

    @Test
    void commandEventContainsExecutionInfo() {
        TelemetryEvent event = TelemetryEvent.command(TEST_UUID, "scan", true, 1234);

        assertEquals(TelemetryEvent.EventType.COMMAND, event.getType());
        assertEquals(TEST_UUID, event.getClientUuid());
        assertEquals("scan", event.getProperties().get("command"));
        assertEquals("true", event.getProperties().get("success"));
        assertEquals("1234", event.getProperties().get("duration_ms"));
    }

    @Test
    void commandEventTracksFailure() {
        TelemetryEvent event = TelemetryEvent.command(TEST_UUID, "search", false, 42);

        assertEquals("false", event.getProperties().get("success"));
        assertEquals("search", event.getProperties().get("command"));
    }

    @Test
    void heartbeatEventContainsVersionInfo() {
        TelemetryEvent event = TelemetryEvent.heartbeat(TEST_UUID, "1.0.0");

        assertEquals(TelemetryEvent.EventType.HEARTBEAT, event.getType());
        assertTrue(event.getProperties().containsKey("synthesis_version"));
        assertTrue(event.getProperties().containsKey("os"));
    }

    @Test
    void toSlackMessageContainsAllRequiredFields() {
        TelemetryEvent event = TelemetryEvent.command(TEST_UUID, "scan", true, 1234);

        String message = event.toSlackMessage();

        assertTrue(message.contains("COMMAND"));
        assertTrue(message.contains(TEST_UUID));
        assertTrue(message.contains("scan"));
        assertTrue(message.contains("true"));
        assertTrue(message.contains("1234"));
    }

    @Test
    void toSlackMessageUsesCorrectEmojis() {
        assertEquals(true, TelemetryEvent.install(TEST_UUID, "1.0.0")
                .toSlackMessage().contains(":rocket:"));
        assertEquals(true, TelemetryEvent.command(TEST_UUID, "test", true, 0)
                .toSlackMessage().contains(":gear:"));
        assertEquals(true, TelemetryEvent.heartbeat(TEST_UUID, "1.0.0")
                .toSlackMessage().contains(":heartbeat:"));
    }

    @Test
    void propertiesAreImmutable() {
        TelemetryEvent event = TelemetryEvent.command(TEST_UUID, "scan", true, 100);

        assertThrows(UnsupportedOperationException.class, () ->
                event.getProperties().put("new_key", "value"));
    }

    @Test
    void timestampIsCloseToNow() {
        Instant before = Instant.now();
        TelemetryEvent event = TelemetryEvent.command(TEST_UUID, "test", true, 0);
        Instant after = Instant.now();

        assertFalse(event.getTimestamp().isBefore(before));
        assertFalse(event.getTimestamp().isAfter(after));
    }

    @Test
    void builderCreatesCustomEvents() {
        TelemetryEvent event = TelemetryEvent.builder(TelemetryEvent.EventType.INSTALL, TEST_UUID)
                .property("custom_key", "custom_value")
                .property("another", "prop")
                .build();

        assertEquals(TelemetryEvent.EventType.INSTALL, event.getType());
        assertEquals("custom_value", event.getProperties().get("custom_key"));
        assertEquals("prop", event.getProperties().get("another"));
    }

    @Test
    void eventNeverContainsSensitiveData() {
        // Verify that none of the factory methods include sensitive system properties
        TelemetryEvent install = TelemetryEvent.install(TEST_UUID, "1.0.0");

        for (String key : install.getProperties().keySet()) {
            assertFalse(key.contains("user"), "Should not contain user-related keys: " + key);
            assertFalse(key.contains("home"), "Should not contain home-related keys: " + key);
            assertFalse(key.contains("path"), "Should not contain path-related keys: " + key);
            assertFalse(key.contains("host"), "Should not contain host-related keys: " + key);
        }

        for (String value : install.getProperties().values()) {
            assertFalse(value.contains(System.getProperty("user.name", "")),
                    "Should not contain username in values");
            assertFalse(value.contains(System.getProperty("user.home", "")),
                    "Should not contain home directory in values");
        }
    }

    @Test
    void toStringIsInformative() {
        TelemetryEvent event = TelemetryEvent.command(TEST_UUID, "scan", true, 100);
        String str = event.toString();

        assertTrue(str.contains("COMMAND"));
        assertTrue(str.contains(TEST_UUID));
    }
}
