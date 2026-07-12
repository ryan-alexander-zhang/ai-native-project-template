-- Reference DDL for the aipersimmon-ddd-web-store-jdbc tables.
-- This file is NOT run automatically; create the tables yourself (for example via
-- Flyway or Liquibase) before enabling the JDBC-backed web stores. Adjust types
-- per database (for PostgreSQL use BYTEA for response_body, TEXT for
-- response_headers, TIMESTAMPTZ instead of TIMESTAMP).

CREATE TABLE IF NOT EXISTS aipersimmon_web_idempotency (
    idempotency_key  VARCHAR(255) PRIMARY KEY,
    response_status  INT          NOT NULL,
    response_body    BLOB,
    response_headers CLOB,
    created_at       TIMESTAMP    NOT NULL,
    expires_at       TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS aipersimmon_web_nonce (
    nonce      VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMP    NOT NULL,
    expires_at TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS aipersimmon_web_rate_limit (
    bucket_key   VARCHAR(255) NOT NULL,
    window_start TIMESTAMP    NOT NULL,
    count        BIGINT       NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);
