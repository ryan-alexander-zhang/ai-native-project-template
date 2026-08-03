CREATE TABLE s20_order (
    id          VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    quantity    INTEGER      NOT NULL,
    placed_at   TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 1
);

-- The keyset index. Its column list is the sort key, in the sort's direction, and it exists so the
-- seek predicate `(placed_at, id) < (?, ?)` is an index range scan rather than a filter over rows
-- the query then discards. Keyset pagination without a matching index is not faster than offset
-- pagination -- it is only more correct.
CREATE INDEX s20_order_keyset ON s20_order (placed_at DESC, id DESC);

-- Filtering changes which index the sort key belongs in: a filtered list wants the filter column
-- first, then the sort key, so one range scan serves both. Adding a filter to an endpoint without
-- adding the matching index is the usual reason a list query that was fast becomes slow.
CREATE INDEX s20_order_customer_keyset ON s20_order (customer_id, placed_at DESC, id DESC);
