-- This service's own table. The framework's inbox and outbox tables come from the framework's
-- migrations, and only because application.yaml lists them under aipersimmon.ddd.flyway.components.
--
-- Stock is per (sku, warehouse) because the contract's v3 addition names a warehouse. Note the shape
-- of the alignment: the schema can hold warehouses before any event carries one, and that is the only
-- safe order. A consumer whose schema learns about a field at the same moment its contract does has no
-- window in which to be deployed first.
CREATE TABLE s21_stock (
    location  VARCHAR(128) PRIMARY KEY,
    sku       VARCHAR(64)  NOT NULL,
    warehouse VARCHAR(32)  NOT NULL,
    available INTEGER      NOT NULL,
    reserved  INTEGER      NOT NULL DEFAULT 0,
    version   BIGINT       NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX s21_stock_sku_warehouse ON s21_stock (sku, warehouse);

INSERT INTO s21_stock (location, sku, warehouse, available, reserved, version) VALUES
  ('sku-keyboard@MAIN', 'sku-keyboard', 'MAIN', 100, 0, 1),
  ('sku-keyboard@EU',   'sku-keyboard', 'EU',   100, 0, 1),
  ('sku-mouse@MAIN',    'sku-mouse',    'MAIN',  50, 0, 1);
