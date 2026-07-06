package io.exoreaction.synthesis.kcp;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Ed25519 tamper-evidence for KCP manifests (issue #360, Phase 7).
 *
 * <p>Produces a detached JWS-style signature (RFC 8037 EdDSA over the JWS signing
 * input) stored alongside the manifest as {@code knowledge.yaml.sig}. Sign and
 * verify are self-consistent: a Synthesis-signed manifest verifies as
 * {@code TRUSTED} when its key is allowlisted, and any post-signing edit flips it
 * to {@code FAILED} — the signal behind health check K005.
 *
 * <p>Keys are Ed25519; public keys are exchanged as base64 of their X.509 encoding.
 * Private keys never leave the caller and are never written into a manifest, log,
 * or export.
 *
 * <p>Trust tiers mirror the spec's {@code kcp render} ladder:
 * {@code unsigned < known < trusted}, with {@code failed} for a present-but-invalid
 * signature.
 */
public final class KcpSigner {

    /** Protected JWS header for EdDSA (kid filled per-key). */
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public enum TrustTier { TRUSTED, KNOWN, UNSIGNED, FAILED }

    private KcpSigner() {
    }

    /** Generates a fresh Ed25519 key pair. */
    public static KeyPair generateKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** Base64 (standard) of a public key's X.509 encoding — safe to publish/allowlist. */
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Base64 (standard) of a private key's PKCS#8 encoding — secret; never emit to manifests. */
    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64X509) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64X509);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    public static PrivateKey decodePrivateKey(String base64Pkcs8) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64Pkcs8);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * Signs {@code manifestBytes}, returning a compact detached JWS
     * ({@code base64url(header)..base64url(signature)}).
     */
    public static String sign(byte[] manifestBytes, PrivateKey key, String keyId) throws Exception {
        String header = "{\"alg\":\"EdDSA\",\"kid\":\"" + keyId.replace("\"", "") + "\"}";
        String encodedHeader = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        byte[] signingInput = signingInput(encodedHeader, manifestBytes);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(signingInput);
        String encodedSig = B64.encodeToString(sig.sign());
        return encodedHeader + ".." + encodedSig;   // detached: empty payload segment
    }

    /**
     * Classifies a manifest's trust tier given its detached JWS ({@code null} =
     * no signature) and an allowlist of keyId → public key.
     */
    public static TrustTier verify(byte[] manifestBytes, String detachedJws,
                                   Map<String, PublicKey> allowlist) {
        if (detachedJws == null || detachedJws.isBlank()) return TrustTier.UNSIGNED;
        try {
            String[] parts = detachedJws.trim().split("\\.");
            // compact detached form: header .. signature → parts = [header, "", signature]
            if (parts.length != 3 || !parts[1].isEmpty()) return TrustTier.FAILED;
            String headerJson = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
            String keyId = extractKid(headerJson);
            byte[] signatureBytes = B64D.decode(parts[2]);
            byte[] signingInput = signingInput(parts[0], manifestBytes);

            PublicKey allowlisted = keyId != null ? allowlist.get(keyId) : null;
            // Verify against the allowlisted key when known; else against any allowlisted key.
            if (allowlisted != null) {
                return check(signingInput, signatureBytes, allowlisted)
                        ? TrustTier.TRUSTED : TrustTier.FAILED;
            }
            for (PublicKey candidate : allowlist.values()) {
                if (check(signingInput, signatureBytes, candidate)) {
                    // Valid signature but kid not in the allowlist → known, not trusted.
                    return TrustTier.KNOWN;
                }
            }
            return TrustTier.FAILED;
        } catch (Exception e) {
            return TrustTier.FAILED;
        }
    }

    // -----------------------------------------------------------------------

    private static boolean check(byte[] signingInput, byte[] signatureBytes, PublicKey key)
            throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(key);
        sig.update(signingInput);
        return sig.verify(signatureBytes);
    }

    /** JWS signing input: ASCII(base64url(header)) || '.' || base64url(payload). */
    private static byte[] signingInput(String encodedHeader, byte[] payload) {
        String encodedPayload = B64.encodeToString(payload);
        return (encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII);
    }

    private static String extractKid(String headerJson) {
        var m = java.util.regex.Pattern.compile("\"kid\"\\s*:\\s*\"([^\"]+)\"").matcher(headerJson);
        return m.find() ? m.group(1) : null;
    }
}
