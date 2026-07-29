-- issue-00069: the payment context's business-idempotency log becomes a table, so that claiming an
-- operation and announcing its outcome are one commit.
--
-- It was a ConcurrentHashMap, described as "the lightest honest dedupe for a scaffold with no
-- payment datastore". The description misidentified what the pattern needs. A putIfAbsent cannot be
-- rolled back: if the transaction that claimed an operation then failed — the outbox insert, a
-- later interceptor, a dropped connection — the claim survived while the outcome event did not, and
-- every redelivery afterwards found the operation already handled and published nothing at all. The
-- authorization was silently and permanently lost.
--
-- The fix is NOT "make it durable". An ordinary table written in its own transaction has precisely
-- the same hole. What closes it is that this table lives on the same DataSource as the outbox, so a
-- recorded decision and the event announcing it commit or roll back together.
--
-- payment therefore gets its first table, and this does not contradict "payment owns no persisted
-- aggregate": an idempotency log is a technical outbound adapter, which is how payment/pom.xml has
-- always described it.
--
-- It now sits in a `payment` schema. It used to be created unqualified, which put it in the default
-- schema next to the framework's own tables — justified at the time as "plumbing, not a domain model".
-- That reasoning confused two different questions. Whether a table holds a domain model decides
-- whether it needs an aggregate; which context OWNS it decides where it lives. This table is
-- unambiguously payment's: payment's code is the only thing that reads or writes it, and its retention
-- is payment's decision. Leaving it unqualified made the payment context the one context whose storage
-- you could not point at, and it would have been the one loose end when extracting payment into its own
-- service. A schema per context, with no exceptions, is both cheaper and easier to explain than a rule
-- with one.

CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.payment_operations (
    -- (tenant_id, operation_id) rather than operation_id alone: the id is derived from a message id
    -- in the originating tenant's own causal chain, so two tenants may legitimately produce the
    -- same one. Same reasoning as the composite keys in ordering/V1_2 and V1_4.
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT '__root__',
    operation_id    VARCHAR(64)  NOT NULL,
    -- AUTHORIZED / DECLINED. Stored as the decision's shape rather than a boolean so a third
    -- outcome (PENDING, for a provider that answers asynchronously) can be added without a
    -- migration that has to reinterpret existing rows.
    outcome         VARCHAR(32)  NOT NULL,
    decline_code    VARCHAR(64),
    decline_reason  VARCHAR(512),
    recorded_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (tenant_id, operation_id)
);

-- The primary key above is the claim, and it is load-bearing rather than merely tidy: two
-- concurrent first deliveries both find nothing and both insert, and the loser's constraint
-- violation is what rolls its transaction back so its retry can republish the winner's decision.
-- There is deliberately no ON CONFLICT anywhere against this table.
