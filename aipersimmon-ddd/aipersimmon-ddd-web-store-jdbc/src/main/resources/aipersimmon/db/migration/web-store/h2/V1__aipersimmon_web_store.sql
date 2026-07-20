-- Flyway migration (H2) for the aipersimmon-ddd-web-store-jdbc tables (idempotency, nonce,
-- rate-limit). Single source of the web-store schema. Applied automatically by the optional
-- aipersimmon-ddd-flyway starter (history table flyway_schema_history_aipersimmon_web_store),
-- or copy into your own Flyway/Liquibase.
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
