-- Flyway migration (PostgreSQL) for the aipersimmon-ddd-web-store-jdbc tables. Single source of
-- the web-store schema. BYTEA for the response body, TEXT for the (C)LOB headers.
CREATE TABLE IF NOT EXISTS aipersimmon_web_idempotency (
    idempotency_key  VARCHAR(255) PRIMARY KEY,
    response_status  INT          NOT NULL,
    response_body    BYTEA,
    response_headers TEXT,
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
