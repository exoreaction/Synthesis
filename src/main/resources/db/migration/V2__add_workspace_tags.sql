-- Add workspace tags for better categorization
-- Version: 2.0
-- Created: 2026-02-15

-- Add tags column to track workspace categories (source-code, documents, mixed)
ALTER TABLE metrics ADD COLUMN workspace_tag TEXT;

-- Create index for efficient filtering by workspace type
CREATE INDEX IF NOT EXISTS idx_workspace_tag ON metrics(workspace_tag);

-- Update metadata to track new feature
INSERT OR REPLACE INTO metadata (key, value) VALUES ('feature_workspace_tags', 'enabled');
