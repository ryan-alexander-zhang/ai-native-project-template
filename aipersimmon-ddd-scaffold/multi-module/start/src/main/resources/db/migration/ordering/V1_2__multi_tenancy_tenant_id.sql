-- design-00009: pool multi-tenancy by a tenant_id discriminator column, for the ordering context.
--
-- Every business table gets tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__' — never NULL, so
-- single-tenant is simply N=1 under the __root__ sentinel and existing rows migrate to it
-- automatically. The framework's own tables (outbox / inbox / process-manager / operation-log) carry
-- their own tenant_id from the aipersimmon component migrations; this migration owns only schema
-- `ordering`.
--
-- The MyBatis-Plus tenant-line interceptor (aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables in
-- application.yml) supplies the tenant_id column on INSERT and the WHERE tenant_id = ? predicate on
-- read/update/delete for these tables; the column must exist here for that rewrite to land.

ALTER TABLE ordering.customers   ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE ordering.orders      ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE ordering.order_lines ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';

-- Composite key for a tenant-relative natural key (design-00009 §5/§6) ------
-- customers.id ('CUST-1') is a consumer-provided natural key: two tenants may legitimately each have
-- their own 'CUST-1'. tenant_id must therefore join its primary key, or a second tenant inserting the
-- same key would collide (and, with a NULL tenant_id, the composite key would be silently defeated —
-- the §6 unique-key trap).
--
-- orders.id is a framework-side globally-unique UUIDv7, so it cannot collide across tenants and its
-- key is left single-column here. That argument is sound about COLLISIONS and was wrongly read as
-- sound about REFERENCES — it explains why two tenants cannot pick the same order id, not why a child
-- row may point at a parent in another tenant. V1_4 corrects it (issue-00091).
ALTER TABLE ordering.customers DROP CONSTRAINT customers_pkey;
ALTER TABLE ordering.customers ADD  PRIMARY KEY (tenant_id, id);
