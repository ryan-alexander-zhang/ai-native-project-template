-- The ordering context's aggregate tables, in its own schema.
--
-- Runs as the consumer's OWN Flyway migration (default history table flyway_schema_history), before
-- the aipersimmon component migrations that own the outbox / inbox / process-manager tables. Same
-- database as those, which is the load-bearing part: an aggregate write and its outbox row commit in
-- one transaction.
--
-- Intra-aggregate foreign keys only. No FK and no join crosses a bounded-context boundary — see
-- ../README.md for why that is what lets each context own its own version namespace.

CREATE SCHEMA IF NOT EXISTS ordering;

CREATE TABLE ordering.customers (
    id            VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    credit_minor  BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL
);

CREATE TABLE ordering.orders (
    id           VARCHAR(64) PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    status       VARCHAR(32) NOT NULL
);

CREATE TABLE ordering.order_lines (
    order_id    VARCHAR(64) NOT NULL REFERENCES ordering.orders (id),
    line_no     INT         NOT NULL,
    sku         VARCHAR(64) NOT NULL,
    quantity    INT         NOT NULL,
    unit_minor  BIGINT      NOT NULL,
    currency    VARCHAR(3)  NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

-- No seed data here, and none in any other versioned migration. A versioned migration runs exactly
-- once in EVERY environment and Flyway gives it no way to opt out, so the demo rows this scaffold
-- needs (CUST-1, SKU-1, ...) would arrive in a real production database too. They live in
-- db/dev/afterMigrate__seed.sql, a location only the dev profile loads.
-- MigrationContentTest keeps this true.
