-- CONTRACT. Step three, and it may not be deployed until every instance that wrote ship_to is gone.
--
-- Step TWO is not in this directory, because it is not a migration: it is the release of the application
-- that stops writing the old column and starts writing the new ones. That is the part of expand/contract
-- that a repository of SQL files cannot express, and the part teams skip — the two migrations look like a
-- pair and get deployed together, which is precisely the outage the pattern exists to avoid.
--
-- What makes this step irreversible in the way that matters: a rollback of the APPLICATION to the version
-- that read ship_to is no longer possible, because the data is gone. So the order is not "expand, contract,
-- done" but "expand, deploy, WAIT until you are sure you will not roll back, contract". The waiting is the
-- step, and it is measured in days rather than minutes.
--
-- Only now may the columns become NOT NULL: at this point nothing writes a row without them.
UPDATE s23_order SET ship_to_city = 'UNKNOWN' WHERE ship_to_city IS NULL;
UPDATE s23_order SET ship_to_street = 'UNKNOWN' WHERE ship_to_street IS NULL;

ALTER TABLE s23_order ALTER COLUMN ship_to_street SET NOT NULL;
ALTER TABLE s23_order ALTER COLUMN ship_to_city   SET NOT NULL;

ALTER TABLE s23_order DROP COLUMN ship_to;
