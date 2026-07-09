-- Reference DDL for the aipersimmon-ddd-inbox-jdbc inbox table.
-- This file is NOT run automatically; create the table yourself (for example via
-- Flyway or Liquibase) before using the inbox. Adjust types per database
-- (for PostgreSQL use TIMESTAMPTZ instead of TIMESTAMP).
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    message_key  VARCHAR(128) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);
