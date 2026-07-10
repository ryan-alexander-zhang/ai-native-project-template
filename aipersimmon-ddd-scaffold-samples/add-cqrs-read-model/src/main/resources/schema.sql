-- Write-side table.
CREATE TABLE IF NOT EXISTS orders (
    id     VARCHAR(64) PRIMARY KEY,
    sku    VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL
);

-- Read-side table (the read model), maintained by the projection.
CREATE TABLE IF NOT EXISTS order_summary (
    order_id VARCHAR(64) PRIMARY KEY,
    sku      VARCHAR(64) NOT NULL,
    status   VARCHAR(32) NOT NULL
);
