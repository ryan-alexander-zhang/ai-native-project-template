-- The last line of defence for rules that so far existed only in the application.
--
-- The argument is V1_4's, applied to the constraints it was not applied to. The aggregates' guards
-- and the tenant interceptor are the application; a data-fix script, an operator at a psql prompt,
-- and the tests' own raw JdbcTemplate bypass all of it. A rule worth enforcing is worth one line in
-- the layer that cannot be bypassed. SchemaBackstopTest holds each of these in place.

-- The line shape OrderLine's constructor enforces, mirrored where no constructor runs.
ALTER TABLE ordering.order_lines
    ADD CONSTRAINT order_lines_quantity_positive CHECK (quantity > 0);
ALTER TABLE ordering.order_lines
    ADD CONSTRAINT order_lines_unit_minor_non_negative CHECK (unit_minor >= 0);

-- OrderHasDistinctSkus runs in memory at placement and — correctly — is not re-run on
-- reconstitution. Without this key the stored line set had no guard at all: a bypassing write
-- could produce duplicate-SKU rows whose total() and reservation semantics silently change on the
-- next load, with nothing reporting it.
ALTER TABLE ordering.order_lines
    ADD CONSTRAINT order_lines_one_line_per_sku UNIQUE (tenant_id, order_id, sku);

-- ...and the unique index behind that key leads with (tenant_id, order_id), so it answers the
-- child-side reads (saveChildren's delete, findById's select) that order_lines_by_order (V1_4)
-- existed for. Replaced rather than kept alongside — same move V2_5 made for reservations: two
-- indexes with one job are write amplification and a planner coin-toss, and the planner already
-- prefers the unique one. OrderListPagingTest pins the child read to it.
DROP INDEX ordering.order_lines_by_order;

-- Same bounded context, same schema, same transaction: inside the boundary the V1_4 tenant-carrying
-- FK argument applies with no counter-argument, so orders.customer_id gets the reference it always
-- implied. (Cross-context references stay FK-free on purpose — that line is the context boundary.)
-- The child-side index this needs already exists: orders_by_customer_newest_first (V1_4) leads with
-- (tenant_id, customer_id).
ALTER TABLE ordering.orders
    ADD CONSTRAINT orders_customer_fkey
    FOREIGN KEY (tenant_id, customer_id) REFERENCES ordering.customers (tenant_id, id);

-- Business time, readable. It so far lived only inside the UUIDv7 id — fine for cursor paging,
-- unreadable for audit, BI, reconciliation and support, all of which had to decode an id to answer
-- "when was this placed?". Written by the application from its Clock bean (the single time source
-- the rest of the application uses), not by a database default: the DEFAULT below exists only to
-- backfill rows that predate the column, and is dropped immediately so a write that forgets the
-- value fails instead of silently picking up database time.
ALTER TABLE ordering.orders ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE ordering.orders ALTER COLUMN created_at DROP DEFAULT;
