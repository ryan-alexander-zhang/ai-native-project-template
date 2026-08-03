-- recorded_at bounds the 30-day dedupe window, so both its meaning and the cost of
-- expiring by it are load-bearing.

-- TIMESTAMP (without time zone) stores a wall-clock reading and silently reinterprets it under the
-- session's time zone; a non-UTC session shifts the entire dedupe window, and a shortened window
-- is "a late redelivery authorizes a second time". timestamptz stores the instant unambiguously.
-- Existing values were written by CURRENT_TIMESTAMP under UTC sessions, which the USING clause
-- states rather than assumes. (The value itself now arrives from the application's Clock bean —
-- one time source for writing the window and expiring it — instead of the database's.)
ALTER TABLE payment.payment_operations
    ALTER COLUMN recorded_at TYPE TIMESTAMPTZ USING recorded_at AT TIME ZONE 'UTC';

-- The hourly purge deletes by recorded_at <, and without an index every run is a full sequential
-- scan whose cost grows with the retained history — measured on PostgreSQL 18.1 with 1M retained
-- rows: 8,345 buffers / ~86ms per run, including runs that delete nothing. With the index the scan
-- touches only what expired (18 buffers / 0.36ms typical; 3 buffers / 0.009ms when nothing has),
-- so the cost tracks the expired work, not the history.
CREATE INDEX payment_operations_by_recorded_at
    ON payment.payment_operations (recorded_at);
