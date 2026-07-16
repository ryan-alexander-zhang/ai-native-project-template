-- Inbox table for the embedded-broker dead-letter test's JDBC inbox.
CREATE TABLE IF NOT EXISTS aipersimmon_inbox (
    consumer     VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (consumer, message_key)
);
