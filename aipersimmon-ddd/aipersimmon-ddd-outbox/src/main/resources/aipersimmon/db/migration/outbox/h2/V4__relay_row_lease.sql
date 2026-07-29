-- Per-row lease for the relay's claim (H2). The relay used to rely solely on a ShedLock lock on
-- its schedule, which meant a killed instance left that lock held for its whole lease and no other
-- instance polled at all -- delivery stopped everywhere. With the lease on the row instead, every
-- instance polls and they claim disjoint rows; a killed instance only holds back the rows it had
-- claimed, and only until lease_until passes.
ALTER TABLE aipersimmon_outbox ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(255);
ALTER TABLE aipersimmon_outbox ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64);
ALTER TABLE aipersimmon_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;

-- The only index this needs: after claiming, the relay reads back exactly the rows it won by
-- lease_token, so that lookup must not scan the table. The claimable predicate itself is still
-- served by idx_aipersimmon_outbox_unsent and idx_aipersimmon_outbox_subject_order; lease_until is
-- deliberately not indexed, because this is a write-hot table and every extra index is paid on
-- insert by the business transaction.
CREATE INDEX IF NOT EXISTS idx_aipersimmon_outbox_lease
    ON aipersimmon_outbox (lease_token);
