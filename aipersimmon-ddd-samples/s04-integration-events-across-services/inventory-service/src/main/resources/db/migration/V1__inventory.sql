-- This service's own table. The framework's inbox table comes from the framework's migrations, and
-- only because application.yaml lists `inbox` under aipersimmon.ddd.flyway.components.
CREATE TABLE s04_stock (
    sku       VARCHAR(64) PRIMARY KEY,
    available INTEGER     NOT NULL,
    reserved  INTEGER     NOT NULL DEFAULT 0,
    version   BIGINT      NOT NULL DEFAULT 1
);

INSERT INTO s04_stock (sku, available, reserved, version) VALUES
  ('sku-keyboard', 100, 0, 1),
  ('sku-mouse', 50, 0, 1);
