-- EXPAND. Step one of three, and the only one that may be deployed while the old code is still running.
--
-- Three properties make this step safe, and dropping any one of them turns a routine deploy into an
-- outage:
--
--   1. The new columns are NULLABLE. The version of the application that is still running knows nothing
--      about them and inserts rows without them. A NOT NULL here would make every insert from the old
--      code fail the moment this migration lands — during the deploy, on the instances that have not
--      been replaced yet.
--   2. The old column is UNTOUCHED. Old code still reads and writes ship_to; new code reads the split
--      columns. Both are correct at the same time, which is the entire point of expand/contract: there
--      is no instant at which the schema matches exactly one version of the code.
--   3. The backfill is pure restatement. It splits bytes that are already in the row. No rule, no
--      lookup, no decision — which is what makes it legitimate as SQL. A backfill that had to decide
--      something would belong in a command; see V4.
--
-- The naive split (everything before the first comma is the street, the rest is the city) is deliberate
-- and is what makes this realistic: a real free-text column contains rows this cannot parse, and the
-- COALESCE is where they land. A migration that assumed its own data was clean would fail at 3am on the
-- one row entered by hand in 2019.
ALTER TABLE s23_order ADD COLUMN ship_to_street VARCHAR(128);
ALTER TABLE s23_order ADD COLUMN ship_to_city   VARCHAR(64);

UPDATE s23_order
SET ship_to_street = TRIM(SPLIT_PART(ship_to, ',', 1)),
    ship_to_city   = COALESCE(NULLIF(TRIM(SPLIT_PART(ship_to, ',', 2)), ''), 'UNKNOWN')
WHERE ship_to_street IS NULL;
