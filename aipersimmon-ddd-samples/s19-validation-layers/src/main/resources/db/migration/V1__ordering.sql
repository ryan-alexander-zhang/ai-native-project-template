CREATE TABLE s19_order (
    id          VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    quantity    INTEGER     NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1
);
