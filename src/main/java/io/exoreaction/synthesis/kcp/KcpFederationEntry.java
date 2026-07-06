package io.exoreaction.synthesis.kcp;

/**
 * Represents one entry of a KCP manifest's root {@code manifests[]} block —
 * a federated reference to another manifest.
 *
 * <p>Covers the v0.9 federation core fields, v0.10 version pinning,
 * v0.21 source-level temporal validity, and v0.24 org-federation fields
 * ({@code context}, {@code agent_identity}). Entry keys outside this
 * structured set are preserved verbatim in {@code extensionsJson}.
 */
public record KcpFederationEntry(
        /** Entry identifier ({@code manifests[].id}). May be null. */
        String entryId,

        /** URL or path of the federated manifest. May be null. */
        String url,

        /** Human-readable label. May be null. */
        String label,

        /** Relationship to this manifest, e.g. "governs", "extends". May be null. */
        String relationship,

        /** Declared refresh cadence. May be null. */
        String updateFrequency,

        /** Local mirror path for offline resolution. May be null. */
        String localMirror,

        /** v0.24 environment context: dev/test/staging/prod. May be null. */
        String context,

        /** v0.10 pinned version of the federated manifest. May be null. */
        String versionPin,

        /** v0.10 version policy: exact/minimum/compatible. May be null. */
        String versionPolicy,

        /** v0.21 temporal: entry valid from (ISO 8601). May be null. */
        String validFrom,

        /** v0.21 temporal: entry valid until (ISO 8601). May be null. */
        String validUntil,

        /** v0.21 temporal: id/url of the superseding source. May be null. */
        String supersededBy,

        /** v0.24 agent_identity block as raw JSON. May be null. */
        String agentIdentityJson,

        /** Unmapped entry keys preserved as a raw JSON object. May be null. */
        String extensionsJson
) {}
