package io.exoreaction.synthesis.kcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * On-disk trust store for KCP signing keys (issue #360, Phase 7).
 *
 * <p>Lives at {@code ~/.synthesis/kcp-keys/} by default:
 * <ul>
 *   <li>{@code <kid>.pub} — base64 X.509 public key; the allowlist for verification</li>
 *   <li>{@code <kid>.key} — base64 PKCS#8 private key; {@code 0600}, used for signing,
 *       and never read into a manifest, log, or export</li>
 * </ul>
 */
public final class KcpTrustStore {

    private final Path dir;

    public KcpTrustStore(Path dir) {
        this.dir = dir;
    }

    /** Default store under the user's Synthesis home. */
    public static KcpTrustStore defaultStore() {
        return new KcpTrustStore(Path.of(System.getProperty("user.home"), ".synthesis", "kcp-keys"));
    }

    /**
     * Returns the key pair for {@code keyId}, generating and persisting a new one
     * when absent. The private key file is written {@code 0600} where supported.
     */
    public KeyPair loadOrCreateSigningKey(String keyId) throws Exception {
        Files.createDirectories(dir);
        Path pub = dir.resolve(keyId + ".pub");
        Path priv = dir.resolve(keyId + ".key");
        if (Files.exists(pub) && Files.exists(priv)) {
            return new KeyPair(
                    KcpSigner.decodePublicKey(Files.readString(pub).trim()),
                    KcpSigner.decodePrivateKey(Files.readString(priv).trim()));
        }
        KeyPair kp = KcpSigner.generateKeyPair();
        Files.writeString(pub, KcpSigner.encodePublicKey(kp.getPublic()));
        Files.writeString(priv, KcpSigner.encodePrivateKey(kp.getPrivate()));
        restrictPermissions(priv);
        return kp;
    }

    /** Loads all public keys in the store as a keyId → key allowlist. */
    public Map<String, PublicKey> loadAllowlist() {
        Map<String, PublicKey> allowlist = new HashMap<>();
        if (!Files.isDirectory(dir)) return allowlist;
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".pub")).forEach(p -> {
                String name = p.getFileName().toString();
                String kid = name.substring(0, name.length() - ".pub".length());
                try {
                    allowlist.put(kid, KcpSigner.decodePublicKey(Files.readString(p).trim()));
                } catch (Exception ignored) {
                    // skip unreadable/corrupt key
                }
            });
        } catch (IOException ignored) {
            // empty allowlist
        }
        return allowlist;
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // non-POSIX filesystem — best effort
        }
    }
}
