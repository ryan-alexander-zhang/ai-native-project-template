-- Enough rows that `spring-boot:run` has something to export, and few enough that the migration is
-- instant. The tests seed their own volumes; nothing here is load-bearing for them.
INSERT INTO s28_export_row (period, order_ref, amount_cents, note)
SELECT '2026-05', 'ORD-' || lpad(g::text, 8, '0'), (g * 137) % 999_99, 'settled'
FROM generate_series(1, 500) g;
