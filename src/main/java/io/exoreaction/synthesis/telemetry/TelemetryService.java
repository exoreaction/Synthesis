package io.exoreaction.synthesis.telemetry;

import com.slack.api.Slack;
import com.slack.api.webhook.Payload;
import com.slack.api.webhook.WebhookResponse;
import io.exoreaction.synthesis.util.Version;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mandatory telemetry reporting service for Synthesis pilot installations.
 *
 * <p>Sends privacy-safe operational events to a Slack channel via incoming webhook.
 * Telemetry is always active for pilot users and cannot be disabled.
 *
 * <p>Design principles:
 * <ul>
 *   <li><b>Never block:</b> Events are sent asynchronously on a daemon thread.
 *       Command execution is never delayed by telemetry.</li>
 *   <li><b>Never crash:</b> All exceptions are swallowed silently. Telemetry failures
 *       must never affect the user's workflow.</li>
 *   <li><b>Privacy-safe:</b> Only operational metadata is sent. See {@link TelemetryEvent}
 *       for the complete list of properties.</li>
 *   <li><b>Always on:</b> Telemetry is mandatory for pilot program participation.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   TelemetryService telemetry = TelemetryService.create();
 *   telemetry.reportCommand("scan", true, 1234);
 *   telemetry.shutdown(); // in app shutdown hook
 * </pre>
 *
 * @see TelemetryConfig
 * @see TelemetryEvent
 * @see ClientUUID
 */
public class TelemetryService {

    /** Synthesis version string, used in telemetry events. */
    public static final String SYNTHESIS_VERSION = Version.getVersion();

    private final TelemetryConfig config;
    private final String clientUuid;
    private final ExecutorService executor;
    private final Slack slack;

