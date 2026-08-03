-- The inventory context's aggregate tables, in its own schema.
--
-- Independent of ordering/V1_*: no foreign key, view or join crosses the boundary, so the only thing
-- these two version namespaces share is the database (which is what lets an aggregate write and its
-- outbox row commit together). See ../README.md.
--
-- Reservation references its order by a bare VARCHAR and NOT by a foreign key into ordering.orders.
-- That is the boundary, expressed in DDL: OrderId is ordering's type and inventory may not depend on
-- it — ArchitectureTest fails the build if the Java side ever does.

CREATE SCHEMA IF NOT EXISTS inventory;

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

-- Structure only. The demo stock levels live in db/dev/afterMigrate__seed.sql, which only the dev
-- profile loads; MigrationContentTest keeps this true.
