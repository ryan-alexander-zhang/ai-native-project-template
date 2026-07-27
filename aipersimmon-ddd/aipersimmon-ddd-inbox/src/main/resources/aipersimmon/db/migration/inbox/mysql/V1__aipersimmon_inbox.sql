-- Flyway migration (MySQL 8+) for the aipersimmon-ddd inbox table. Single source of the inbox
-- schema, shared by the -inbox-jdbc and -inbox-mybatis-plus adapters. DATETIME(3) for millisecond
-- precision; inline KEY (MySQL does not support CREATE INDEX IF NOT EXISTS). The primary key
-- (consumer, source, message_key) scopes idempotency dedup to one consuming application and,
-- within it, to one producer: message_key (ce_id) is unique only within its source, so source
-- belongs in the key rather than in a data column (see the Inbox port). It stays inside InnoDB's
-- 3072-byte index limit: (128 + 255 + 128) chars x 4 bytes (utf8mb4) = 2044.
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    source       VARCHAR(255) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (consumer, source, message_key),
    KEY idx_aipersimmon_inbox_processed_at (processed_at)
) ENGINE = InnoDB;
