-- This service's own tables. The framework's outbox tables are NOT here: they come from the
-- framework's migrations, and only because application.yaml lists `outbox` under
-- aipersimmon.ddd.flyway.components. Being on the classpath is not being applied.
--
-- tenant_id (S13) is in V1 rather than in a migration that adds it, because this sample is greenfield.
-- Adopting tenancy on a live table is a different job: backfill the column, make it NOT NULL, and
-- widen every unique key that must now be per-tenant — before enabling the interceptor, never after.
-- That sequence is S23's subject.
CREATE TABLE s04_order (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1
);

CREATE TABLE s04_order_line (
    id        VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    order_id  VARCHAR(36) NOT NULL REFERENCES s04_order (id),
    sku       VARCHAR(64) NOT NULL,
    quantity  INTEGER     NOT NULL
);

CREATE INDEX s04_order_line_order ON s04_order_line (order_id);

-- A tenant-scoped read carries tenant_id in every predicate, so the index leads with it.
CREATE INDEX s04_order_tenant ON s04_order (tenant_id, id);
