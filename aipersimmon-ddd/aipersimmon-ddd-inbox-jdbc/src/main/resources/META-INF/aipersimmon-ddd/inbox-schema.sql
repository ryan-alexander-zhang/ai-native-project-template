-- Reference DDL for the aipersimmon-ddd-inbox-jdbc inbox table.
-- This file is NOT run automatically; create the table yourself (for example via
-- Flyway or Liquibase) before using the inbox. Adjust types per database
-- (for PostgreSQL use TIMESTAMPTZ instead of TIMESTAMP).
-- The primary key is (consumer, message_key): the consumer column scopes dedup to
-- one consuming application, so several services sharing this table do not suppress
-- one another's processing of the same producer-assigned message id.
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (consumer, message_key)
);
-- Speeds up the retention cleanup (DELETE ... WHERE processed_at < ?). Drop the
-- IF NOT EXISTS on databases that do not support it on CREATE INDEX (e.g. MySQL).
CREATE INDEX IF NOT EXISTS idx_aipersimmon_inbox_processed_at
    ON aipersimmon_inbox (processed_at);
