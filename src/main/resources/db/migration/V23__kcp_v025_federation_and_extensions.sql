-- KCP v0.25 ingestion (issue #355): federation entries and forward-compatible
-- extension blocks.
--
-- kcp_federation stores the root `manifests[]` block (v0.9 federation, v0.21
-- source-level temporal, v0.24 org-federation context/agent_identity).
--
-- The *_extensions_json columns capture root- and unit-level blocks the
-- structured schema does not model (auth, payment, rate_limits, trust,
-- freshness_policy, composition, visibility, authority, delegation,
-- compliance, ...) as raw JSON so future spec waves degrade gracefully
-- instead of being dropped at ingestion.

-- ---- kcp_manifests: forward-compatible root blocks ----
ALTER TABLE kcp_manifests ADD COLUMN root_extensions_json TEXT;  -- JSON object of unmapped root blocks

-- ---- kcp_units: forward-compatible unit blocks ----
ALTER TABLE kcp_units ADD COLUMN extensions_json TEXT;           -- JSON object of unmapped unit blocks

-- ---- kcp_federation: root manifests[] entries ----
CREATE TABLE IF NOT EXISTS kcp_federation (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path      TEXT    NOT NULL,
    manifest_file       TEXT    NOT NULL,   -- FK → kcp_manifests.file_path
    entry_id            TEXT,               -- manifests[].id
    url                 TEXT,               -- manifests[].url
    label               TEXT,               -- manifests[].label
    relationship        TEXT,               -- e.g. "governs", "extends"
    update_frequency    TEXT,
    local_mirror        TEXT,
    context             TEXT,               -- v0.24: dev|test|staging|prod
    version_pin         TEXT,               -- v0.10 federation version pinning
    version_policy      TEXT,               -- exact|minimum|compatible
    valid_from          TEXT,               -- v0.21 manifests[].temporal
    valid_until         TEXT,
    superseded_by       TEXT,
    agent_identity_json TEXT,               -- v0.24: raw JSON of agent_identity
    extensions_json     TEXT,               -- unmapped entry keys as raw JSON
    last_computed       INTEGER NOT NULL,
    UNIQUE(workspace_path, manifest_file, entry_id, url)
);

CREATE INDEX IF NOT EXISTS idx_kcp_federation_workspace
    ON kcp_federation(workspace_path);
CREATE INDEX IF NOT EXISTS idx_kcp_federation_manifest
    ON kcp_federation(workspace_path, manifest_file);
