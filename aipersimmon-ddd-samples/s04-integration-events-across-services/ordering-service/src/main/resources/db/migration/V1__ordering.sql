-- This service's own tables. The framework's outbox tables are NOT here: they come from the
-- framework's migrations, and only because application.yaml lists `outbox` under
-- aipersimmon.ddd.flyway.components. Being on the classpath is not being applied.
CREATE TABLE s04_order (
    id          VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1
);

CREATE TABLE s04_order_line (
    id       VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL REFERENCES s04_order (id),
    sku      VARCHAR(64) NOT NULL,
    quantity INTEGER     NOT NULL
);

CREATE INDEX s04_order_line_order ON s04_order_line (order_id);
