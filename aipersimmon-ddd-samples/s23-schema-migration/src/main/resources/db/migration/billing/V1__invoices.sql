-- The billing context, in the same database as ordering, with a V1 of its own.
--
-- That is the whole reason this context exists in the sample. Two contexts sharing a database is a normal
-- intermediate state — a modular monolith on the way to being split, or one that is staying — and the
-- question it raises is not "how do they share tables" (they must not) but "whose migration is V2".
--
-- The answer is that neither is: each context has its own location and its own history table, so each
-- numbers from V1 and they never meet. The alternative — one shared version space — makes every schema
-- change a negotiation between teams, and makes a cherry-picked release impossible to apply.
CREATE TABLE s23_invoice (
    id       VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT  NOT NULL,
    version  BIGINT      NOT NULL DEFAULT 1
);

-- No foreign key to s23_order, and that is not an oversight. A cross-context foreign key is a
-- deployment-time coupling: it makes the two tables un-splittable, it makes billing's migration fail if
-- ordering's has not run, and it lets a delete in one context be refused by a rule the other context
-- owns. Billing keeps ordering's id as a value, and nothing enforces it but the flow that wrote it.
CREATE INDEX s23_invoice_order ON s23_invoice (order_id);
