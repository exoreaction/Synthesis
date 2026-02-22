-- V15__security_findings.sql
-- Security analysis tables for code-graph security feature.
-- Stores security findings (S001-S021), declared dependencies, and attack surface edges.

CREATE TABLE IF NOT EXISTS security_findings (
    workspace_path TEXT NOT NULL,
    signal_id TEXT NOT NULL,
    severity TEXT NOT NULL,
    cwe_id TEXT,
    file_path TEXT NOT NULL,
    line_number INTEGER DEFAULT 0,
    class_name TEXT,
    package_name TEXT,
    description TEXT NOT NULL,
    evidence TEXT,
    suggestion TEXT,
    flow_type TEXT,
    suppressed INTEGER DEFAULT 0,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, signal_id, file_path, line_number)
);

CREATE INDEX IF NOT EXISTS idx_sf_workspace ON security_findings(workspace_path);
CREATE INDEX IF NOT EXISTS idx_sf_severity ON security_findings(severity);
CREATE INDEX IF NOT EXISTS idx_sf_signal ON security_findings(signal_id);

CREATE TABLE IF NOT EXISTS declared_dependencies (
    workspace_path TEXT NOT NULL,
    group_id TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    version TEXT,
    scope TEXT,
    build_file TEXT NOT NULL,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, group_id, artifact_id, build_file)
);

CREATE INDEX IF NOT EXISTS idx_dd_workspace ON declared_dependencies(workspace_path);

CREATE TABLE IF NOT EXISTS attack_surface_edges (
    workspace_path TEXT NOT NULL,
    entry_file TEXT NOT NULL,
    entry_class TEXT NOT NULL,
    sink_file TEXT NOT NULL,
    sink_class TEXT NOT NULL,
    sink_type TEXT NOT NULL,
    hop_count INTEGER DEFAULT 1,
    path_summary TEXT,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, entry_file, sink_file, sink_type)
);

CREATE INDEX IF NOT EXISTS idx_ase_workspace ON attack_surface_edges(workspace_path);
CREATE INDEX IF NOT EXISTS idx_ase_entry ON attack_surface_edges(entry_file);
CREATE INDEX IF NOT EXISTS idx_ase_sink ON attack_surface_edges(sink_file);
