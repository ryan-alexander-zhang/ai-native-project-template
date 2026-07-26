-- design-00009: pool multi-tenancy by a tenant_id discriminator column. Every business table gets
-- tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__' — never NULL, so single-tenant is simply N=1
-- under the __root__ sentinel and existing rows migrate to it automatically. The framework's own
-- tables (outbox / inbox / process-manager / operation-log) carry their own tenant_id from the
-- aipersimmon component migrations; this migration owns only the two domain schemas.
--
-- The MyBatis-Plus tenant-line interceptor (aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables in
-- application.yml) supplies the tenant_id column on INSERT and the WHERE tenant_id = ? predicate on
-- read/update/delete for these tables; the column must exist here for that rewrite to land.

-- ordering context ----------------------------------------------------------
ALTER TABLE ordering.customers   ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE ordering.orders      ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE ordering.order_lines ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';

-- inventory context ---------------------------------------------------------
ALTER TABLE inventory.stocks            ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE inventory.reservations      ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE inventory.reservation_lines ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';

-- Composite keys for tenant-relative natural keys (design-00009 §5/§6) ------
-- customers.id ('CUST-1') and stocks.sku ('SKU-1') are consumer-provided natural keys: two tenants
-- may legitimately each have their own 'CUST-1' / 'SKU-1'. tenant_id must therefore join their
-- primary key, or a second tenant inserting the same key would collide (and, with a NULL tenant_id,
-- the composite key would be silently defeated — the §6 unique-key trap). orders.id and
-- reservations.id are framework-side globally-unique UUIDs, so their key is left single-column and
-- tenant_id is a plain data column there.
ALTER TABLE ordering.customers DROP CONSTRAINT customers_pkey;
ALTER TABLE ordering.customers ADD  PRIMARY KEY (tenant_id, id);

ALTER TABLE inventory.stocks   DROP CONSTRAINT stocks_pkey;
ALTER TABLE inventory.stocks   ADD  PRIMARY KEY (tenant_id, sku);
