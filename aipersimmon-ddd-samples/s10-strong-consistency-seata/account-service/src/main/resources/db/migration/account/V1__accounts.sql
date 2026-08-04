-- The money. undo_log is not here, for the reason given in points-service's V1 and in the README.
--
-- The key is (tenant_id, id): it is the tenant boundary in a constraint rather than only in a predicate,
-- and it is also the key Seata AT rolls back by and the key its global lock is taken on. Measured: the
-- lock key Seata reports is "s10_account:acme_customer-1" — the whole primary key, joined.
CREATE TABLE s10_account (
    tenant_id     VARCHAR(64) NOT NULL,
    id            VARCHAR(64) NOT NULL,
    balance_minor BIGINT      NOT NULL,
    -- Nullable on purpose: it exercises the framework's cleared-column handling, which forces an explicit
    -- "SET last_note = NULL" that Seata's SQL parser then has to cope with. It does; the after-image
    -- carries the null.
    last_note     VARCHAR(200),
    version       BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, id)
);

INSERT INTO s10_account (tenant_id, id, balance_minor, last_note, version) VALUES
  ('acme', 'customer-1', 100000, 'opening', 1),
  ('acme', 'customer-2', 100000, 'opening', 1);
