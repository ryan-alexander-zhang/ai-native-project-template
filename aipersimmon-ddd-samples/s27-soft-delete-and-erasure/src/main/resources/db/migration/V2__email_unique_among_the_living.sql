-- The fix for "a suppressed customer holds their address for ever".
--
-- A partial unique index: uniqueness applies only among rows the application can see. A suppressed row
-- keeps its email in the table and stops constraining anybody, which is exactly what "as far as the
-- application is concerned this row does not exist" ought to mean.
--
-- Order matters. The new index is created before the old one is dropped so the column is never briefly
-- unconstrained — a window in which two live rows could take the same address, which no later migration
-- could repair.
CREATE UNIQUE INDEX uq_s27_customer_email_live ON s27_customer (email) WHERE deleted = FALSE;

DROP INDEX uq_s27_customer_email;

-- PORTABILITY, stated rather than discovered later: this is a PostgreSQL partial index. MySQL has no
-- such thing, and the usual substitute is to put the delete marker in the key itself —
-- UNIQUE (email, deleted_marker) where the marker is a *sentinel* for live rows and the row id once
-- deleted. It must not be NULL for live rows: MySQL (like PostgreSQL) treats NULLs as distinct in a
-- unique index, so UNIQUE (email, deleted_at) with NULL meaning "alive" would permit two live rows with
-- the same address — the opposite of the intent, and green in every test that only deletes one row.
