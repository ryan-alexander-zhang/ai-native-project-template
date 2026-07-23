-- PostgreSQL schema for the Operation Log component (design-00008 §7.2).
-- Append-only business-readable log; timestamps are timestamptz (UTC instants).

CREATE TABLE IF NOT EXISTS aipersimmon_operation_log (
    record_id         VARCHAR(64)  NOT NULL PRIMARY KEY, -- time-ordered id (UUIDv7/ULID)
    source            VARCHAR(128) NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL,             -- non-multi-tenant normalized to 'GLOBAL'
    idempotency_key   CHAR(64)     NOT NULL,             -- SHA-256 hex
    operation_code    VARCHAR(128) NOT NULL,
    actor_type        VARCHAR(32)  NOT NULL,
    actor_id          VARCHAR(128),
    actor_display     VARCHAR(256),
    target_type       VARCHAR(128) NOT NULL,
    target_id         VARCHAR(128) NOT NULL,
    target_display    VARCHAR(256),
    outcome           VARCHAR(16)  NOT NULL,             -- SUCCEEDED / REJECTED / FAILED
    completion        VARCHAR(16)  NOT NULL,             -- COMMITTED / ROLLED_BACK / NOT_STARTED / UNKNOWN
    summary           VARCHAR(1024),
    changes           TEXT,                              -- bounded JSON [{field,label,before,after}]
    details           TEXT,                              -- bounded JSON [{name,value}]
    failure_code      VARCHAR(128),
    failure_category  VARCHAR(64),
    failure_summary   VARCHAR(512),
    message_id        VARCHAR(96),
    correlation_id    VARCHAR(96),
    causation_id      VARCHAR(96),
    template_key      VARCHAR(128),
    template_version  VARCHAR(32),
    schema_version    INT          NOT NULL,
    occurred_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    recorded_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_operation_log_idempotency UNIQUE (tenant_id, source, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_operation_log_target
    ON aipersimmon_operation_log (tenant_id, target_type, target_id, occurred_at, record_id);
CREATE INDEX IF NOT EXISTS idx_operation_log_actor
    ON aipersimmon_operation_log (tenant_id, actor_type, actor_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_operation_log_code
    ON aipersimmon_operation_log (tenant_id, operation_code, occurred_at);
CREATE INDEX IF NOT EXISTS idx_operation_log_correlation
    ON aipersimmon_operation_log (correlation_id);
