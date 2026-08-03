-- This service's own table. The framework's inbox table comes from the framework's migrations, and only
-- because application.yaml lists `inbox` under aipersimmon.ddd.flyway.components.
--
-- upstream_revision is domain state, not bookkeeping: it is what makes a late message answerable. Note
-- it sits beside `version`, which is this row's optimistic lock — two different races, two columns.
CREATE TABLE s05_product (
    sku               VARCHAR(64) PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    price_cents       BIGINT       NOT NULL,
    upstream_revision BIGINT       NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 1
);
