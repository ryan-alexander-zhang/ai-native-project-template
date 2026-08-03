-- The business schema. No framework tables: this sample uses neither the outbox, the inbox, the
-- process manager nor the operation log.

CREATE TABLE s01_order (
    id          VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(64)  NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    -- What the version-checked update keys on. DEFAULT 1 matches the version the insert branch writes.
    version     BIGINT       NOT NULL DEFAULT 1
);

CREATE TABLE s01_order_line (
    id       VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL REFERENCES s01_order (id),
    sku      VARCHAR(64) NOT NULL,
    quantity INTEGER     NOT NULL
);

CREATE INDEX idx_s01_order_line_order ON s01_order_line (order_id);
