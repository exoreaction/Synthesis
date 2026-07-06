package io.exoreaction.synthesis.kcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Ed25519 tamper-evidence for KCP manifests, interoperable with kcp-agent
 * (issue #360 Phase 7 + kcp-agent interop follow-up).
 *
 * <p>The signature is a raw Ed25519 signature over the <em>exact manifest bytes</em>
 * — the format kcp-agent's {@code verify.js} checks
 * ({@code webcrypto.subtle.verify("Ed25519", key, sig, manifestText)}). It is
 * carried two ways so any KCP consumer can find it:
 * <ul>
 *   <li>a top-level {@code signing:} block written into the manifest
 *       ({@code scheme}, {@code key_id}, {@code public_key}, {@code signature}
 *       pointing at the detached file), and</li>
 *   <li>a detached {@code knowledge.yaml.sig} holding the Cantara envelope
 *       {@code {algorithm, key_id, public_key, signature}}.</li>
 * </ul>
 *
 * <p>Public keys are exchanged as base64 X.509/SPKI DER (a form kcp-agent accepts);
 * private keys never leave the caller and are never written into a manifest, log,
 * or export. Trust tiers mirror kcp-agent: {@code unsigned < known < trusted}, with
 * {@code failed} for a present-but-invalid signature.
 */
public final class KcpSigner {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum TrustTier { TRUSTED, KNOWN, UNSIGNED, FAILED }

    private KcpSigner() {
    }

    // -----------------------------------------------------------------------
    // Keys
    // -----------------------------------------------------------------------

    public static KeyPair generateKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** Base64 (standard) of a public key's X.509/SPKI encoding — safe to publish. */
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Base64 (standard) of a private key's PKCS#8 encoding — secret; never emit. */
    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64X509) throws Exception {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64X509)));
    }

    public static PrivateKey decodePrivateKey(String base64Pkcs8) throws Exception {
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8)));
    }

    // -----------------------------------------------------------------------
    // Raw signature over exact bytes (kcp-agent contract)
    // -----------------------------------------------------------------------

    /** Raw Ed25519 signature over {@code manifestBytes}, base64 (standard). */
    public static String signBytes(byte[] manifestBytes, PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(manifestBytes);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /** The Cantara detached-signature envelope for a {@code .sig} file. */
    public static String buildEnvelope(String signatureB64, String publicKeyB64, String keyId) {
        ObjectNode env = JSON.createObjectNode();
        env.put("algorithm", "ed25519");
        env.put("key_id", keyId);
        env.put("public_key", publicKeyB64);
        env.put("signature", signatureB64);
        return env.toPrettyString() + "\n";
    }

    // -----------------------------------------------------------------------
    // Sign a manifest file in place (adds signing block, writes .sig)
    // -----------------------------------------------------------------------

    /**
     * Signs {@code manifest} in place: writes a top-level {@code signing:} block
     * referencing the sibling {@code <name>.sig}, then signs the resulting exact
     * bytes and writes the envelope to that {@code .sig}. Any pre-existing
     * {@code signing:} block is replaced (idempotent re-sign).
     */
    public static void signManifest(Path manifest, KeyPair keyPair, String keyId) throws Exception {
        String sigFileName = manifest.getFileName() + ".sig";
        String publicKeyB64 = encodePublicKey(keyPair.getPublic());
        String body = stripSigningBlock(Files.readString(manifest));
        String withBlock = body
                + (body.endsWith("\n") ? "" : "\n")
                + "signing:\n"
                + "  scheme: ed25519\n"
                + "  key_id: " + keyId + "\n"
                + "  public_key: " + publicKeyB64 + "\n"
                + "  signature: " + sigFileName + "\n";
        Files.writeString(manifest, withBlock);

        String signatureB64 = signBytes(withBlock.getBytes(StandardCharsets.UTF_8),
                keyPair.getPrivate());
        Files.writeString(manifest.resolveSibling(sigFileName),
                buildEnvelope(signatureB64, publicKeyB64, keyId));
    }

    /**
     * Removes a top-level {@code signing:} block (and its indented body) so a
     * manifest can be re-signed without stacking blocks.
     */
    static String stripSigningBlock(String manifest) {
        String[] lines = manifest.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inSigning = false;
        for (String line : lines) {
            if (line.startsWith("signing:")) { inSigning = true; continue; }
            if (inSigning) {
                // block continues while lines are indented (or blank); a new
                // top-level key (non-space, non-comment start) ends it.
                if (line.isEmpty() || line.startsWith(" ") || line.startsWith("\t")) continue;
                inSigning = false;
            }
            out.append(line).append("\n");
        }
        // Trim the trailing newline we always add back, to preserve original shape
        String result = out.toString();
        return manifest.endsWith("\n") ? result : result.replaceAll("\n$", "");
    }

    // -----------------------------------------------------------------------
    // Verification
    // -----------------------------------------------------------------------

    /**
     * Classifies a manifest's trust tier. {@code sigFileContent} is the {@code .sig}
     * body (envelope JSON or raw base64), or null when absent.
     */
    public static TrustTier verify(byte[] manifestBytes, String sigFileContent,
                                   Map<String, PublicKey> allowlist) {
        if (sigFileContent == null || sigFileContent.isBlank()) return TrustTier.UNSIGNED;
        try {
            String signatureB64;
            String keyId = null;
            PublicKey envelopeKey = null;
            String trimmed = sigFileContent.trim();
            if (trimmed.startsWith("{")) {
                JsonNode env = JSON.readTree(trimmed);
                signatureB64 = text(env, "signature");
                keyId = text(env, "key_id");
                String alg = text(env, "algorithm");
                if (alg != null && !alg.matches("(?i)ed25519|eddsa")) return TrustTier.FAILED;
                String pub = text(env, "public_key");
                if (pub != null) envelopeKey = decodePublicKey(pub);
            } else {
                signatureB64 = trimmed;  // raw base64 signature
            }
            if (signatureB64 == null || signatureB64.isBlank()) return TrustTier.FAILED;
            byte[] signatureBytes = Base64.getDecoder().decode(signatureB64);

            // TRUSTED = verifies against a key we allowlist (matched by kid, or by
            // trying the allowlist when the sig carries no/other kid).
            PublicKey byKid = keyId != null ? allowlist.get(keyId) : null;
            if (byKid != null && verifyRaw(manifestBytes, signatureBytes, byKid)) {
                return TrustTier.TRUSTED;
            }
            for (PublicKey candidate : allowlist.values()) {
                if (verifyRaw(manifestBytes, signatureBytes, candidate)) return TrustTier.TRUSTED;
            }
            // Valid against the envelope's own key but not one we allowlist → KNOWN.
            if (envelopeKey != null && verifyRaw(manifestBytes, signatureBytes, envelopeKey)) {
                return TrustTier.KNOWN;
            }
            return TrustTier.FAILED;
        } catch (Exception e) {
            return TrustTier.FAILED;
        }
    }

    /** Ed25519 verify with the same trailing-newline tolerance kcp-agent applies. */
    private static boolean verifyRaw(byte[] manifestBytes, byte[] signatureBytes, PublicKey key)
            throws Exception {
        if (checkOnce(manifestBytes, signatureBytes, key)) return true;
        String text = new String(manifestBytes, StandardCharsets.UTF_8).replaceAll("\n*$", "\n");
        return checkOnce(text.getBytes(StandardCharsets.UTF_8), signatureBytes, key);
    }

    private static boolean checkOnce(byte[] message, byte[] signatureBytes, PublicKey key)
            throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(key);
        sig.update(message);
        return sig.verify(signatureBytes);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }
}
