-- Make the credit limit enforceable, which means making Customer a
-- thing that is written.
--
-- The limit was compared against but never held. Nothing wrote to ordering.customers, so there was
-- no contention point, so two concurrent placements could each read credit_minor = 100000, each
-- pass the check, and together commit any multiple of the limit. Worse, the comparison was
-- per-order rather than cumulative, so it did not even need concurrency: two serial orders of
-- 60000 both passed a limit of 100000. What was named a credit limit behaved as a per-order cap.
--
-- Two columns fix both halves.
--
--   used_minor  what open orders currently hold. Reserved when an order is placed, returned when
--               it is cancelled, kept when it is confirmed (the customer owes it by then). This is
--               the column that turns "is this order under the limit?" into "is this order under
--               what is LEFT of the limit?".
--   version     the optimistic lock. V1_3 deliberately skipped this table, and was right to at the
--               time: "Customers exposes only findById, so the Customer aggregate is never written
--               and a version column there would be dead weight." That reasoning was sound and its
--               premise is what changed — enforcing credit requires writing, and writing requires
--               the version. DEFAULT 1 for the same reason as V1_3's orders table: version 0 means
--               "not yet persisted", and the seeded rows are persisted.
--
-- Backfilling used_minor to 0 is correct rather than convenient: no existing order ever reserved
-- credit, so there is nothing committed to carry forward. On a real deployment with live orders
-- this would instead be a SUM over open orders, and that difference is the interesting part of
-- turning an advisory check into an enforced one.

ALTER TABLE ordering.customers ADD COLUMN used_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ordering.customers ADD COLUMN version    BIGINT NOT NULL DEFAULT 1;

-- The list of open orders per customer is now a hot read for any credit question an operator asks
-- ("what is holding this customer's credit?"), and orders_by_customer_newest_first (V1_4) already
-- serves it: (tenant_id, customer_id, id DESC) covers the customer predicate. No new index here.
