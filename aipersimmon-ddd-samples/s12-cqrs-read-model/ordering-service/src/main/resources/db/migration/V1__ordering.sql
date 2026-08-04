-- Three kinds of table, and telling them apart is most of what S12 is about.
--
--   1. The write model: s12_order and s12_order_line. The truth about orders.
--   2. This context's replica of another context's data: s12_product_name. Owned here, fed by the
--      catalogue's events, never written by anything else. It is not a projection and not a cache with
--      a TTL — it is reference data this context has chosen to hold a copy of.
--   3. The projection: s12_order_list. Derived, disposable, rebuildable from (1) join (2).
--
-- The inbox table comes from the framework's migrations, and only because ordering-service.yaml lists
-- `inbox` under aipersimmon.ddd.flyway.components.

CREATE TABLE s12_order (
    id          VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    placed_at   TIMESTAMPTZ  NOT NULL,
    paid_at     TIMESTAMPTZ,
    total_minor BIGINT       NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 1
);

CREATE TABLE s12_order_line (
    id               VARCHAR(64)  PRIMARY KEY,
    order_id         VARCHAR(36)  NOT NULL REFERENCES s12_order (id),
    sku              VARCHAR(64)  NOT NULL,
    quantity         INTEGER      NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    -- The name as it was when the customer bought it. A business fact, frozen forever: the invoice must
    -- say what the customer agreed to buy, and a later rename in the catalogue must not rewrite history.
    -- Compare with s12_order_list.display_summary below, which must show the *current* name. Same value,
    -- two opposite requirements — which is why one is copied into the write model and the other is
    -- maintained in a projection.
    name_at_purchase VARCHAR(200) NOT NULL
);

CREATE INDEX s12_order_line_order ON s12_order_line (order_id);
-- The rename path needs to find every order containing a sku, so this index is load-bearing rather than
-- speculative: without it a rename scans every line ever written.
CREATE INDEX s12_order_line_sku ON s12_order_line (sku);

CREATE TABLE s12_product_name (
    sku        VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- Two of the catalogue's three products, on purpose. sku-monitor is absent so that "this context has
-- never heard of that product" is a real, reachable state rather than a hypothetical — an order for it
-- shows the sku until the catalogue's event arrives. A real deployment bootstraps this table by replaying
-- the catalogue's events or by a one-time import; seeding it here stands in for that.
INSERT INTO s12_product_name (sku, name, updated_at) VALUES
  ('sku-keyboard', 'Mechanical Keyboard', now()),
  ('sku-mouse',    'Wireless Mouse',      now());

CREATE TABLE s12_order_list (
    order_id        VARCHAR(36)  PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    placed_at       TIMESTAMPTZ  NOT NULL,
    paid_at         TIMESTAMPTZ,
    line_count      INTEGER      NOT NULL,
    total_minor     BIGINT       NOT NULL,
    display_summary VARCHAR(600) NOT NULL,
    -- When this row was last recomputed. The only honest way to answer "how stale is this list", and the
    -- reason it is a column rather than a log line.
    projected_at    TIMESTAMPTZ  NOT NULL
);

-- The index the list page actually uses. A projection whose access path is not obvious from its own DDL
-- is a projection nobody can tell is being used correctly.
CREATE INDEX s12_order_list_customer ON s12_order_list (customer_id, placed_at DESC);
