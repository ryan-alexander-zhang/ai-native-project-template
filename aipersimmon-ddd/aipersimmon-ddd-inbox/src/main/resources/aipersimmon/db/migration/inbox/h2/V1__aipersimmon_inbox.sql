-- Flyway migration (H2) for the aipersimmon-ddd inbox table. Single source of the inbox schema,
-- shared by the -inbox-jdbc and -inbox-mybatis-plus adapters (identical structure). The primary
-- key (consumer, message_key) scopes idempotency dedup to one consuming application.
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (consumer, message_key)
);

-- Speeds up the retention cleanup (DELETE ... WHERE processed_at < ?).
CREATE INDEX IF NOT EXISTS idx_aipersimmon_inbox_processed_at
    ON aipersimmon_inbox (processed_at);
