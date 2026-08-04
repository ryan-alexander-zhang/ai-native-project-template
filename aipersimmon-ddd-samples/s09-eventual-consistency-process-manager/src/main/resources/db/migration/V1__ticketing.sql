-- This service's own schema. The coordinator's four tables (instance, transition, effect, deadline)
-- are created by a separate runner with its own history table, driven by
-- aipersimmon.ddd.flyway.components. Nothing here knows their shape, and nothing there knows this one's
-- — which is the point: the flow's bookkeeping and the business truth are different tables owned by
-- different code.

-- The truth about a ticket order. The flow does NOT hold a copy of this status; see TicketingState.
CREATE TABLE s09_ticket_order (
    id            VARCHAR(36)  PRIMARY KEY,
    customer_id   VARCHAR(64)  NOT NULL,
    seat_class    VARCHAR(32)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    -- PLACED / TICKETED / CANCELLED
    status        VARCHAR(16)  NOT NULL,
    cancel_reason VARCHAR(200),
    version       BIGINT       NOT NULL DEFAULT 1
);

-- Seat inventory, one row per class, and the counter the flow's first step competes for.
CREATE TABLE s09_seat_class (
    seat_class VARCHAR(32) PRIMARY KEY,
    available  INT         NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 1
);

-- One hold per order, as a child of its seat class. Released holds are kept rather than deleted: the
-- release is a fact with a time, which is the smallest possible illustration of what compensation is.
CREATE TABLE s09_seat_hold (
    order_id    VARCHAR(36) PRIMARY KEY,
    seat_class  VARCHAR(32) NOT NULL REFERENCES s09_seat_class (seat_class),
    held_at     TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ
);

CREATE INDEX idx_s09_seat_hold_class ON s09_seat_hold (seat_class);

-- A prepaid balance. There is no third party in this sample — S7 covers that — so the money moves
-- inside a local aggregate, which is what makes the ledger below observable in a test.
CREATE TABLE s09_wallet (
    customer_id   VARCHAR(64) PRIMARY KEY,
    balance_minor BIGINT      NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 1
);

-- The ledger, and the sample's central exhibit for "compensation is not a rollback".
--
-- A refund is not the deletion of a debit. It is a second entry, with its own reference, its own sign
-- and its own reason, and both entries stay on the statement forever. `reference` is UNIQUE, which is
-- how a redelivered effect debits or credits exactly once — the aggregate refuses the second one by
-- recognising the reference, and the index is the backstop under concurrency.
CREATE TABLE s09_wallet_entry (
    reference    VARCHAR(80) PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL REFERENCES s09_wallet (customer_id),
    -- DEBIT / CREDIT
    kind         VARCHAR(8)  NOT NULL,
    amount_minor BIGINT      NOT NULL,
    note         VARCHAR(200),
    recorded_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_s09_wallet_entry_customer ON s09_wallet_entry (customer_id, recorded_at);

-- Two seat classes and one well-funded customer, so a fresh database can run the happy path.
INSERT INTO s09_seat_class (seat_class, available) VALUES ('STALLS', 2), ('BALCONY', 0);
INSERT INTO s09_wallet (customer_id, balance_minor) VALUES ('customer-1', 20000);
