-- issue-00083: materialise the order total, so the rule that computes it has one definition.
--
-- The write side owns it (Order.total() = Σ line.subtotal(), with Money.plus refusing to add
-- across currencies). The read side computed it again in SQL:
--
--     COALESCE(SUM(l.quantity * l.unit_minor), 0) AS totalMinor,
--     MAX(l.currency)                             AS currency
--
-- Two implementations of one business rule, and only one of them knows its preconditions. They
-- agree today. The day the write side grows line discounts, tax, or a free-gift line — anything
-- where quantity × unit stops being the subtotal — the SQL will not follow, and no test will fail,
-- because the assertions use numbers both sides happen to produce.
--
-- MAX(currency) is the sharper half. It is not a rule, it is a guess: it expresses nothing about
-- "an order has one currency", it just picks one. The domain guarantees that (Order.total() throws
-- on mixed currencies) but SQL cannot see the guarantee, so on mixed rows SUM would add USD to EUR
-- and label the result with whichever code sorted highest — the write side refuses the data, the
-- read side displays it. COALESCE(..., 0) had the same shape: a line-less order is illegal enough
-- for Order.total() to throw, and the list showed 0.
--
-- The step that was missed is not "the read side should rebuild the aggregate" — it should not.
-- It is that a derived value which stops changing can be FROZEN AT WRITE TIME instead of
-- recomputed on every read. An order's line set is only ever set at placement, so its total is
-- exactly that kind of value.
--
-- The list query loses its LEFT JOIN and GROUP BY as a result, which is also the cheapest it has
-- ever been: a single-table range scan over orders_by_customer_newest_first (V4).

-- Added nullable, backfilled, then constrained — the three-step every "add a NOT NULL column to a
-- populated table" needs, and worth showing even where this scaffold's table is empty.
ALTER TABLE ordering.orders ADD COLUMN total_minor BIGINT;
ALTER TABLE ordering.orders ADD COLUMN currency    VARCHAR(3);

-- The backfill reproduces the old read-side expression deliberately: for rows written before this
-- migration it IS the historical total, and reproducing it is how existing data keeps its meaning.
-- New rows come from Order.total() and never take this path.
UPDATE ordering.orders o
   SET total_minor = COALESCE(
           (SELECT SUM(l.quantity * l.unit_minor)
              FROM ordering.order_lines l
             WHERE l.tenant_id = o.tenant_id AND l.order_id = o.id), 0),
       currency = COALESCE(
           (SELECT MAX(l.currency)
              FROM ordering.order_lines l
             WHERE l.tenant_id = o.tenant_id AND l.order_id = o.id), 'USD');

ALTER TABLE ordering.orders ALTER COLUMN total_minor SET NOT NULL;
ALTER TABLE ordering.orders ALTER COLUMN currency    SET NOT NULL;
