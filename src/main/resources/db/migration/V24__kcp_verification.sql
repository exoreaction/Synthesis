-- KCP verification results (issue #356): Synthesis's observations about a
-- manifest's declarations, stored BESIDE the declaration rather than
-- overwriting it. Re-scans re-ingest the manifest's own declared
-- verification_status into kcp_units; this table records what
-- `synthesis kcp verify` actually observed, and survives re-scans.

CREATE TABLE IF NOT EXISTS kcp_verification (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path     TEXT    NOT NULL,
    manifest_file      TEXT    NOT NULL,   -- FK → kcp_manifests.file_path
    unit_id            TEXT    NOT NULL,
    verdict            TEXT    NOT NULL,   -- observed | stale | contradicted
    findings_json      TEXT,               -- JSON array of {checkId, severity, detail}
    verified_at        INTEGER NOT NULL,   -- epoch millis of the verify run
    synthesis_version  TEXT,
    UNIQUE(workspace_path, manifest_file, unit_id)
);

CREATE INDEX IF NOT EXISTS idx_kcp_verification_workspace
    ON kcp_verification(workspace_path);
CREATE INDEX IF NOT EXISTS idx_kcp_verification_manifest
    ON kcp_verification(workspace_path, manifest_file);
