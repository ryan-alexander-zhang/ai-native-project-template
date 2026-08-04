-- This service's own table. aipersimmon_inbox comes from the framework's migration, because
-- application.yaml lists `inbox` under aipersimmon.ddd.flyway.components.
CREATE TABLE s22_stock (
    sku       VARCHAR(64) PRIMARY KEY,
    available INTEGER     NOT NULL,
    reserved  INTEGER     NOT NULL DEFAULT 0,
    version   BIGINT      NOT NULL DEFAULT 1
);

-- Seeded, because this sample is about what happens to messages and not about how stock arrives.
INSERT INTO s22_stock (sku, available, reserved, version) VALUES
    ('sku-keyboard', 100, 0, 1),
    ('sku-mouse',    100, 0, 1),
    ('sku-cable',    100, 0, 1);
