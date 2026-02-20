CREATE TABLE IF NOT EXISTS knowledge_edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_path TEXT NOT NULL,
    source_path TEXT NOT NULL,
    entity_name TEXT,
    coverage_type TEXT DEFAULT 'mentioned',
    skill_modified_at INTEGER,
    source_modified_at INTEGER,
    drift_days INTEGER,
    confidence TEXT DEFAULT 'HIGH',
    last_reconciled_at INTEGER,
    UNIQUE(skill_path, source_path, entity_name)
);

CREATE INDEX IF NOT EXISTS idx_ke_skill_path ON knowledge_edges(skill_path);
CREATE INDEX IF NOT EXISTS idx_ke_source_path ON knowledge_edges(source_path);
CREATE INDEX IF NOT EXISTS idx_ke_confidence ON knowledge_edges(confidence);

