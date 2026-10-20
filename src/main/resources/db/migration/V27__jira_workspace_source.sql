-- Jira workspace source: issue index, issue links, and sync state (Phase 1)

CREATE TABLE IF NOT EXISTS jira_issues (
    issue_key      TEXT NOT NULL,
    workspace_name TEXT NOT NULL,
    issue_id       TEXT,
    project_key    TEXT NOT NULL,
    summary        TEXT NOT NULL,
    status         TEXT,
    virtual_path   TEXT NOT NULL,
    last_updated_at INTEGER NOT NULL,
    last_synced_at  INTEGER NOT NULL,
    content_hash   TEXT,
    issue_url      TEXT,
    PRIMARY KEY (issue_key, workspace_name)
);

CREATE INDEX IF NOT EXISTS idx_jira_issues_workspace
    ON jira_issues(workspace_name);
CREATE INDEX IF NOT EXISTS idx_jira_issues_project
    ON jira_issues(project_key, workspace_name);
CREATE INDEX IF NOT EXISTS idx_jira_issues_virtual_path
    ON jira_issues(virtual_path, workspace_name);
CREATE INDEX IF NOT EXISTS idx_jira_issues_last_updated
    ON jira_issues(last_updated_at);

-- Issue links are first-class structured relationships (blocks/relates-to/duplicates),
-- unlike anything in notion_pages or a Confluence page tree — persisted as their own
-- table per RFC #542 so a future consumer (RFC #543) can read them as trusted,
-- already-resolved edges rather than inferring them.
CREATE TABLE IF NOT EXISTS jira_issue_links (
    workspace_name    TEXT NOT NULL,
    source_issue_key  TEXT NOT NULL,
    target_issue_key  TEXT NOT NULL,
    link_type         TEXT NOT NULL,
    direction         TEXT NOT NULL,
    target_summary    TEXT,
    PRIMARY KEY (workspace_name, source_issue_key, target_issue_key, link_type, direction)
);

CREATE INDEX IF NOT EXISTS idx_jira_issue_links_source
    ON jira_issue_links(workspace_name, source_issue_key);
CREATE INDEX IF NOT EXISTS idx_jira_issue_links_target
    ON jira_issue_links(workspace_name, target_issue_key);

CREATE TABLE IF NOT EXISTS jira_sync_state (
    workspace_name   TEXT PRIMARY KEY,
    jql_filter       TEXT NOT NULL,
    last_sync_time   INTEGER NOT NULL,
    total_issues     INTEGER NOT NULL DEFAULT 0,
    last_sync_status TEXT NOT NULL DEFAULT 'ok',
    error_message    TEXT
);
