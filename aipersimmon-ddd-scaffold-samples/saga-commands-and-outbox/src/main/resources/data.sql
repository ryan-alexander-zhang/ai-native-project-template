-- Seed stock. SKU-1 reserves normally; BOOM-SKU is used to show that a handler
-- failure rolls back both the stock change and the outbox row.
INSERT INTO stock (sku, available) VALUES ('SKU-1', 10);
INSERT INTO stock (sku, available) VALUES ('BOOM-SKU', 10);
