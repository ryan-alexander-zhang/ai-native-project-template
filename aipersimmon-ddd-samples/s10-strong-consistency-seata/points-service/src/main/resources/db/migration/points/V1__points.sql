-- The participant's own tables. undo_log is deliberately NOT here: Seata verifies its existence while
-- the DataSource bean is being constructed, which is strictly before Flyway can run — Flyway depends on
-- the DataSource. See the sample README, "undo_log is not yours to migrate".
--
-- The key is (tenant_id, account_id): a points account is unique per tenant, and isolation belongs in
-- the constraint and not only in the interceptor's predicate. It is also the key Seata AT uses to build
-- its rollback, so this table's identity is load-bearing twice over.
CREATE TABLE s10_points_account (
    tenant_id  VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    awarded    INTEGER     NOT NULL DEFAULT 0,
    -- Points promised by a TCC Try and not yet settled. AT has no use for this column; TCC's entire
    -- advantage is that this column exists, so the row is free again the moment Try commits.
    frozen     INTEGER     NOT NULL DEFAULT 0,
    version    BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, account_id)
);

-- The ledger, keyed by the caller's reference. This is what makes every operation here idempotent
-- without the participant having to remember anything else — including TCC's Cancel, which must be
-- able to run for a Try that never arrived.
CREATE TABLE s10_points_entry (
    tenant_id  VARCHAR(64)  NOT NULL,
    reference  VARCHAR(128) NOT NULL,
    account_id VARCHAR(64)  NOT NULL,
    points     INTEGER      NOT NULL,
    -- RESERVED (a Try that has not settled) / AWARDED (settled, points are real) / CANCELLED.
    state      VARCHAR(16)  NOT NULL,
    PRIMARY KEY (tenant_id, reference)
);

CREATE INDEX s10_points_entry_account ON s10_points_entry (tenant_id, account_id);

-- shared-loyalty exists so two different bank accounts can award points to one points row. That is the
-- only way to measure AT's lock against TCC's absence of one without the bank account's own lock being the
-- thing that blocks: under AT the second purchase cannot have the points row, under TCC it can.
INSERT INTO s10_points_account (tenant_id, account_id, awarded, frozen, version) VALUES
  ('acme', 'customer-1', 0, 0, 1),
  ('acme', 'customer-2', 0, 0, 1),
  ('acme', 'shared-loyalty', 0, 0, 1);
