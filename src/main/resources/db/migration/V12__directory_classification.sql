-- V12__directory_classification.sql
-- Phase 5: Stores directory classification to gate centroid/wants/health processing.
-- Directories classified as CODE or GENERATED skip semantic processing entirely.

ALTER TABLE directory_centroids ADD COLUMN classification TEXT NOT NULL DEFAULT 'UNKNOWN';

CREATE INDEX IF NOT EXISTS idx_dc_classification ON directory_centroids(classification);
