-- This service's own table. The framework's inbox table comes from the framework's migrations, and
-- only because application.yaml lists `inbox` under aipersimmon.ddd.flyway.components.
--
-- The key is (tenant_id, sku), not sku. That is the part of adopting tenancy that a column addition
-- does not cover: a sku is unique per tenant, so a single-column key would have made two tenants
-- compete for one row — and the interceptor cannot save a key. Isolation belongs in the constraint,
-- where the application cannot bypass it, and not only in the predicate.
--
-- Measured, not assumed. Removing s04_stock from tenant-tables (with the startup guard off, so the
-- deployment could actually boot) does not silently read another tenant's row here: selectById drops
-- to `WHERE sku = ?`, matches two rows, and MyBatis raises TooManyResultsException — a systemic
-- failure the consumer retries forever, so the partition stalls and consumer lag climbs. Loud, and
-- loud only because the key is composite. With a single-column key the same mistake would have
-- returned somebody else's row and reserved from it.
CREATE TABLE s04_stock (
    tenant_id VARCHAR(64) NOT NULL,
    sku       VARCHAR(64) NOT NULL,
    available INTEGER     NOT NULL,
    reserved  INTEGER     NOT NULL DEFAULT 0,
    version   BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, sku)
);

INSERT INTO s04_stock (tenant_id, sku, available, reserved, version) VALUES
  ('acme',   'sku-keyboard', 100, 0, 1),
  ('acme',   'sku-mouse',     50, 0, 1),
  ('globex', 'sku-keyboard', 100, 0, 1),
  ('globex', 'sku-mouse',     50, 0, 1);
