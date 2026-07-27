-- Multi-tenancy (decision-00018 / design-00009): record which tenant each handled message
-- belonged to. tenant_id is a plain data column, NOT part of the dedup key: the key stays
-- (consumer, source, message_key), which already identifies the message globally — ce_id within
-- its ce_source — so adding tenant would not sharpen dedup and could let the same message be
-- processed once per tenant. NOT NULL with the sentinel default keeps single-tenant (N=1) and
-- pre-existing rows valid.
ALTER TABLE aipersimmon_inbox ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
