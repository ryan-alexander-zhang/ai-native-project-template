CREATE TABLE s03_order (
    id            VARCHAR(36) PRIMARY KEY,
    customer_id   VARCHAR(64) NOT NULL,
    amount_cents  BIGINT      NOT NULL,
    review_reason VARCHAR(255),
    version       BIGINT      NOT NULL DEFAULT 1
);

-- A second aggregate, written by a subscriber inside the same transaction.
CREATE TABLE s03_coupon (
    id          VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    value_cents BIGINT      NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1
);
