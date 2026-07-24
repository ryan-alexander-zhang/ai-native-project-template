-- Fixture table for the tenant-line interceptor integration test: a stand-in for a consumer's own
-- tenant-scoped domain table, opted into aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables.
CREATE TABLE IF NOT EXISTS t18_thing (
    id        VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64)  NOT NULL,
    name      VARCHAR(128) NOT NULL
);
