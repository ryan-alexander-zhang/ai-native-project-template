-- Flyway migration (MySQL 8+) for the aipersimmon-ddd inbox table. Single source of the inbox
-- schema, shared by the -inbox-jdbc and -inbox-mybatis-plus adapters. DATETIME(3) for millisecond
-- precision; inline KEY (MySQL does not support CREATE INDEX IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (consumer, message_key),
    KEY idx_aipersimmon_inbox_processed_at (processed_at)
) ENGINE = InnoDB;
