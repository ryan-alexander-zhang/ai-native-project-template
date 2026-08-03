-- The business schema. No framework tables: the Redis edge store needs no DDL, and this sample uses
-- neither the outbox, the inbox, the process manager nor the operation log.

CREATE TABLE s02_order (
    id               VARCHAR(36) PRIMARY KEY,
    client_reference VARCHAR(64) NOT NULL,
    amount_cents     BIGINT      NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 1
);

-- The business uniqueness rule, and the thing an Idempotency-Key cannot express: one order per
-- client reference, for as long as the row exists, no matter how many different submissions try.
CREATE UNIQUE INDEX uq_s02_order_client_reference ON s02_order (client_reference);
