-- KCP (Knowledge Context Protocol) manifest tables (Phase 3).
--
-- kcp_manifests  : one row per knowledge.yaml file discovered during scan/maintain
-- kcp_units      : one row per unit entry within a manifest
-- kcp_relationships : one row per declared relationship between units

CREATE TABLE IF NOT EXISTS kcp_manifests (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path      TEXT    NOT NULL,
    file_path           TEXT    NOT NULL,   -- absolute path to knowledge.yaml
    project             TEXT,               -- 'project' or 'id' at manifest root
    kcp_version         TEXT,               -- kcp_version field value
    unit_count          INTEGER NOT NULL DEFAULT 0,
    relationship_count  INTEGER NOT NULL DEFAULT 0,
    last_computed       INTEGER NOT NULL,
    UNIQUE(workspace_path, file_path)
);

CREATE INDEX IF NOT EXISTS idx_kcp_manifests_workspace
    ON kcp_manifests(workspace_path);
CREATE INDEX IF NOT EXISTS idx_kcp_manifests_project
    ON kcp_manifests(project);

-- ----------------------------------------------------------------

CREATE TABLE IF NOT EXISTS kcp_units (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path  TEXT NOT NULL,
    manifest_file   TEXT NOT NULL,  -- FK → kcp_manifests.file_path
    unit_id         TEXT NOT NULL,
    path            TEXT,           -- file path this unit refers to (may be relative)
    intent          TEXT,
    scope           TEXT,
    audience_json   TEXT,           -- JSON array, e.g. ["developer","agent"]
    triggers_json   TEXT,           -- JSON array, e.g. ["api","rest"]
    hints_json      TEXT,           -- JSON object, e.g. {"summary_of":"agents"}
    last_computed   INTEGER NOT NULL,
    UNIQUE(workspace_path, manifest_file, unit_id)
);

CREATE INDEX IF NOT EXISTS idx_kcp_units_workspace
    ON kcp_units(workspace_path);
CREATE INDEX IF NOT EXISTS idx_kcp_units_manifest
    ON kcp_units(workspace_path, manifest_file);
CREATE INDEX IF NOT EXISTS idx_kcp_units_unit_id
    ON kcp_units(unit_id);
CREATE INDEX IF NOT EXISTS idx_kcp_units_path
    ON kcp_units(path);

-- ----------------------------------------------------------------

CREATE TABLE IF NOT EXISTS kcp_relationships (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path  TEXT NOT NULL,
    manifest_file   TEXT NOT NULL,  -- FK → kcp_manifests.file_path
    from_unit       TEXT NOT NULL,
    to_unit         TEXT NOT NULL,
    type            TEXT,           -- e.g. "context", "extends", "summary_of"
    last_computed   INTEGER NOT NULL,
    UNIQUE(workspace_path, manifest_file, from_unit, to_unit, type)
);

CREATE INDEX IF NOT EXISTS idx_kcp_rels_workspace
    ON kcp_relationships(workspace_path);
CREATE INDEX IF NOT EXISTS idx_kcp_rels_manifest
    ON kcp_relationships(workspace_path, manifest_file);
CREATE INDEX IF NOT EXISTS idx_kcp_rels_from
    ON kcp_relationships(workspace_path, from_unit);
CREATE INDEX IF NOT EXISTS idx_kcp_rels_to
    ON kcp_relationships(workspace_path, to_unit);
