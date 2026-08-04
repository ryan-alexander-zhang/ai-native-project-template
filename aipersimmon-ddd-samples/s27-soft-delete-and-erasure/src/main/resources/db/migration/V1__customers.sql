-- One table carrying all three things this sample calls "delete", so they can be compared rather than
-- described.
CREATE TABLE s27_customer (
    id           VARCHAR(64)  PRIMARY KEY,

    -- The personal data. These are the columns an erasure overwrites.
    email        VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    phone        VARCHAR(64),

    -- (1) DOMAIN STATE. ACTIVE / CLOSED. The aggregate knows it, rules read it, it can be undone, and a
    -- support agent can be told why. Mapped by toRow like any other field, because it *is* any other
    -- field.
    status        VARCHAR(16)  NOT NULL,
    closed_reason VARCHAR(200),

    -- (3) COMPLIANCE ERASURE. Not a delete: the row stays and the personal columns above are overwritten.
    -- This column records that it happened, which is itself a fact somebody may have to prove.
    erased_at     TIMESTAMPTZ,

    -- (2) INFRASTRUCTURE SWITCH. MyBatis-Plus @TableLogic. Nothing in the domain has heard of it: as far
    -- as the application is concerned a suppressed row does not exist, because every select the mapper
    -- builds carries `deleted = false`. NOT NULL DEFAULT is load-bearing — see the resurrection trap in
    -- ClearedColumnsTest.
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,

    version       BIGINT       NOT NULL
);

-- Deliberately the naive one, and V2 replaces it. A plain UNIQUE cannot tell a live row from a
-- logically-deleted one, so suppressing a customer leaves their address permanently taken and the
-- person cannot come back. UniqueEmailTest measures both halves.
CREATE UNIQUE INDEX uq_s27_customer_email ON s27_customer (email);

-- The consent this sample's inbox consumer maintains. It exists to make "was this message processed"
-- observable: dedup is invisible unless processing leaves a mark.
CREATE TABLE s27_marketing_consent (
    customer_id VARCHAR(64) NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL,
    note        VARCHAR(200)
);
CREATE INDEX idx_s27_marketing_consent_customer ON s27_marketing_consent (customer_id);
