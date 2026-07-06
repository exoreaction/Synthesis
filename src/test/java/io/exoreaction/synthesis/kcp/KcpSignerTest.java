package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpSigner} and {@link KcpTrustStore} — Ed25519 tamper-evidence
 * interoperable with kcp-agent (raw-bytes signature + Cantara envelope).
 */
class KcpSignerTest {

    @TempDir
    Path tempDir;

    private static final byte[] MANIFEST =
            "kcp_version: \"0.25\"\nproject: demo\n".getBytes(StandardCharsets.UTF_8);

    // -----------------------------------------------------------------------
    // Raw signature + envelope (the kcp-agent contract)
    // -----------------------------------------------------------------------

    @Test
    void rawSignatureVerifiesAgainstEnvelope() throws Exception {
        KeyPair kp = KcpSigner.generateKeyPair();
        String sigB64 = KcpSigner.signBytes(MANIFEST, kp.getPrivate());
        String pubB64 = KcpSigner.encodePublicKey(kp.getPublic());
        String envelope = KcpSigner.buildEnvelope(sigB64, pubB64, "k1");

        // Envelope is the Cantara shape kcp-agent parses
        assertTrue(envelope.contains("\"algorithm\" : \"ed25519\""));
        assertTrue(envelope.contains("\"key_id\" : \"k1\""));
        assertTrue(envelope.contains("\"public_key\""));
        assertTrue(envelope.contains("\"signature\""));

        assertEquals(KcpSigner.TrustTier.TRUSTED,
                KcpSigner.verify(MANIFEST, envelope, Map.of("k1", kp.getPublic())));
    }

    @Test
    void noSignatureIsUnsigned() {
        assertEquals(KcpSigner.TrustTier.UNSIGNED, KcpSigner.verify(MANIFEST, null, Map.of()));
        assertEquals(KcpSigner.TrustTier.UNSIGNED, KcpSigner.verify(MANIFEST, "  ", Map.of()));
    }

