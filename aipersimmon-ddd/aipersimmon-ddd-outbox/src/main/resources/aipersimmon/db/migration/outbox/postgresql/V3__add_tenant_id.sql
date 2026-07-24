-- Add the tenant discriminator to the outbox tables (multi-tenancy, decision-00018 / design-00009).
-- A data column only: event_id stays the natural dedup key (tenant is NOT part of the unique key).
-- Non-null with the __root__ sentinel so single-tenant rows — and any rows written before tenancy —
-- resolve to N=1 rather than NULL (a NULL tenant would silently void tenant-composite unique keys).
ALTER TABLE aipersimmon_outbox ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_dead_letter ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
