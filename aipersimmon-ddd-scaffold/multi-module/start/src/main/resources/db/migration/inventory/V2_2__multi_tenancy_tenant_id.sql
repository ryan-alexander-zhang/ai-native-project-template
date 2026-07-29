-- design-00009: pool multi-tenancy by a tenant_id discriminator column, for the inventory context.
-- Same rationale as ordering/V1_2 — NOT NULL DEFAULT '__root__', so single-tenant is N=1 under the
-- sentinel and existing rows migrate automatically, and the column must exist for the MyBatis-Plus
-- tenant-line interceptor's rewrite to land.

ALTER TABLE inventory.stocks            ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE inventory.reservations      ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE inventory.reservation_lines ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';

-- Composite key for a tenant-relative natural key (design-00009 §5/§6) ------
-- stocks.sku ('SKU-1') is a consumer-provided natural key: two tenants may legitimately each carry
-- their own 'SKU-1'. tenant_id must therefore join its primary key, or a second tenant inserting the
-- same key would collide.
--
-- reservations.id is a framework-side globally-unique UUIDv7, so its key is left single-column here —
-- and V2_4 revisits that for the same reason ordering/V1_4 does: the argument is about collisions, not
-- about references (issue-00091).
ALTER TABLE inventory.stocks DROP CONSTRAINT stocks_pkey;
ALTER TABLE inventory.stocks ADD  PRIMARY KEY (tenant_id, sku);
