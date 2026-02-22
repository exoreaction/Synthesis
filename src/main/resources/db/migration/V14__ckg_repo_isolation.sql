-- V14__ckg_repo_isolation.sql
-- CKG repo isolation: add repo_name column to code_dependencies, module_profiles,
-- and code_quality_gaps so that package identity becomes (workspace_path, repo_name, package_name).
-- This prevents false-positive cycle detection and inflated metrics in multi-repo workspaces
-- where different repos share the same Java package namespace.

-- 1. code_dependencies: add repo_name column (simple ALTER TABLE since no UNIQUE constraint change needed)
ALTER TABLE code_dependencies ADD COLUMN repo_name TEXT NOT NULL DEFAULT '';

-- Update the UNIQUE constraint by recreating the table
-- Old UNIQUE: (workspace_path, source_file, target_class, target_package)
-- source_file already contains repo path prefix, so the UNIQUE is fine for code_dependencies.
-- But we add an index for repo_name filtering.
CREATE INDEX IF NOT EXISTS idx_cd_repo ON code_dependencies(workspace_path, repo_name);

-- 2. module_profiles: recreate to update UNIQUE constraint to include repo_name
--    Old UNIQUE: (workspace_path, module_path)
--    New UNIQUE: (workspace_path, repo_name, module_path)
ALTER TABLE module_profiles RENAME TO module_profiles_old;

CREATE TABLE module_profiles (
    workspace_path TEXT NOT NULL,
    repo_name TEXT NOT NULL DEFAULT '',
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
    UNIQUE(workspace_path, repo_name, module_path)
);

INSERT INTO module_profiles (
    workspace_path, repo_name, module_path, package_name, inferred_purpose,
    domain_concepts_json, public_classes, public_interfaces, exported_types_json,
    fan_in, fan_out, instability, total_files, commits_last_30_days,
    confidence, last_computed
)
SELECT
    workspace_path, '', module_path, package_name, inferred_purpose,
    domain_concepts_json, public_classes, public_interfaces, exported_types_json,
    fan_in, fan_out, instability, total_files, commits_last_30_days,
    confidence, last_computed
FROM module_profiles_old;

DROP TABLE module_profiles_old;

CREATE INDEX IF NOT EXISTS idx_mp_workspace ON module_profiles(workspace_path);
CREATE INDEX IF NOT EXISTS idx_mp_instability ON module_profiles(instability);
CREATE INDEX IF NOT EXISTS idx_mp_repo ON module_profiles(workspace_path, repo_name);

-- 3. code_quality_gaps: add repo_name column
--    Old UNIQUE: (workspace_path, module_path, gap_type, file_path)
--    We add repo_name but keep the old UNIQUE (module_path already scoped by repo via its content).
--    Recreate to include repo_name in the UNIQUE constraint for correctness.
ALTER TABLE code_quality_gaps RENAME TO code_quality_gaps_old;

CREATE TABLE code_quality_gaps (
    workspace_path TEXT NOT NULL,
    repo_name TEXT NOT NULL DEFAULT '',
    module_path TEXT NOT NULL,
    gap_type TEXT NOT NULL,
    description TEXT NOT NULL,
    severity TEXT NOT NULL,
    file_path TEXT,
    suggestion TEXT,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, repo_name, module_path, gap_type, file_path)
);

INSERT INTO code_quality_gaps (
    workspace_path, repo_name, module_path, gap_type, description,
    severity, file_path, suggestion, last_computed
)
SELECT
    workspace_path, '', module_path, gap_type, description,
    severity, file_path, suggestion, last_computed
FROM code_quality_gaps_old;

DROP TABLE code_quality_gaps_old;

CREATE INDEX IF NOT EXISTS idx_cqg_workspace ON code_quality_gaps(workspace_path);
CREATE INDEX IF NOT EXISTS idx_cqg_type ON code_quality_gaps(gap_type);
CREATE INDEX IF NOT EXISTS idx_cqg_severity ON code_quality_gaps(severity);
CREATE INDEX IF NOT EXISTS idx_cqg_repo ON code_quality_gaps(workspace_path, repo_name);
