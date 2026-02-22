-- V13__code_knowledge_graph.sql
-- CKG Phase 1: Persistent code knowledge graph tables.
-- Stores class-level dependency edges, module profiles, cross-format links,
-- and code quality gaps extracted by CodeGraphExtractor.

-- Class-level dependency edges (import, extends, implements, annotation)
CREATE TABLE IF NOT EXISTS code_dependencies (
    workspace_path TEXT NOT NULL,
    source_file TEXT NOT NULL,
    source_class TEXT NOT NULL,
    source_package TEXT NOT NULL,
    target_file TEXT,
    target_class TEXT NOT NULL,
    target_package TEXT NOT NULL,
    dependency_type TEXT NOT NULL,
    is_external INTEGER DEFAULT 0,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, source_file, target_class, target_package)
);

CREATE INDEX IF NOT EXISTS idx_cd_workspace ON code_dependencies(workspace_path);
CREATE INDEX IF NOT EXISTS idx_cd_source ON code_dependencies(workspace_path, source_file);
CREATE INDEX IF NOT EXISTS idx_cd_target ON code_dependencies(workspace_path, target_class, target_package);
CREATE INDEX IF NOT EXISTS idx_cd_source_pkg ON code_dependencies(workspace_path, source_package);
CREATE INDEX IF NOT EXISTS idx_cd_target_pkg ON code_dependencies(workspace_path, target_package);

-- Package-level aggregated module profiles
CREATE TABLE IF NOT EXISTS module_profiles (
    workspace_path TEXT NOT NULL,
    module_path TEXT NOT NULL,
    package_name TEXT,
    inferred_purpose TEXT,
    domain_concepts_json TEXT,
    public_classes INTEGER DEFAULT 0,
    public_interfaces INTEGER DEFAULT 0,
    exported_types_json TEXT,
    fan_in INTEGER DEFAULT 0,
    fan_out INTEGER DEFAULT 0,
    instability REAL DEFAULT 0.5,
    total_files INTEGER DEFAULT 0,
    commits_last_30_days INTEGER DEFAULT 0,
    confidence REAL DEFAULT 0.0,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, module_path)
);

CREATE INDEX IF NOT EXISTS idx_mp_workspace ON module_profiles(workspace_path);
CREATE INDEX IF NOT EXISTS idx_mp_instability ON module_profiles(instability);

-- Cross-format links (SQL->Java, YAML->Java, doc->code)
CREATE TABLE IF NOT EXISTS cross_format_links (
    workspace_path TEXT NOT NULL,
    source_file TEXT NOT NULL,
    target_file TEXT NOT NULL,
    link_type TEXT NOT NULL,
    entity_name TEXT,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, source_file, target_file, entity_name)
);

CREATE INDEX IF NOT EXISTS idx_cfl_workspace ON cross_format_links(workspace_path);
CREATE INDEX IF NOT EXISTS idx_cfl_source ON cross_format_links(workspace_path, source_file);
CREATE INDEX IF NOT EXISTS idx_cfl_target ON cross_format_links(workspace_path, target_file);

-- Detected code quality gaps (missing tests, interfaces, docs)
CREATE TABLE IF NOT EXISTS code_quality_gaps (
    workspace_path TEXT NOT NULL,
    module_path TEXT NOT NULL,
    gap_type TEXT NOT NULL,
    description TEXT NOT NULL,
    severity TEXT NOT NULL,
    file_path TEXT,
    suggestion TEXT,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, module_path, gap_type, file_path)
);

CREATE INDEX IF NOT EXISTS idx_cqg_workspace ON code_quality_gaps(workspace_path);
CREATE INDEX IF NOT EXISTS idx_cqg_type ON code_quality_gaps(gap_type);
CREATE INDEX IF NOT EXISTS idx_cqg_severity ON code_quality_gaps(severity);