    /**
     * Creates a TelemetryService with the given config and UUID.
     *
     * @param config the telemetry configuration
     * @param clientUuid the client UUID for this installation
     */
    public TelemetryService(TelemetryConfig config, String clientUuid) {
        this.config = config;
        this.clientUuid = clientUuid;

        if (config.isWebhookConfigured()) {
            // Single daemon thread -- telemetry should be lightweight
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "synthesis-telemetry");
                t.setDaemon(true);
                return t;
            });
            this.slack = Slack.getInstance();
        } else {
            this.executor = null;
            this.slack = null;
        }
    }

    /**
     * Creates a no-op TelemetryService that silently discards all events.
     *
     * <p>Used in air-gapped mode ({@code SYNTHESIS_EDITION=core} or
     * {@code SYNTHESIS_EDITION=enterprise}) where no network connectivity
     * is available or desired.
     *
     * @return a TelemetryService that does nothing
     */
    public static TelemetryService createNoOp() {
        return new TelemetryService(new TelemetryConfig(), "air-gapped");
    }

    /**
     * Creates a TelemetryService from the global configuration and UUID.
     *
     * <p>This is the standard factory method for production use. It loads
     * configuration from {@code ~/.synthesis/telemetry.properties} and reads
     * (or generates) the client UUID from {@code ~/.synthesis/client-uuid}.
     *
     * @return a configured TelemetryService
     */
    public static TelemetryService create() {
        TelemetryConfig config = TelemetryConfig.load();
        String uuid;
        try {
            uuid = ClientUUID.getOrCreate();
        } catch (IOException e) {
            return new TelemetryService(new TelemetryConfig(), "unknown");
        }
        return new TelemetryService(config, uuid);
    }

    /**
     * Creates a TelemetryService using a custom home directory (for testing).
     */
    public static TelemetryService create(Path homeDir) {
        TelemetryConfig config = TelemetryConfig.load(TelemetryConfig.getConfigPath(homeDir));
        String uuid;
        try {
            uuid = ClientUUID.getOrCreate(ClientUUID.getUuidPath(homeDir));
        } catch (IOException e) {
            return new TelemetryService(new TelemetryConfig(), "unknown");
        }
        return new TelemetryService(config, uuid);
    }

    /**
     * Reports an installation event.
     * Called during {@code synthesis init} to notify the pilot channel.
     */
    public void reportInstall() {
        if (!isActive()) return;
        send(TelemetryEvent.install(clientUuid, SYNTHESIS_VERSION));
    }

    /**
     * Reports a command execution event.
     *
     * @param commandName the command name (e.g., "scan", "search", "init")
     * @param success whether the command completed successfully
     * @param durationMs execution duration in milliseconds
     */
    public void reportCommand(String commandName, boolean success, long durationMs) {
        if (!isActive()) return;
        send(TelemetryEvent.command(clientUuid, commandName, success, durationMs));
    }

    /**
     * Reports a heartbeat event.
     */
    public void reportHeartbeat() {
        if (!isActive()) return;
        send(TelemetryEvent.heartbeat(clientUuid, SYNTHESIS_VERSION));
    }

    /**
     * Sends a custom telemetry event.
     */
    public void send(TelemetryEvent event) {
        if (!isActive()) return;

        executor.submit(() -> {
            try {
                Payload payload = Payload.builder()
                        .text(event.toSlackMessage())
                        .build();

                WebhookResponse response = slack.send(config.getSlackWebhookUrl(), payload);

                if (response.getCode() != 200) {
                    // Silently ignore -- telemetry should never affect the user
                }
            } catch (Exception e) {
                // Silently ignore -- telemetry should never affect the user
            }
        });
    }

    /**
     * Returns whether telemetry is active (webhook configured and executor available).
     * Telemetry is always logically "on" -- this only checks whether the webhook
     * is configured so events can actually be delivered.
     */
    public boolean isActive() {
        return config.isWebhookConfigured() && executor != null;
    }

    /**
     * Returns the client UUID for this installation.
     */
    public String getClientUuid() {
        return clientUuid;
    }

    /**
     * Returns the current telemetry configuration.
     */
    public TelemetryConfig getConfig() {
        return config;
    }

    /**
     * Shuts down the telemetry executor, waiting up to 2 seconds for pending events.
     *
     * <p>Should be called during application shutdown to ensure in-flight events
     * are delivered. If events are still pending after 2 seconds, they are discarded.
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (slack != null) {
            try {
                slack.close();
            } catch (Exception e) {
                // Silently ignore
            }
        }
    }

    /**
     * Returns a human-readable summary of what telemetry reports.
     * Used by {@code synthesis telemetry --show} to display what data is sent.
     */
    public String describeWhatIsSent() {
        StringBuilder sb = new StringBuilder();
        sb.append("Synthesis Pilot - Telemetry Data Report\n");
        sb.append("=======================================\n\n");

        sb.append("Telemetry Status: ").append(isActive() ? "ACTIVE" : "INACTIVE (no webhook configured)").append("\n");
        sb.append("Client UUID: ").append(clientUuid).append("\n\n");

        sb.append("Note: Telemetry is mandatory for pilot program participation.\n\n");

        sb.append("What IS sent:\n");
        sb.append("  - Client UUID (random identifier, not linked to your identity)\n");
        sb.append("  - Command name (e.g., 'scan', 'search')\n");
        sb.append("  - Command success/failure (boolean)\n");
        sb.append("  - Command duration (milliseconds)\n");
        sb.append("  - OS name and version\n");
        sb.append("  - Java version\n");
        sb.append("  - Synthesis version\n\n");

        sb.append("What is NEVER sent:\n");
        sb.append("  - Workspace content, file names, or file paths\n");
        sb.append("  - API keys, credentials, or tokens\n");
        sb.append("  - User identity, hostname, or IP address\n");
        sb.append("  - Search queries or command arguments\n");
        sb.append("  - Any workspace data whatsoever\n\n");

        sb.append("Example event:\n");
        TelemetryEvent example = TelemetryEvent.command(clientUuid, "scan", true, 1234);
        sb.append("  ").append(example.toSlackMessage().replace("\n", "\n  "));

        return sb.toString();
    }
}
