-- This service's own table, and the only table it has: no outbox, no inbox, no dedup log. A synchronous
-- call is the one integration style that adds no schema — and leaves no record that it happened.
CREATE TABLE s06_order (
    id           VARCHAR(36) PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    amount_cents BIGINT      NOT NULL,
    version      BIGINT      NOT NULL DEFAULT 1
);
