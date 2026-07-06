package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpSigner} and {@link KcpTrustStore} — Ed25519 tamper-evidence
 * (issue #360). Sign/verify are self-consistent: TRUSTED when allowlisted, FAILED
 * on any edit.
 */
class KcpSignerTest {

    @TempDir
    Path tempDir;

    private static final byte[] MANIFEST =
            "kcp_version: \"0.25\"\nproject: demo\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void signedManifestVerifiesAsTrustedWhenKeyAllowlisted() throws Exception {
        KeyPair kp = KcpSigner.generateKeyPair();
        String sig = KcpSigner.sign(MANIFEST, kp.getPrivate(), "k1");
        var allowlist = Map.of("k1", kp.getPublic());
        assertEquals(KcpSigner.TrustTier.TRUSTED, KcpSigner.verify(MANIFEST, sig, allowlist));
    }

    @Test
    void noSignatureIsUnsigned() {
        assertEquals(KcpSigner.TrustTier.UNSIGNED,
                KcpSigner.verify(MANIFEST, null, Map.of()));
        assertEquals(KcpSigner.TrustTier.UNSIGNED,
                KcpSigner.verify(MANIFEST, "   ", Map.of()));
    }

    @Test
    void tamperedManifestFails() throws Exception {
        KeyPair kp = KcpSigner.generateKeyPair();
        String sig = KcpSigner.sign(MANIFEST, kp.getPrivate(), "k1");
        byte[] tampered = "kcp_version: \"0.25\"\nproject: EVIL\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(KcpSigner.TrustTier.FAILED,
                KcpSigner.verify(tampered, sig, Map.of("k1", kp.getPublic())));
    }

    @Test
    void validSignatureFromUnknownKeyIsKnownNotTrusted() throws Exception {
        KeyPair signer = KcpSigner.generateKeyPair();
        KeyPair other = KcpSigner.generateKeyPair();
        String sig = KcpSigner.sign(MANIFEST, signer.getPrivate(), "signer-kid");
        // Allowlist contains a different kid but happens to also hold the signer's key
        // under a different name → signature validates but kid isn't allowlisted → KNOWN.
        Map<String, PublicKey> allowlist = Map.of("some-other-kid", signer.getPublic());
        assertEquals(KcpSigner.TrustTier.KNOWN, KcpSigner.verify(MANIFEST, sig, allowlist));
    }

    @Test
    void garbageSignatureFails() {
        assertEquals(KcpSigner.TrustTier.FAILED,
                KcpSigner.verify(MANIFEST, "not-a-jws", Map.of()));
        assertEquals(KcpSigner.TrustTier.FAILED,
                KcpSigner.verify(MANIFEST, "aGVhZGVy.cGF5bG9hZA.c2ln", Map.of()),
                "Non-detached (payload present) form is rejected");
    }

    @Test
    void keyEncodingRoundTrips() throws Exception {
        KeyPair kp = KcpSigner.generateKeyPair();
        PublicKey pub = KcpSigner.decodePublicKey(KcpSigner.encodePublicKey(kp.getPublic()));
        var priv = KcpSigner.decodePrivateKey(KcpSigner.encodePrivateKey(kp.getPrivate()));
        String sig = KcpSigner.sign(MANIFEST, priv, "k1");
        assertEquals(KcpSigner.TrustTier.TRUSTED, KcpSigner.verify(MANIFEST, sig, Map.of("k1", pub)));
    }

    // -----------------------------------------------------------------------
    // Trust store
    // -----------------------------------------------------------------------

    @Test
    void trustStorePersistsAndReloadsKeys() throws Exception {
        var store = new KcpTrustStore(tempDir.resolve("keys"));
        KeyPair created = store.loadOrCreateSigningKey("synthesis-local");
        // Second load returns the same key (persisted, not regenerated)
        KeyPair reloaded = store.loadOrCreateSigningKey("synthesis-local");
        assertArrayEquals(created.getPublic().getEncoded(), reloaded.getPublic().getEncoded());

        // Allowlist exposes the public key under its kid
        var allowlist = store.loadAllowlist();
        assertTrue(allowlist.containsKey("synthesis-local"));

        String sig = KcpSigner.sign(MANIFEST, created.getPrivate(), "synthesis-local");
        assertEquals(KcpSigner.TrustTier.TRUSTED, KcpSigner.verify(MANIFEST, sig, allowlist));
    }

    @Test
    void privateKeyNeverAppearsInPublicArtifacts() throws Exception {
        var store = new KcpTrustStore(tempDir.resolve("keys"));
        KeyPair kp = store.loadOrCreateSigningKey("k1");
        String sig = KcpSigner.sign(MANIFEST, kp.getPrivate(), "k1");
        String pubB64 = KcpSigner.encodePublicKey(kp.getPublic());
        String privB64 = KcpSigner.encodePrivateKey(kp.getPrivate());
        // The signature and public encoding must not leak the private key material
        assertFalse(sig.contains(privB64));
        assertFalse(pubB64.contains(privB64));
    }
}
