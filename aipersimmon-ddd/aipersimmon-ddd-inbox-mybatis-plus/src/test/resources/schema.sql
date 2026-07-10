CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    message_key  VARCHAR(128) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);
