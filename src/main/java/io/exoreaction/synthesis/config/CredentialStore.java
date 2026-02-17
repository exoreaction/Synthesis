package io.exoreaction.synthesis.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Machine-local credential store for Synthesis API keys.
 *
 * <p>Stores credentials in {@code ~/.synthesis/credentials} with:
 * <ul>
 *   <li>File permissions 600 (owner read/write only)</li>
 *   <li>XOR obfuscation keyed to this machine's client UUID</li>
 *   <li>Base64 encoding for safe file storage</li>
 * </ul>
 *
 * <p>This is obfuscation, not encryption — it prevents accidental exposure
 * (log files, screen sharing, shoulder surfing) but is not a substitute for
 * a proper secrets manager in multi-user environments.
 *
 * <p>Machine binding: credentials obfuscated on one machine will not decode
 * correctly on another (different client UUID = different XOR key).
 */
public class CredentialStore {

    private static final Path CREDENTIALS_FILE = Path.of(
            System.getProperty("user.home"), ".synthesis", "credentials");

    private static final Path CLIENT_UUID_FILE = Path.of(
            System.getProperty("user.home"), ".synthesis", "client-uuid");

    // ---- Public API ----

    /**
     * Stores a credential, overwriting any existing value for that name.
     *
     * @param name  credential name (e.g., "ANTHROPIC_API_KEY")
     * @param value the plaintext value to store
     * @throws IOException if the file cannot be written
     */
    public static void store(String name, String value) throws IOException {
        Map<String, String> entries = load();
        entries.put(name, obfuscate(value));
        save(entries);
    }

    /**
     * Retrieves and deobfuscates a stored credential.
     *
     * @param name the credential name
     * @return the plaintext value, or empty if not stored
     */
    public static Optional<String> retrieve(String name) {
        try {
            Map<String, String> entries = load();
            String obfuscated = entries.get(name);
            if (obfuscated == null) {
                return Optional.empty();
            }
            return Optional.of(deobfuscate(obfuscated));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Removes a stored credential.
     *
     * @param name the credential name to remove
     * @return true if the entry existed and was removed
     * @throws IOException if the file cannot be written
     */
    public static boolean clear(String name) throws IOException {
        Map<String, String> entries = load();
        boolean existed = entries.remove(name) != null;
        if (existed) {
            save(entries);
        }
        return existed;
    }

    /**
     * Returns the names of all stored credentials (values are not exposed).
     */
    public static Set<String> listNames() {
        try {
            return load().keySet();
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * Returns true if the credentials file exists and contains at least one entry.
     */
    public static boolean hasAny() {
        return !listNames().isEmpty();
    }

    // ---- File I/O ----

    private static Map<String, String> load() {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!Files.exists(CREDENTIALS_FILE)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(CREDENTIALS_FILE, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    entries.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            // Return empty map; will be recreated on next store()
        }
        return entries;
    }

    private static void save(Map<String, String> entries) throws IOException {
        // Ensure ~/.synthesis directory exists
        Files.createDirectories(CREDENTIALS_FILE.getParent());

        StringBuilder sb = new StringBuilder();
        sb.append("# Synthesis credential store — do not edit manually\n");
        sb.append("# Values are XOR-obfuscated with this machine's client UUID\n");
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }

        Files.writeString(CREDENTIALS_FILE, sb.toString(), StandardCharsets.UTF_8);

        // Restrict to owner read/write only (chmod 600)
        try {
            Files.setPosixFilePermissions(CREDENTIALS_FILE, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows NTFS) — permissions not settable
        }
    }

    // ---- XOR Obfuscation ----

    private static String obfuscate(String plaintext) {
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] key  = getMachineKey();
        byte[] out  = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return Base64.getEncoder().encodeToString(out);
    }

    private static String deobfuscate(String encoded) {
        byte[] data = Base64.getDecoder().decode(encoded);
        byte[] key  = getMachineKey();
        byte[] out  = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    /**
     * Returns the machine-specific XOR key derived from the client UUID.
     * Falls back to a fixed seed if the UUID file is unavailable.
     */
    private static byte[] getMachineKey() {
        try {
            String uuid = Files.readString(CLIENT_UUID_FILE, StandardCharsets.UTF_8).strip();
            if (!uuid.isEmpty()) {
                return uuid.getBytes(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // Fall through to fallback
        }
        // Fallback: fixed seed (weaker obfuscation, but still not plaintext)
        return "synthesis-credential-fallback-seed".getBytes(StandardCharsets.UTF_8);
    }
}
