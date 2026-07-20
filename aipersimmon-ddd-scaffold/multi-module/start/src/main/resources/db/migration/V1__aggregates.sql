-- plan-00007: business aggregate tables. One PostgreSQL database, one schema per bounded context
-- (ordering / inventory). Runs as the consumer's OWN Flyway migration (default history table
-- flyway_schema_history), before the aipersimmon component migrations that own the outbox / inbox /
-- process-manager tables in the public schema. Same database → an aggregate write and its outbox row
-- commit in one transaction. Intra-aggregate FKs only; no cross-bounded-context FK/join.

CREATE SCHEMA IF NOT EXISTS ordering;
CREATE SCHEMA IF NOT EXISTS inventory;

-- ordering context ----------------------------------------------------------

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

-- inventory context ---------------------------------------------------------

CREATE TABLE inventory.stocks (
    sku        VARCHAR(64) PRIMARY KEY,
    available  INT         NOT NULL
);

CREATE TABLE inventory.reservations (
    id        VARCHAR(64) PRIMARY KEY,
    order_id  VARCHAR(64) NOT NULL,
    released  BOOLEAN     NOT NULL
);

CREATE TABLE inventory.reservation_lines (
    reservation_id  VARCHAR(64) NOT NULL REFERENCES inventory.reservations (id),
    sku             VARCHAR(64) NOT NULL,
    quantity        INT         NOT NULL,
    PRIMARY KEY (reservation_id, sku)
);

-- seed (same demo data the in-memory repositories used) ----------------------

INSERT INTO ordering.customers (id, name, credit_minor, currency)
VALUES ('CUST-1', 'Acme', 100000, 'USD');

INSERT INTO inventory.stocks (sku, available)
VALUES ('SKU-1', 10), ('SKU-2', 5);
