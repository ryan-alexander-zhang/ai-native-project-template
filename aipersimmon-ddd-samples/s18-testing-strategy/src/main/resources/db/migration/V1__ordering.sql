CREATE TABLE s18_order (
    id           VARCHAR(36) PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    amount_cents BIGINT      NOT NULL,
    status       VARCHAR(32) NOT NULL,
    version      BIGINT      NOT NULL DEFAULT 1
);
