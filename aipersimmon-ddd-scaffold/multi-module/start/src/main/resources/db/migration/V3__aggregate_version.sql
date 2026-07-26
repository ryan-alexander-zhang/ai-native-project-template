-- issue-00051 / design-00011: optimistic-lock version on every aggregate table that is written.
-- Without it two concurrent commands each pass the aggregate's own state guards on the snapshot they
-- loaded and both write, so the later write silently discards the earlier one: concurrent
-- ReserveStock on one SKU oversells it (both read available=10, both reserve 8, both store 2), and
-- concurrent ConfirmOrder/CancelOrder both "succeed" while emitting contradictory domain events.
--
-- The column is what makes the aggregate a real transactional consistency unit: the repository puts
-- the loaded value in the UPDATE's WHERE clause (MyBatis-Plus @Version + OptimisticLockerInnerInterceptor
-- rewrite it in), so the losing write affects 0 rows and surfaces as OptimisticLockingFailureException
-- -> ConcurrencyConflictException -> HTTP 409 rather than as silent data loss.
--
-- DEFAULT 1, not 0: version 0 is reserved to mean "not yet persisted", which is how the repository
-- tells an INSERT from an UPDATE without a preceding existence query. Existing rows (the V1 seed
-- data) ARE persisted, so they must start at 1 — with DEFAULT 0 they would load as version 0, the
-- repository would take them for new aggregates, and the save would attempt an INSERT and fail on
-- the primary key. A freshly inserted row is likewise written at version 1.
--
-- Only tables whose aggregate has a save() port get the column:
--   ordering.orders          - saved by PlaceOrder / ConfirmOrder / CancelOrder / FulfilmentTrigger
--   inventory.stocks         - saved by ReserveStock / ReleaseStock  (the oversell path)
--   inventory.reservations   - saved by ReserveStock / ReleaseStock
-- ordering.customers is deliberately absent: Customers exposes only findById, so the Customer
-- aggregate is never written and a version column there would be dead weight.
--
-- Child tables (order_lines, reservation_lines) are not versioned: they are inside the aggregate
-- boundary and are rewritten wholesale under the root's version check, which already serialises
-- concurrent writers of the whole aggregate.

ALTER TABLE ordering.orders        ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory.stocks       ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory.reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
