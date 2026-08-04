-- This service's own schema. The framework's tables — the outbox, its dead letters, and the three
-- web-store tables behind the replay guard — are created by a separate runner with its own history
-- table, driven by aipersimmon.ddd.flyway.components. Nothing here has to know their shape.

CREATE TABLE s07_payment (
    id            VARCHAR(36)  PRIMARY KEY,
    order_ref     VARCHAR(64)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    requested_at  TIMESTAMPTZ  NOT NULL,
    -- REQUESTED / SUBMITTED / SUCCEEDED / FAILED, stored by name. An ordinal would tie every existing
    -- row's meaning to the declaration order of a Java enum.
    status        VARCHAR(16)  NOT NULL,
    -- The provider's own reference. Nullable, because it does not exist until they tell us — and the
    -- window in which it is null is exactly the window in which a payment is hardest to talk about.
    gateway_ref   VARCHAR(64),
    -- Why a human was asked to look. Nullable, and NOT a status: a payment under review can still be
    -- settled by a callback that finally arrives, and folding this into `status` would make that
    -- impossible to express.
    review_reason VARCHAR(500),
    version       BIGINT       NOT NULL DEFAULT 1
);

-- The business rule an idempotency key cannot express. The key protects the *call* — the same request
-- sent twice charges once. This protects the *order* — two different requests, minutes apart, from a
-- customer who clicked twice or a retrying client that minted a fresh key, cannot both become charges.
-- The two are complementary and neither substitutes for the other (S2 argues it at length).
CREATE UNIQUE INDEX uq_s07_payment_order_ref ON s07_payment (order_ref);

-- The reconciler's scan, indexed exactly as written: unsettled, not escalated, oldest first. A partial
-- index because the rows that matter are a shrinking minority of the table — every payment ends up
-- terminal, so the interesting set stays small while the table grows forever, and a full index on
-- requested_at would make the planner walk history it can never need.
CREATE INDEX idx_s07_payment_unsettled
    ON s07_payment (requested_at)
    WHERE status IN ('REQUESTED', 'SUBMITTED') AND review_reason IS NULL;
