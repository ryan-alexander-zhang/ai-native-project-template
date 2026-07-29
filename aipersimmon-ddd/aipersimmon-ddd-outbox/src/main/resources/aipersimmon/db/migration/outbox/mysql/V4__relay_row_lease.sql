-- Per-row lease for the relay's claim (MySQL 8+). The relay used to rely solely on a ShedLock lock
-- on its schedule, which meant a killed instance left that lock held for its whole lease and no
-- other instance polled at all -- delivery stopped everywhere. With the lease on the row instead,
-- every instance polls and they claim disjoint rows; a killed instance only holds back the rows it
-- had claimed, and only until lease_until passes.
--
-- MySQL has no ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS, so these are unguarded.
-- Flyway's history table is what makes them run exactly once.
--
-- One index only: after claiming, the relay reads back exactly the rows it won by lease_token, so
-- that lookup must not scan the table. The claimable predicate itself is still served by
-- idx_aipersimmon_outbox_unsent and idx_aipersimmon_outbox_subject_order; lease_until is
-- deliberately not indexed, because this is a write-hot table and every extra index is paid on
-- insert by the business transaction.
ALTER TABLE aipersimmon_outbox
    ADD COLUMN lease_owner VARCHAR(255),
    ADD COLUMN lease_token VARCHAR(64),
    ADD COLUMN lease_until DATETIME(3),
    ADD INDEX idx_aipersimmon_outbox_lease (lease_token);
