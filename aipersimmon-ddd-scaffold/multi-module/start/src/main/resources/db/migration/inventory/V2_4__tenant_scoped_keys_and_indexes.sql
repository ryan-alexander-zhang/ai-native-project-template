-- issue-00091 + issue-00073: the two things V2_2 left behind, for the inventory context. The same
-- correction ordering/V1_4 makes, for the same reason — see that file's part 1 for the full argument
-- about why a globally-unique id justifies a single-column PRIMARY key but not a single-column FOREIGN
-- key.

-- Part 1 — the foreign key must carry the tenant (issue-00091) ---------------
-- Without it the database accepts a reservation_lines row in tenant 'acme' hanging off a reservations
-- row in tenant 'globex'. The interceptor prevents it in the application; a data-fix script, a psql
-- prompt, or the tests' own raw JdbcTemplate do not go through the interceptor.

ALTER TABLE inventory.reservation_lines DROP CONSTRAINT reservation_lines_reservation_id_fkey;

ALTER TABLE inventory.reservations DROP CONSTRAINT reservations_pkey;
ALTER TABLE inventory.reservations ADD  PRIMARY KEY (tenant_id, id);

ALTER TABLE inventory.reservation_lines
    ADD CONSTRAINT reservation_lines_reservation_fkey
    FOREIGN KEY (tenant_id, reservation_id) REFERENCES inventory.reservations (tenant_id, id);

-- Part 2 — the indexes those queries need (issue-00073) ----------------------

-- PostgreSQL indexes the parent side of a foreign key but never the child side. reservation_lines is
-- read and rewritten by its parent id on the write path, so it needs the index the constraint does not
-- bring with it.
CREATE INDEX reservation_lines_by_reservation
    ON inventory.reservation_lines (tenant_id, reservation_id);

-- Finding a reservation by the order it holds stock for: the compensation path (release then cancel)
-- and any operator asking "what is still held for this order?".
CREATE INDEX reservations_by_order
    ON inventory.reservations (tenant_id, order_id);
