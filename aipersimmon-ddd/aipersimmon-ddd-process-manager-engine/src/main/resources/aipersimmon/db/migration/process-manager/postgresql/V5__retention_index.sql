-- Retention scans the instance table by (lifecycle, updated_at): finished instances whose retention
-- has elapsed and which hold nothing still owed. Without this index that predicate is a full scan
-- of every instance ever run — which is precisely the table the purge exists to keep from growing,
-- so the scan would get slower exactly as the need for it grew.
CREATE INDEX IF NOT EXISTS idx_process_instance_retention
    ON aipersimmon_process_instance (lifecycle, updated_at);
