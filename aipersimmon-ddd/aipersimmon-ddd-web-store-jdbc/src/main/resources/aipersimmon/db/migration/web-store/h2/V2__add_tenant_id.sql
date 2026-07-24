-- Multi-tenancy (decision-00018 / design-00009): the idempotency key, nonce, and rate-limit
-- bucket key are all CLIENT-provided (tenant-relative), so tenant_id joins each primary key rather
-- than being a mere data column — two tenants may legitimately send the same Idempotency-Key or
-- reuse a bucket identifier, and without tenant in the key one tenant could read back another's
-- stored response (a cross-tenant leak) or share a rate-limit counter. tenant_id is NOT NULL with
-- the sentinel default so single-tenant (N=1) and pre-existing rows stay valid and unique.
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_web_idempotency DROP PRIMARY KEY;
ALTER TABLE aipersimmon_web_idempotency ADD PRIMARY KEY (tenant_id, idempotency_key);

ALTER TABLE aipersimmon_web_nonce ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_web_nonce DROP PRIMARY KEY;
ALTER TABLE aipersimmon_web_nonce ADD PRIMARY KEY (tenant_id, nonce);

ALTER TABLE aipersimmon_web_rate_limit ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__';
ALTER TABLE aipersimmon_web_rate_limit DROP PRIMARY KEY;
ALTER TABLE aipersimmon_web_rate_limit ADD PRIMARY KEY (tenant_id, bucket_key, window_start);
