-- The write model: one small aggregate with an optimistic-lock version.
CREATE TABLE s26_product (
    sku         VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    price_cents BIGINT       NOT NULL,
    version     BIGINT       NOT NULL
);

-- The facts that make a product detail expensive to read. Append-only, and the only source of truth
-- for how much of a product has sold.
CREATE TABLE s26_order_line (
    id        VARCHAR(64) PRIMARY KEY,
    sku       VARCHAR(64) NOT NULL,
    quantity  INT         NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL
);
-- Composite, sku first: every read of the sales figure is "this product, this window".
CREATE INDEX idx_s26_order_line_sku_placed_at ON s26_order_line (sku, placed_at);

-- The projection: the same number the detail query computes, maintained at write time instead.
--
-- It exists next to the cache on purpose. The cache and this table hold the same value and are not
-- interchangeable — a table can be sorted, filtered and paged over, and can be rebuilt from
-- s26_order_line; a cache entry can only be fetched by the key someone already knew.
CREATE TABLE s26_product_sales (
    sku           VARCHAR(64) PRIMARY KEY,
    sold_recently BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);
-- Descending, because the only query this table answers is "the top N", and an index that matches the
-- ORDER BY is the difference between reading N rows and sorting the whole catalogue.
CREATE INDEX idx_s26_product_sales_sold_recently ON s26_product_sales (sold_recently DESC);
