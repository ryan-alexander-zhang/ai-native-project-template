-- Retention scans the instance table by (lifecycle, updated_at): finished instances whose retention
-- has elapsed and which hold nothing still owed. Without this index that predicate is a full scan
-- of every instance ever run — which is precisely the table the purge exists to keep from growing,
-- so the scan would get slower exactly as the need for it grew.
--
-- MySQL supports neither CREATE INDEX IF NOT EXISTS nor ADD INDEX IF NOT EXISTS, so this statement
-- is unguarded and Flyway's version ledger is what makes it run once.
ALTER TABLE aipersimmon_process_instance
    ADD INDEX idx_process_instance_retention (lifecycle, updated_at);