    @Test
    void tamperedManifestFails() throws Exception {
        KeyPair kp = KcpSigner.generateKeyPair();
        String envelope = KcpSigner.buildEnvelope(
                KcpSigner.signBytes(MANIFEST, kp.getPrivate()),
                KcpSigner.encodePublicKey(kp.getPublic()), "k1");
        byte[] tampered = "kcp_version: \"0.25\"\nproject: EVIL\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(KcpSigner.TrustTier.FAILED,
                KcpSigner.verify(tampered, envelope, Map.of("k1", kp.getPublic())));
    }

    @Test
    void validSignatureFromNonAllowlistedKidIsKnown() throws Exception {
        KeyPair signer = KcpSigner.generateKeyPair();
        String envelope = KcpSigner.buildEnvelope(
                KcpSigner.signBytes(MANIFEST, signer.getPrivate()),
                KcpSigner.encodePublicKey(signer.getPublic()), "signer-kid");
        // kid not in allowlist, but the envelope carries a valid public key → KNOWN
        assertEquals(KcpSigner.TrustTier.KNOWN, KcpSigner.verify(MANIFEST, envelope, Map.of()));
    }

    @Test
    void rawBase64SignatureFileAlsoVerifies() throws Exception {
        KeyPair kp = KcpSigner.generateKeyPair();
        String sigB64 = KcpSigner.signBytes(MANIFEST, kp.getPrivate());
        // A bare base64 signature (no envelope) verifies against an allowlisted key
        assertEquals(KcpSigner.TrustTier.TRUSTED,
                KcpSigner.verify(MANIFEST, sigB64, Map.of("k1", kp.getPublic())));
    }

    @Test
    void garbageSignatureFails() {
        assertEquals(KcpSigner.TrustTier.FAILED, KcpSigner.verify(MANIFEST, "not-base64!!", Map.of()));
        assertEquals(KcpSigner.TrustTier.FAILED,
                KcpSigner.verify(MANIFEST, "{\"algorithm\":\"rsa\",\"signature\":\"x\"}", Map.of()),
                "Non-ed25519 algorithm is rejected");
    }

    // -----------------------------------------------------------------------
    // signManifest: in-place signing block + detached .sig, self-verifying
    // -----------------------------------------------------------------------

    @Test
    void signManifestWritesBlockAndSigAndSelfVerifies() throws Exception {
        Path manifest = tempDir.resolve("knowledge.yaml");
        Files.writeString(manifest, "kcp_version: \"0.25\"\nproject: demo\nunits: []\n");
        KeyPair kp = KcpSigner.generateKeyPair();

        KcpSigner.signManifest(manifest, kp, "synthesis-local");

        String signed = Files.readString(manifest);
        assertTrue(signed.contains("signing:"), "signing block written into manifest");
        assertTrue(signed.contains("scheme: ed25519"));
        assertTrue(signed.contains("key_id: synthesis-local"));
        assertTrue(signed.contains("signature: knowledge.yaml.sig"), "block points at the .sig");

        Path sig = tempDir.resolve("knowledge.yaml.sig");
        assertTrue(Files.exists(sig), "detached envelope written");

        // Verifying the on-disk bytes against the on-disk envelope → TRUSTED
        assertEquals(KcpSigner.TrustTier.TRUSTED, KcpSigner.verify(
                Files.readAllBytes(manifest), Files.readString(sig),
                Map.of("synthesis-local", kp.getPublic())));
    }

    @Test
    void reSigningReplacesTheSigningBlockNotStacksIt() throws Exception {
        Path manifest = tempDir.resolve("knowledge.yaml");
        Files.writeString(manifest, "kcp_version: \"0.25\"\nproject: demo\n");
        KeyPair kp = KcpSigner.generateKeyPair();

        KcpSigner.signManifest(manifest, kp, "k1");
        KcpSigner.signManifest(manifest, kp, "k1");   // re-sign

        long blocks = Files.readString(manifest).lines().filter(l -> l.equals("signing:")).count();
        assertEquals(1, blocks, "Exactly one signing block after re-sign");
        assertEquals(KcpSigner.TrustTier.TRUSTED, KcpSigner.verify(
                Files.readAllBytes(manifest), Files.readString(tempDir.resolve("knowledge.yaml.sig")),
                Map.of("k1", kp.getPublic())));
    }

    @Test
    void stripSigningBlockRemovesOnlyThatBlock() {
        String m = "kcp_version: \"0.25\"\nproject: demo\n"
                + "signing:\n  scheme: ed25519\n  key_id: k1\n"
                + "units:\n  - id: x\n";
        String stripped = KcpSigner.stripSigningBlock(m);
        assertFalse(stripped.contains("signing:"));
        assertTrue(stripped.contains("project: demo"));
        assertTrue(stripped.contains("units:"), "content after the block survives");
        assertTrue(stripped.contains("- id: x"));
    }

    // -----------------------------------------------------------------------
    // Trust store
    // -----------------------------------------------------------------------

    @Test
    void trustStorePersistsAndReloadsKeys() throws Exception {
        var store = new KcpTrustStore(tempDir.resolve("keys"));
        KeyPair created = store.loadOrCreateSigningKey("synthesis-local");
        KeyPair reloaded = store.loadOrCreateSigningKey("synthesis-local");
        assertArrayEquals(created.getPublic().getEncoded(), reloaded.getPublic().getEncoded());

        var allowlist = store.loadAllowlist();
        assertTrue(allowlist.containsKey("synthesis-local"));
        String envelope = KcpSigner.buildEnvelope(
                KcpSigner.signBytes(MANIFEST, created.getPrivate()),
                KcpSigner.encodePublicKey(created.getPublic()), "synthesis-local");
        assertEquals(KcpSigner.TrustTier.TRUSTED, KcpSigner.verify(MANIFEST, envelope, allowlist));
    }

    @Test
    void privateKeyNeverAppearsInPublicArtifacts() throws Exception {
        var store = new KcpTrustStore(tempDir.resolve("keys"));
        KeyPair kp = store.loadOrCreateSigningKey("k1");
        String sigB64 = KcpSigner.signBytes(MANIFEST, kp.getPrivate());
        String pubB64 = KcpSigner.encodePublicKey(kp.getPublic());
        String envelope = KcpSigner.buildEnvelope(sigB64, pubB64, "k1");
        String privB64 = KcpSigner.encodePrivateKey(kp.getPrivate());
        assertFalse(envelope.contains(privB64), "envelope must not leak the private key");
        assertFalse(pubB64.contains(privB64));
    }
}
