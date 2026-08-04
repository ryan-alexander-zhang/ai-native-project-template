-- The catalogue's own table. The outbox tables come from the framework's migrations, and only because
-- catalog-service.yaml lists `outbox` under aipersimmon.ddd.flyway.components.
--
-- This is the whole of what the catalogue owns about a name. Note what is *not* here: any notion of who
-- else is displaying it. A context that publishes a rename does not know, and must not need to know, that
-- another context keeps a copy — which is exactly why the copy has to be the other context's asset.
CREATE TABLE s12_product (
    sku     VARCHAR(64) PRIMARY KEY,
    name    VARCHAR(200) NOT NULL,
    version BIGINT       NOT NULL DEFAULT 1
);

INSERT INTO s12_product (sku, name, version) VALUES
  ('sku-keyboard', 'Mechanical Keyboard', 1),
  ('sku-mouse',    'Wireless Mouse',      1),
  ('sku-monitor',  '27-inch Monitor',     1);
