-- This service's own tables. The framework's outbox tables come from the framework's migrations, and
-- only because application.yaml lists `outbox` under aipersimmon.ddd.flyway.components.
--
-- warehouse_code is here in V1 rather than in a V2 that adds it, because this sample's subject is the
-- evolution of the *published contract*, not of the schema. How the two orders are aligned — schema
-- first, contract second, and why never the other way round — is §7 of the companion document; the
-- mechanics of the schema half are S23.
CREATE TABLE s21_order (
    id             VARCHAR(36) PRIMARY KEY,
    customer_id    VARCHAR(64) NOT NULL,
    warehouse_code VARCHAR(32) NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 1
);

CREATE TABLE s21_order_line (
    id       VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL REFERENCES s21_order (id),
    sku      VARCHAR(64) NOT NULL,
    quantity INTEGER     NOT NULL
);

CREATE INDEX s21_order_line_order ON s21_order_line (order_id);
