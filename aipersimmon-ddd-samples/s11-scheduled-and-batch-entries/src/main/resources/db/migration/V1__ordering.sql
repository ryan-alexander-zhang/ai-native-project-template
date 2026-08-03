CREATE TABLE s11_order (
    id             VARCHAR(36) PRIMARY KEY,
    customer_id    VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    payment_due_at TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 1
);

-- The sweep's scan: open orders past their due time, oldest first. The index leads with the column
-- the scan filters on and carries the one it orders by, so a backlog of a million rows still costs
-- one bounded range scan per round rather than a sequential scan of the table every few minutes.
CREATE INDEX s11_order_due ON s11_order (status, payment_due_at);
