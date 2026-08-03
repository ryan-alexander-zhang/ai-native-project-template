-- The root table. Two mapping choices are visible here:
--   * shipping_address is one JSONB column, because nothing queries a line of an address;
--   * total_currency / total_amount_cents are flattened, because the total is queried and summed.
CREATE TABLE s17_order (
    id                VARCHAR(36) PRIMARY KEY,
    customer_id       VARCHAR(64) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    -- Nullable, and the interesting one: emptying it in the domain has to reach the database.
    note              VARCHAR(255),
    shipping_address  JSONB       NOT NULL,
    total_currency    VARCHAR(3)  NOT NULL,
    total_amount_cents BIGINT     NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 1
);

-- Child rows keep their own identity, so a write can update the one line that changed.
CREATE TABLE s17_order_line (
    id                      VARCHAR(64) PRIMARY KEY,
    order_id                VARCHAR(36) NOT NULL REFERENCES s17_order (id),
    sku                     VARCHAR(64) NOT NULL,
    unit_price_currency     VARCHAR(3)  NOT NULL,
    unit_price_amount_cents BIGINT      NOT NULL,
    quantity                INTEGER     NOT NULL
);

CREATE INDEX idx_s17_order_line_order ON s17_order_line (order_id);
