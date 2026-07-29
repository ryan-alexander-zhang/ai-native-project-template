-- Demo data for the dev profile only (issue-00072). This location is on spring.flyway.locations in
-- application-dev.yml and absent from application-prod.yml, which is the entire mechanism: a
-- production database never sees a customer named Acme.
--
-- afterMigrate, not a versioned migration: those run once per database and are then frozen by their
-- checksum, which is the wrong shape for sample data. A callback runs after every migrate, so the
-- rows come back if someone deletes them while poking at the demo, and re-running it must
-- therefore be harmless — hence ON CONFLICT DO NOTHING on every statement. Note the conflict
-- targets are the COMPOSITE keys: (tenant_id, id) and (tenant_id, sku), per ordering/V1_2 and inventory/V2_2.
--
-- ONE TENANT, 'demo' — an ordinary tenant that exists and has stock. Both demo entry points use it:
-- the README quickstart sends 'X-Tenant-Id: demo', and the acceptance tests that dispatch straight on
-- the command bus bind it for the test thread (see BoundTenant), standing in for the edge.
--
-- These rows used to be seeded under the '__root__' sentinel as well, because an unbound dispatch
-- silently fell back to it — so bus-driven tests landed in the sentinel bucket without saying so.
-- With multi-tenancy enabled the framework now refuses to run tenant-scoped work on an unbound
-- thread (issue-00099), so a caller that skips the edge has to name its tenant; there is nothing left
-- for a sentinel copy to serve. The sentinel still exists, and every row still carries a tenant — it
-- is just what a single-tenant (N=1) deployment uses, not a fallback a multi-tenant one drifts into.
--
-- 'acme' and 'globex' are deliberately NOT seeded here: the multi-tenant tests own those and set
-- their own credit and stock levels, and rows here would win the ON CONFLICT and silently change
-- their fixtures.

INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)
VALUES ('CUST-1', 'Acme', 100000, 'USD', 'demo')
ON CONFLICT (tenant_id, id) DO NOTHING;

-- SKU-RESTRICTED is stocked like any other, but ordering's ManualReviewPolicy flags it, so an
-- order containing it is held for manual review before any reservation — the review-path demo.
INSERT INTO inventory.stocks (sku, available, tenant_id)
VALUES ('SKU-1', 10, 'demo'),
       ('SKU-2', 5, 'demo'),
       ('SKU-RESTRICTED', 10, 'demo')
ON CONFLICT (tenant_id, sku) DO NOTHING;
