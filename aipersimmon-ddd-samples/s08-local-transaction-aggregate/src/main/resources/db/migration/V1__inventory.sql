CREATE TABLE s08_stock (
    sku       VARCHAR(64) PRIMARY KEY,
    available INTEGER     NOT NULL,
    version   BIGINT      NOT NULL DEFAULT 1
);

-- One row, deliberately: it owns the rule that spans skus, so its version is the serialisation point
-- two concurrent reservations of *different* skus would otherwise not have.
CREATE TABLE s08_reservation_budget (
    id             VARCHAR(64) PRIMARY KEY,
    limit_units    INTEGER     NOT NULL,
    reserved_units INTEGER     NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 1
);
