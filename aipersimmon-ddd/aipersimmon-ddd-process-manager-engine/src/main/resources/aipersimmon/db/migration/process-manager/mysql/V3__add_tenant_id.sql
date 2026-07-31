-- Multi-tenancy: add the tenant discriminator to all four
-- process tables. The single global relay/claim/deadline pollers still scan every tenant, but
-- each row now carries its owning tenant so the durable store-and-forward hop can reconstruct
-- the command context under the right tenant and enforce isolation. tenant_id is NOT NULL with
-- the sentinel default so single-tenant (N=1) deployments and pre-existing rows are unaffected
-- and no unique key is silently voided by a NULL.
ALTER TABLE aipersimmon_process_instance ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_process_transition ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_process_effect ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_process_deadline ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';

-- business_key is a tenant-relative natural key: two tenants may legitimately reuse the same
-- (process_type, business_key), so tenant_id joins the uniqueness scope. Leaving it out would let
-- one tenant's start collide with another's — a cross-tenant data leak. The other three tables
-- take tenant_id as a plain data/predicate column and keep their existing constraints. In MySQL a
-- named UNIQUE constraint is an index, so it is dropped with DROP INDEX.
ALTER TABLE aipersimmon_process_instance DROP INDEX uq_process_instance_business;
ALTER TABLE aipersimmon_process_instance
    ADD CONSTRAINT uq_process_instance_business UNIQUE (tenant_id, process_type, business_key);
