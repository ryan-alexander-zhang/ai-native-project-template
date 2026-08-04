-- The new context's schema, as its own migration.
--
-- A separate migration rather than an edit to V1, and not only because V1 has already run somewhere: this file is
-- the record that the coupons context was added on a particular day, and it is the file that gets moved when the
-- context becomes its own deployment unit. A new context that scatters its columns through the existing
-- migrations has nothing to move.
--
-- Note what is NOT here: no foreign key to s24_ordering_order. A coupon is redeemed *for* an order, and the
-- redemption row names the order id — as an opaque string, with no constraint. The constraint would be free
-- today, correct today, and the single hardest thing to remove on the day the context is split out. Referential
-- integrity across a context boundary is the boundary not existing.

CREATE TABLE s24_coupons_coupon (
  code            VARCHAR(32) PRIMARY KEY,
  kind            VARCHAR(16) NOT NULL,
  value_minor     BIGINT,
  percent_off     INT,
  currency        VARCHAR(3)  NOT NULL,
  valid_from      TIMESTAMPTZ NOT NULL,
  valid_until     TIMESTAMPTZ NOT NULL,
  max_redemptions INT         NOT NULL,
  redemptions     INT         NOT NULL DEFAULT 0,
  version         BIGINT      NOT NULL
);

CREATE TABLE s24_coupons_redemption (
  coupon_code VARCHAR(32) NOT NULL,
  order_id    VARCHAR(64) NOT NULL,
  amount_minor BIGINT     NOT NULL,
  redeemed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (coupon_code, order_id)
);
