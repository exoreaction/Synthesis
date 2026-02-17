package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.CredentialStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Manages locally stored API credentials for Synthesis.
 *
 * <p>Credentials are stored in {@code ~/.synthesis/credentials} with XOR obfuscation
 * (keyed to this machine's client UUID) and file permissions 600.
 * This prevents accidental exposure in logs, screen sharing, or casual file browsing,
 * while requiring no master password.
 *
 * <p>Resolution order for AI API keys (e.g., ANTHROPIC_API_KEY):
 * <ol>
 *   <li>Environment variable (highest priority)</li>
 *   <li>Credential store ({@code ~/.synthesis/credentials})</li>
 *   <li>Not found — AI features disabled</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   synthesis credentials set ANTHROPIC_API_KEY sk-ant-...
 *   synthesis credentials status
 *   synthesis credentials clear ANTHROPIC_API_KEY
 * </pre>
 */
@Command(
        name = "credentials",
        description = "Manage locally stored API credentials",
        mixinStandardHelpOptions = true,
        subcommands = {
                CredentialsCommand.SetCommand.class,
                CredentialsCommand.StatusCommand.class,
                CredentialsCommand.ClearCommand.class
        }
)
public class CredentialsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // No subcommand — print status
        return new StatusCommand().call();
    }

    // ---- set ----

    @Command(
            name = "set",
            description = "Store a credential (obfuscated, machine-local)"
    )
    static class SetCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Credential name (e.g., ANTHROPIC_API_KEY)")
        private String name;

        @Parameters(index = "1", description = "Credential value")
        private String value;

        @Override
        public Integer call() {
            if (name == null || name.isBlank()) {
                System.err.println("Error: credential name is required");
                return 1;
            }
            if (value == null || value.isBlank()) {
                System.err.println("Error: credential value is required");
                return 1;
            }
            try {
                CredentialStore.store(name, value);
                System.out.println("  \u2713 Stored " + name + " in ~/.synthesis/credentials (chmod 600, obfuscated)");
                System.out.println("  Resolution order: env var > credential store > disabled");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: could not store credential: " + e.getMessage());
                return 1;
            }
        }
    }

    // ---- status ----

    @Command(
            name = "status",
            description = "Show which credentials are stored"
    )
    static class StatusCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            System.out.println("Credential Store: ~/.synthesis/credentials");
            System.out.println("  Obfuscation: XOR with machine client UUID (chmod 600)");
            System.out.println();

            Set<String> names = CredentialStore.listNames();
            if (names.isEmpty()) {
                System.out.println("  No credentials stored.");
                System.out.println();
                System.out.println("  To store your Anthropic API key:");
                System.out.println("    synthesis credentials set ANTHROPIC_API_KEY sk-ant-...");
            } else {
                System.out.println("  Stored credentials:");
                for (String name : names) {
                    String envValue = System.getenv(name);
                    String source = (envValue != null && !envValue.isBlank())
                            ? "  \u26A0\uFE0F  overridden by env var"
                            : "  \u2713 active (no env var override)";
                    System.out.println("    " + name + source);
                }
            }
            System.out.println();
            System.out.println("  Resolution order: env var > credential store > disabled");
            return 0;
        }
    }

    // ---- clear ----

    @Command(
            name = "clear",
            description = "Remove a stored credential"
    )
    static class ClearCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Credential name to remove")
        private String name;

        @Override
        public Integer call() {
            if (name == null || name.isBlank()) {
                System.err.println("Error: credential name is required");
                return 1;
            }
            try {
                boolean removed = CredentialStore.clear(name);
                if (removed) {
                    System.out.println("  \u2713 Removed " + name + " from credential store");
                } else {
                    System.out.println("  " + name + " was not in the credential store");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: could not clear credential: " + e.getMessage());
                return 1;
            }
        }
    }
}
