-- The monolith, exactly as a team would inherit it. Nothing here is a strawman: every property that makes
-- it awkward is a property real legacy schemas have, and each one is a thing the library's defaults assume
-- away.
--
--   * BIGSERIAL primary keys — the database assigns identity, not the application;
--   * no version column anywhere — last writer wins, and always has;
--   * no tenant column;
--   * a foreign key from the table we are about to strangle into the table we are not;
--   * updated_at maintained by hand in every UPDATE, inconsistently;
--   * status as free text, with values that are not quite an enum.
--
-- Table names have no prefix and no context. That is also authentic, and it is the first thing that makes
-- "who owns this table" unanswerable.

CREATE TABLE legacy_orders (
  id           BIGSERIAL    PRIMARY KEY,
  customer_ref VARCHAR(64)  NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  total_cents  BIGINT       NOT NULL,
  notes        TEXT,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE legacy_order_items (
  id           BIGSERIAL    PRIMARY KEY,
  order_id     BIGINT       NOT NULL REFERENCES legacy_orders (id),
  sku          VARCHAR(64)  NOT NULL,
  qty          INT          NOT NULL,
  unit_cents   BIGINT       NOT NULL
);

-- The table this sample strangles. Chosen by measurement rather than by taste — see LegacyFanInTest.
CREATE TABLE legacy_refunds (
  id           BIGSERIAL    PRIMARY KEY,
  order_id     BIGINT       NOT NULL REFERENCES legacy_orders (id),
  amount_cents BIGINT       NOT NULL,
  reason       VARCHAR(255),
  state        VARCHAR(16)  NOT NULL,
  approved_by  VARCHAR(64),
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_legacy_refunds_order ON legacy_refunds (order_id);
