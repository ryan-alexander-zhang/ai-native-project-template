-- The two things V1_2 left behind, for the ordering context: tenant-carrying foreign keys,
-- and the indexes the read paths need.
--
-- V1_1 created the tables; V1_2 added tenant_id and reworked the *primary* keys so two tenants could
-- each own a 'CUST-1'. But a new column changes more than the primary key. It changes every other
-- constraint that is supposed to express "within one tenant" (part 1), and it changes which indexes
-- the queries need, because every predicate the interceptor rewrites now leads with tenant_id
-- (part 2).
--
-- The checklist V1_2 worked from was "which keys might now collide?". The checklist it needed was
-- "which constraints and which indexes now need one more column?". Primary keys are the only overlap
-- between the two, which is why they were the only thing V1_2 got to.

-- Part 1 — foreign keys must carry the tenant --------------------------------
--
-- V1_1 gave order_lines a single-column foreign key (order_lines.order_id). V1_2's note is right that
-- orders.id is a globally-unique UUIDv7 and so cannot collide across tenants — but that argument is
-- about primary keys, and it was carried over to foreign keys, which guard something else entirely. A
-- primary key stops two tenants from using one key. A foreign key stops a *reference* from crossing
-- the tenant boundary, and a single-column one cannot: the database happily accepts an order_lines row
-- with tenant_id='acme' hanging off an orders row with tenant_id='globex'.
--
-- In practice the MyBatis-Plus tenant-line interceptor prevents it. But the interceptor is the
-- application, and everything that bypasses it — a data-fix script, a migration, an operator at a psql
-- prompt, the raw JdbcTemplate the tests themselves use — has been running with no constraint at all.
-- Tenant isolation is worth having in the one place that cannot be bypassed.
--
-- Order of operations matters: a foreign key depends on the unique index backing the key it points at,
-- so the child constraint has to go before the parent's primary key can be swapped.

ALTER TABLE ordering.order_lines DROP CONSTRAINT order_lines_order_id_fkey;

ALTER TABLE ordering.orders DROP CONSTRAINT orders_pkey;
ALTER TABLE ordering.orders ADD  PRIMARY KEY (tenant_id, id);

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT order_lines_order_fkey
    FOREIGN KEY (tenant_id, order_id) REFERENCES ordering.orders (tenant_id, id);

-- This is not a new shape for the persistence layer: customers has had a composite primary key since
-- V1_2 and is read through the same @TableId(type = IdType.INPUT) on a single column, with the
-- interceptor supplying tenant_id. orders is only being pulled level with it.

-- Part 2 — the indexes those queries need ------------------------------------
--
-- Cursor paging's *correctness* — no repeats, no gaps under concurrent inserts — comes from UUIDv7 ids
-- and `id < :after`, and OrderListPagingTest has covered that from the start. Its *performance* — a
-- page costing the page, not the table — comes from an index, and nothing covered that, because a
-- functional test cannot tell the two apart: an unindexed cursor query returns exactly the right page,
-- just by reading every row to find it. Cursor paging without a supporting index costs the same order
-- of magnitude as the offset paging it replaced.

-- The list statement (OrderListMapper.byCustomer) filters on customer_id, has tenant_id added by the
-- interceptor, and walks the cursor with `id < ?` in id DESC order. One index serves all three:
-- equality columns first, then the range/sort column, which turns the whole page into a single index
-- range scan.
CREATE INDEX orders_by_customer_newest_first
    ON ordering.orders (tenant_id, customer_id, id DESC);

-- PostgreSQL indexes the *parent* side of a foreign key (that is the key it points at) but never the
-- child side. order_lines is read and deleted by its parent id on the hot write path — saveChildren
-- rewrites the line set on every save, findById reads it back — so it needs the index the foreign key
-- does not bring with it.
CREATE INDEX order_lines_by_order
    ON ordering.order_lines (tenant_id, order_id);
