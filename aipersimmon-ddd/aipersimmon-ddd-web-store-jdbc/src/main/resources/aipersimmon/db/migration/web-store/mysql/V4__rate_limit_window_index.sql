-- The rate-limit sweep (JdbcWebStoreCleanup) finds dead counters by window_start. The primary key
-- starts with bucket_key, so it cannot serve a scan by window alone: without this index the sweep is
-- a full scan of the table it exists to keep from growing. V3 added the equivalent index for the two
-- expires_at tables and left this one out, because there was no sweep yet to need it.
-- MySQL supports neither IF NOT EXISTS here; Flyway's version ledger is what makes it run once.
ALTER TABLE aipersimmon_web_rate_limit ADD INDEX idx_aipersimmon_web_rate_limit_window (window_start);
