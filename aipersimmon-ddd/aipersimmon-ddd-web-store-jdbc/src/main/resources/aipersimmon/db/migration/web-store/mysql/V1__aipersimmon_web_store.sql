-- Flyway migration (MySQL 8+) for the aipersimmon-ddd-web-store-jdbc tables. Single source of the
-- web-store schema. LONGBLOB for the response body, LONGTEXT for headers, DATETIME(3) timestamps.
CREATE TABLE IF NOT EXISTS aipersimmon_web_idempotency (
    idempotency_key  VARCHAR(255) NOT NULL,
    response_status  INT          NOT NULL,
    response_body    LONGBLOB,
    response_headers LONGTEXT,
    created_at       DATETIME(3)  NOT NULL,
    expires_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (idempotency_key)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS aipersimmon_web_nonce (
    nonce      VARCHAR(255) NOT NULL,
    created_at DATETIME(3)  NOT NULL,
    expires_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (nonce)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS aipersimmon_web_rate_limit (
    bucket_key   VARCHAR(255) NOT NULL,
    window_start DATETIME(3)  NOT NULL,
    count        BIGINT       NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
) ENGINE = InnoDB;
