CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (consumer, message_key)
);
CREATE INDEX IF NOT EXISTS idx_aipersimmon_inbox_processed_at ON aipersimmon_inbox (processed_at);
