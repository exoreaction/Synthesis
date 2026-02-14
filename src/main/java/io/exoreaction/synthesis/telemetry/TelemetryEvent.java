package io.exoreaction.synthesis.telemetry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a telemetry event to be reported.
 *
 * <p>Events are privacy-safe by design: they contain ONLY operational metadata,
 * never workspace content, file names, user data, or credentials.
 *
 * <p>Event types:
 * <ul>
 *   <li>{@code INSTALL} -- first-time installation or update</li>
 *   <li>{@code COMMAND} -- command execution (name, success/failure, duration)</li>
 *   <li>{@code HEARTBEAT} -- periodic "still alive" signal</li>
 * </ul>
 *
 * @see TelemetryService
 */
public class TelemetryEvent {

    public enum EventType {
        INSTALL,
        COMMAND,
        HEARTBEAT
    }

    private final EventType type;
    private final String clientUuid;
    private final Instant timestamp;
    private final Map<String, String> properties;

    private TelemetryEvent(EventType type, String clientUuid, Instant timestamp, Map<String, String> properties) {
        this.type = type;
        this.clientUuid = clientUuid;
        this.timestamp = timestamp;
        this.properties = Map.copyOf(properties);
    }

    public EventType getType() {
        return type;
    }

    public String getClientUuid() {
        return clientUuid;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Formats this event as a Slack message string.
     *
     * <p>Uses a structured, human-readable format suitable for Slack channel display.
     */
    public String toSlackMessage() {
        StringBuilder sb = new StringBuilder();

        String emoji = switch (type) {
            case INSTALL -> ":rocket:";
            case COMMAND -> ":gear:";
            case HEARTBEAT -> ":heartbeat:";
        };

        sb.append(emoji).append(" *").append(type.name()).append("*");
        sb.append(" | `").append(clientUuid).append("`");
        sb.append(" | ").append(timestamp.toString());

        if (!properties.isEmpty()) {
            sb.append("\n");
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                sb.append("    ").append(entry.getKey()).append(": `").append(entry.getValue()).append("`\n");
            }
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "TelemetryEvent{type=" + type + ", uuid=" + clientUuid + ", ts=" + timestamp
                + ", props=" + properties + "}";
    }

    // --- Factory Methods ---

    /**
     * Creates an INSTALL event with system information.
     */
    public static TelemetryEvent install(String clientUuid, String synthesisVersion) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("synthesis_version", synthesisVersion);
        props.put("os", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""));
        props.put("os_arch", System.getProperty("os.arch", "unknown"));
        props.put("java_version", System.getProperty("java.version", "unknown"));
        props.put("java_vendor", System.getProperty("java.vendor", "unknown"));

        return new TelemetryEvent(EventType.INSTALL, clientUuid, Instant.now(), props);
    }

    /**
     * Creates a COMMAND event for a command execution.
     *
     * @param clientUuid the client UUID
     * @param commandName the command name (e.g., "scan", "search", "init")
     * @param success whether the command succeeded
     * @param durationMs execution duration in milliseconds
     */
    public static TelemetryEvent command(String clientUuid, String commandName, boolean success, long durationMs) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("command", commandName);
        props.put("success", String.valueOf(success));
        props.put("duration_ms", String.valueOf(durationMs));

        return new TelemetryEvent(EventType.COMMAND, clientUuid, Instant.now(), props);
    }

    /**
     * Creates a HEARTBEAT event.
     */
    public static TelemetryEvent heartbeat(String clientUuid, String synthesisVersion) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("synthesis_version", synthesisVersion);
        props.put("os", System.getProperty("os.name", "unknown"));
        props.put("java_version", System.getProperty("java.version", "unknown"));

        return new TelemetryEvent(EventType.HEARTBEAT, clientUuid, Instant.now(), props);
    }

    /**
     * Builder for creating custom events with additional properties.
     */
    public static Builder builder(EventType type, String clientUuid) {
        return new Builder(type, clientUuid);
    }

    public static class Builder {
        private final EventType type;
        private final String clientUuid;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(EventType type, String clientUuid) {
            this.type = type;
            this.clientUuid = clientUuid;
        }

        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public TelemetryEvent build() {
            return new TelemetryEvent(type, clientUuid, Instant.now(), properties);
        }
    }
}
