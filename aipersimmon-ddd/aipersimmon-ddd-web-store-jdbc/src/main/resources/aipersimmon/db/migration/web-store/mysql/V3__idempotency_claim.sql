-- Idempotency becomes a claim, not a record of what already happened. See the PostgreSQL copy of this
-- migration for the full rationale; the column set is identical and only the dialect differs (MySQL
-- drops a primary key with DROP PRIMARY KEY and relaxes NOT NULL by restating the column type).
--
-- state          PENDING while an attempt holds the claim, COMPLETE once an outcome is stored.
-- expires_at     the claim lease while PENDING (short), the end of the retry window once COMPLETE.
-- principal      joins the primary key: a key is a value one caller invents, so without the caller
--                anyone presenting a known key reads back another user's response body.
-- fingerprint    compared, not part of the identity, so a key reused for a different request is
--                refusable rather than answered with the wrong outcome.
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN principal VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'COMPLETE';
ALTER TABLE aipersimmon_web_idempotency MODIFY COLUMN response_status INT NULL;

ALTER TABLE aipersimmon_web_idempotency DROP PRIMARY KEY;
ALTER TABLE aipersimmon_web_idempotency ADD PRIMARY KEY (tenant_id, principal, idempotency_key);

-- Keys are used once and then sit until they expire, so the per-key purge inside claim() never reaches
-- most rows. A retention job needs to find expired rows across all keys, which without this index is a
-- full scan of a table that only grows.
ALTER TABLE aipersimmon_web_idempotency ADD INDEX idx_aipersimmon_web_idempotency_expires (expires_at);
ALTER TABLE aipersimmon_web_nonce ADD INDEX idx_aipersimmon_web_nonce_expires (expires_at);
