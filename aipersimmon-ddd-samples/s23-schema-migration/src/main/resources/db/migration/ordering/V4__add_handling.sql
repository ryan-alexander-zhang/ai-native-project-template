-- A column whose value this file is deliberately NOT going to compute.
--
-- `handling` is decided by a rule: a large quantity, or a destination the carrier treats as remote, means
-- the order is handled differently. That rule lives in the aggregate, because it is the same rule new
-- orders are decided by — and a CASE WHEN here would be a second copy of it, in SQL, maintained by
-- whoever last touched the database.
--
-- So this migration adds the column, nullable, and stops. Filling it is a backfill through the command
-- channel (BackfillHandling), which reuses the aggregate's own rule and announces what it changed. The
-- rule for choosing between the two is in the README, and it is short: restating bytes already in the row
-- is SQL; deciding anything, or having to tell anyone, is a command.
--
-- NULL therefore means "not yet decided", which is a state the read side has to tolerate for as long as
-- the backfill takes. That is not a flaw in the plan; it is the plan. A backfill that must be atomic with
-- its migration is a backfill that holds a lock on the whole table.
ALTER TABLE s23_order ADD COLUMN handling VARCHAR(16);

-- The backfill scans for rows still undecided, so it needs to find them cheaply. Partial index, because
-- the population it serves shrinks to nothing and then the index costs nothing to keep.
CREATE INDEX s23_order_undecided_handling ON s23_order (id) WHERE handling IS NULL;
