-- The service as it was before the new context arrived: two contexts, two table prefixes.
--
-- The prefix is not cosmetic and it is not a naming convention for its own sake. It is the only thing that makes
-- "which context owns this table" a mechanical question — and therefore the only thing that makes "has anybody
-- written a query across the boundary" answerable. `TableOwnershipTest` reads every mapper's SQL and checks it,
-- which is the single most useful measurement for whether a context could still be split out.

-- ---------------------------------------------------------------------------------------------------
-- ordering
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s24_ordering_order (
  id            VARCHAR(64)  PRIMARY KEY,
  customer_id   VARCHAR(64)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  gross_minor   BIGINT       NOT NULL,
  discount_minor BIGINT      NOT NULL,
  currency      VARCHAR(3)   NOT NULL,
  coupon_code   VARCHAR(32),
  placed_at     TIMESTAMPTZ  NOT NULL,
  version       BIGINT       NOT NULL
);

CREATE TABLE s24_ordering_order_line (
  order_id    VARCHAR(64) NOT NULL,
  line_no     INT         NOT NULL,
  sku         VARCHAR(64) NOT NULL,
  quantity    INT         NOT NULL,
  unit_minor  BIGINT      NOT NULL,
  PRIMARY KEY (order_id, line_no)
);

-- ---------------------------------------------------------------------------------------------------
-- inventory
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s24_inventory_stock_item (
  sku       VARCHAR(64) PRIMARY KEY,
  on_hand   INT         NOT NULL,
  reserved  INT         NOT NULL,
  version   BIGINT      NOT NULL
);
