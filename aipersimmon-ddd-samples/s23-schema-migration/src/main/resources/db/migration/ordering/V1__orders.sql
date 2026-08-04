-- The ordering context, as it first shipped. Everything after this file exists because this file was
-- wrong in a way nobody minded at the time: the destination is one free-text line.
--
-- Note the location. This is db/migration/ORDERING, not db/migration, because a second context lives in
-- the same database and has a V1 of its own. Two contexts in one location means two V1s in one version
-- space, which Flyway refuses outright — and rightly, since the two teams' version numbers have nothing
-- to do with each other.
CREATE TABLE s23_order (
    id          VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    sku         VARCHAR(64) NOT NULL,
    quantity    INTEGER     NOT NULL,
    -- "12 Baker Street, London" — one column, because the first version of anything models what the
    -- first screen needed.
    ship_to     TEXT        NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1
);
