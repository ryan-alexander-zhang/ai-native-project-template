-- "One order, one reservation" is a business fact, so it is written into the schema.
--
-- The handler already answers a redelivered ReserveStock by finding the existing reservation and
-- re-announcing it — but that lookup cannot settle two deliveries racing each other, and the
-- failure mode of losing that race is not "one action done twice" (compensable) but a second
-- Reservation nothing will ever release: the fulfilment flow has moved on, the extra StockReserved
-- is ignored, and the held stock leaks with no alarm. The unique key is the last line: the losing
-- insert rolls back, the delivery is retried, and the retry finds the winner's row.
--
-- The index this replaces (reservations_by_order, V2_4) served lookups by (tenant_id, order_id);
-- a unique index serves the same reads, so it is replaced rather than duplicated.

DROP INDEX inventory.reservations_by_order;

CREATE UNIQUE INDEX reservations_by_order
    ON inventory.reservations (tenant_id, order_id);
