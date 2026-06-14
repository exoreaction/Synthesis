-- KCP v0.21 field extensions: temporal validity, content integrity, negative space,
-- content structure, and discovery provenance.
--
-- All new columns are nullable for graceful degradation on manifests that do not
-- declare them. Existing v0.5 manifests continue to work unchanged.

-- ---- kcp_units: temporal validity (RFC-0010, RFC-0020) ----
ALTER TABLE kcp_units ADD COLUMN valid_from TEXT;       -- ISO 8601 date/datetime
ALTER TABLE kcp_units ADD COLUMN valid_until TEXT;      -- ISO 8601 date/datetime
ALTER TABLE kcp_units ADD COLUMN recorded_at TEXT;      -- ISO 8601 date/datetime
ALTER TABLE kcp_units ADD COLUMN superseded_by TEXT;    -- unit ID of replacement

-- ---- kcp_units: content integrity (RFC-0019) ----
ALTER TABLE kcp_units ADD COLUMN content_hash_algorithm TEXT;  -- e.g. "sha256"
ALTER TABLE kcp_units ADD COLUMN content_hash_value TEXT;      -- hex digest

-- ---- kcp_units: negative space (RFC-0015) ----
ALTER TABLE kcp_units ADD COLUMN not_for_json TEXT;     -- JSON array of strings
ALTER TABLE kcp_units ADD COLUMN not_for_strict INTEGER DEFAULT 0;  -- boolean (0/1)

-- ---- kcp_units: content structure (RFC-0016) ----
ALTER TABLE kcp_units ADD COLUMN content_structure_primary TEXT;  -- prose|table|code|list|diagram|reference|mixed
ALTER TABLE kcp_units ADD COLUMN content_structure_density TEXT;  -- sparse|normal|dense

-- ---- kcp_units: discovery provenance (RFC-0012, RFC-0020) ----
ALTER TABLE kcp_units ADD COLUMN verification_status TEXT;  -- rumored|declared|observed|verified
ALTER TABLE kcp_units ADD COLUMN confidence REAL;           -- 0.0-1.0, NULL = not declared
ALTER TABLE kcp_units ADD COLUMN verified_by TEXT;          -- key ID or agent identity
ALTER TABLE kcp_units ADD COLUMN evidence TEXT;             -- URL or path to verification artifact

-- ---- kcp_manifests: signing metadata (RFC-0018) ----
ALTER TABLE kcp_manifests ADD COLUMN signing_algorithm TEXT;   -- e.g. "EdDSA"
ALTER TABLE kcp_manifests ADD COLUMN signing_key_id TEXT;      -- key identifier
ALTER TABLE kcp_manifests ADD COLUMN signature_file TEXT;      -- e.g. "knowledge.yaml.sig"

-- ---- kcp_manifests: root-level discovery (RFC-0012) ----
ALTER TABLE kcp_manifests ADD COLUMN verification_status TEXT;
ALTER TABLE kcp_manifests ADD COLUMN confidence REAL;
ALTER TABLE kcp_manifests ADD COLUMN verified_by TEXT;
ALTER TABLE kcp_manifests ADD COLUMN verified_at TEXT;         -- ISO 8601 datetime

-- ---- kcp_manifests: root-level temporal defaults (RFC-0020) ----
ALTER TABLE kcp_manifests ADD COLUMN valid_from TEXT;
ALTER TABLE kcp_manifests ADD COLUMN valid_until TEXT;

-- ---- kcp_manifests: root-level not_for (RFC-0015, §3.10) ----
ALTER TABLE kcp_manifests ADD COLUMN not_for_json TEXT;

-- ---- kcp_manifests: content structure root defaults (RFC-0016) ----
ALTER TABLE kcp_manifests ADD COLUMN content_structure_primary TEXT;
ALTER TABLE kcp_manifests ADD COLUMN content_structure_density TEXT;

-- ---- Index for temporal filtering (Phase B) ----
CREATE INDEX IF NOT EXISTS idx_kcp_units_valid_from
    ON kcp_units(valid_from);
CREATE INDEX IF NOT EXISTS idx_kcp_units_valid_until
    ON kcp_units(valid_until);
CREATE INDEX IF NOT EXISTS idx_kcp_units_superseded_by
    ON kcp_units(superseded_by);
