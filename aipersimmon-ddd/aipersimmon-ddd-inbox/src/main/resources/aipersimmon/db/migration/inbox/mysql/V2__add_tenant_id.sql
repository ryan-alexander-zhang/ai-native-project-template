-- Multi-tenancy (decision-00018 / design-00009): record which tenant each handled message
-- belonged to. tenant_id is a plain data column, NOT part of the dedup key: the key stays
-- (consumer, message_key) because message_key is the producer-assigned, globally-unique message id
-- (ce_id) — adding tenant would not change dedup and could let the same message be processed once
-- per tenant. NOT NULL with the sentinel default keeps single-tenant (N=1) and pre-existing rows
-- valid.
ALTER TABLE aipersimmon_inbox ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
