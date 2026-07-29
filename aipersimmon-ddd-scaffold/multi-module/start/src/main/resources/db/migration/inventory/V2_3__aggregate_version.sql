-- issue-00051 / design-00011: optimistic-lock version on the inventory tables that are written.
--
-- This is the context where the absence of it is most visible: concurrent ReserveStock on one SKU
-- oversells it (both read available=10, both reserve 8, both store 2). The column puts the loaded
-- value in the UPDATE's WHERE clause, so the losing write affects 0 rows and surfaces as
-- ConcurrencyConflictException instead of silent data loss — and because ReserveStockHandler
-- deliberately does not catch technical failures, that rolls the transaction back and the delivery is
-- retried.
--
-- DEFAULT 1, not 0: version 0 is reserved to mean "not yet persisted", which is how the repository
-- tells an INSERT from an UPDATE without a preceding existence query. Seeded rows ARE persisted, so
-- they must start at 1.
--
-- Both roots that have a save() port get the column:
--   inventory.stocks        - saved by ReserveStock / ReleaseStock  (the oversell path)
--   inventory.reservations  - saved by ReserveStock / ReleaseStock
--
-- reservation_lines is not versioned: it is inside the Reservation aggregate boundary and is rewritten
-- wholesale under the root's version check.

ALTER TABLE inventory.stocks       ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory.reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
