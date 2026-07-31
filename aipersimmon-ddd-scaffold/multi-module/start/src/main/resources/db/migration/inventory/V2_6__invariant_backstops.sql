-- issue-00146: the last line of defence for inventory's flagship rule (see ordering/V1_7 for the
-- full argument — V1_4/V2_4 made it for tenant isolation, this applies it to the invariants).

-- The anti-oversell rule itself. Until now it was enforced entirely by the application's
-- optimistic lock, which a raw write never meets; a bypassing UPDATE could drive available
-- negative and every subsequent reservation would reason from a corrupt figure.
ALTER TABLE inventory.stocks
    ADD CONSTRAINT stocks_available_non_negative CHECK (available >= 0);

-- The rule Reservation's constructor enforces (a hold of zero or less is corrupt state the
-- release path explodes on, two transactions from its cause), mirrored where no constructor runs.
ALTER TABLE inventory.reservation_lines
    ADD CONSTRAINT reservation_lines_quantity_positive CHECK (quantity > 0);
