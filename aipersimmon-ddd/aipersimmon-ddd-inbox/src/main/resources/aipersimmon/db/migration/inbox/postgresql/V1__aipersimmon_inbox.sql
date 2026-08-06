-- Flyway migration (PostgreSQL) for the aipersimmon-ddd inbox table. Single source of the inbox
-- schema, used by the -inbox-mybatis-plus adapter. The primary key
-- (consumer, source, message_key) scopes idempotency dedup to one consuming application and,
-- within it, to one producer: message_key (ce_id) is unique only within its source, so source
-- belongs in the key rather than in a data column (see the Inbox port).
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    source       VARCHAR(255) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (consumer, source, message_key)
);

CREATE INDEX IF NOT EXISTS idx_aipersimmon_inbox_processed_at
    ON aipersimmon_inbox (processed_at);
