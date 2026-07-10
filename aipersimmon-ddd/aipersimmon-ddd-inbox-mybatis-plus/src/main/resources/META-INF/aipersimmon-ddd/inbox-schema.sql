-- Reference DDL for the aipersimmon-ddd-inbox-mybatis-plus inbox table.
-- This file is NOT run automatically; create the table yourself (for example via
-- Flyway or Liquibase) before using the inbox. Adjust types per database
-- (for PostgreSQL use TIMESTAMPTZ instead of TIMESTAMP). Same schema as the
-- inbox-jdbc starter, so the two are interchangeable.
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    message_key  VARCHAR(128) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);
