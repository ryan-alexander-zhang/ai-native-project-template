-- Demo data for the dev profile only (issue-00072). This location is on spring.flyway.locations in
-- application-dev.yml and absent from application-prod.yml, which is the entire mechanism: a
-- production database never sees a customer named Acme.
--
-- afterMigrate, not V5: a versioned migration runs once per database and is then frozen by its
-- checksum, which is the wrong shape for sample data. A callback runs after every migrate, so the
-- rows come back if someone deletes them while poking at the demo, and re-running it must
-- therefore be harmless — hence ON CONFLICT DO NOTHING on every statement. Note the conflict
-- targets are the COMPOSITE keys: (tenant_id, id) and (tenant_id, sku) since V2.
--
-- TWO TENANTS, because the demo has two entry points and they resolve their tenant differently
-- (issue-00096):
--
--   '__root__'  the sentinel the command bus binds when nothing else is bound — how the
--               acceptance tests that dispatch straight on the bus, with no HTTP request and no
--               TenantContext, end up scoped. It CANNOT be requested over HTTP: Tenants.of()
--               rejects the reserved '__' prefix precisely so a client can never name a framework
--               sentinel, so a curl carrying X-Tenant-Id: __root__ is a 400.
--   'demo'      an ordinary tenant, which is what the README quickstart uses. Nothing reserved
--               about it; it is simply a tenant that exists and has stock.
--
-- Seeding both is not redundancy — it is the same natural keys under two tenants, which is the
-- composite primary key (tenant_id, id) doing its job in the smallest possible example. 'acme' and
-- 'globex' are deliberately NOT seeded here: the multi-tenant tests own those and set their own
-- credit and stock levels, and rows here would win the ON CONFLICT and silently change their
-- fixtures.

INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)
VALUES ('CUST-1', 'Acme', 100000, 'USD', '__root__'),
       ('CUST-1', 'Acme', 100000, 'USD', 'demo')
ON CONFLICT (tenant_id, id) DO NOTHING;

-- SKU-RESTRICTED is stocked like any other, but ordering's ManualReviewPolicy flags it, so an
-- order containing it is held for manual review before any reservation — the review-path demo.
INSERT INTO inventory.stocks (sku, available, tenant_id)
VALUES ('SKU-1', 10, '__root__'),
       ('SKU-2', 5, '__root__'),
       ('SKU-RESTRICTED', 10, '__root__'),
       ('SKU-1', 10, 'demo'),
       ('SKU-2', 5, 'demo'),
       ('SKU-RESTRICTED', 10, 'demo')
ON CONFLICT (tenant_id, sku) DO NOTHING;
