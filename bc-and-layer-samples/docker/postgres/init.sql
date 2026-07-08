-- Runs once on first container init (empty data dir), inside DB "samples".
-- One schema per (structure, bounded context) so the three structures never
-- share state. Each app's Flyway creates its own tables inside its schema.
CREATE SCHEMA IF NOT EXISTS s1_ordering;
CREATE SCHEMA IF NOT EXISTS s1_inventory;
CREATE SCHEMA IF NOT EXISTS s2_ordering;
CREATE SCHEMA IF NOT EXISTS s2_inventory;
CREATE SCHEMA IF NOT EXISTS s3_ordering;
CREATE SCHEMA IF NOT EXISTS s3_inventory;
