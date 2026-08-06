-- Idempotency becomes a claim, not a record of what already happened.
--
-- Before this, the filter looked the key up, ran the request, then saved the response. Two concurrent
-- first attempts both missed the lookup and both executed; the atomic save only decided whose response
-- was kept, after both side effects had committed — which is the double charge the key exists to
-- prevent. A row is now inserted BEFORE execution, so the primary key serialises the attempts.
--
-- state          PENDING while an attempt holds the claim, COMPLETE once an outcome is stored. The
--                response columns become nullable because a PENDING row has no response yet.
-- expires_at     re-used as the claim lease while PENDING (short) and as the end of the retry window
--                once COMPLETE (long). An attempt that dies mid-request therefore frees the key when
--                its lease passes, instead of blocking it for the whole retention period.
-- principal      joins the primary key. The key alone is not an identity: it is a value one caller
--                invents, so without the caller anyone presenting a known key reads back the response
--                body of another user's request. Empty string for unauthenticated endpoints, where
--                tenant is as far as identity goes.
-- fingerprint    a digest of what was requested. Not part of the identity — compared against it, so
--                reusing one key for a different request is refusable (422) rather than answered with
--                the wrong stored outcome.
--
-- Existing rows are completed responses under no principal, which is what they were.
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN principal VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE aipersimmon_web_idempotency ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'COMPLETE';
ALTER TABLE aipersimmon_web_idempotency ALTER COLUMN response_status DROP NOT NULL;

ALTER TABLE aipersimmon_web_idempotency DROP CONSTRAINT aipersimmon_web_idempotency_pkey;
ALTER TABLE aipersimmon_web_idempotency ADD PRIMARY KEY (tenant_id, principal, idempotency_key);

-- Keys are used once and then sit until they expire, so nothing rewrites them and the per-key purge
-- inside claim() never reaches most rows. A retention job needs to find expired rows across all keys,
-- which without this index is a full scan of a table that only grows.
CREATE INDEX IF NOT EXISTS idx_aipersimmon_web_idempotency_expires
    ON aipersimmon_web_idempotency (expires_at);
CREATE INDEX IF NOT EXISTS idx_aipersimmon_web_nonce_expires
    ON aipersimmon_web_nonce (expires_at);
