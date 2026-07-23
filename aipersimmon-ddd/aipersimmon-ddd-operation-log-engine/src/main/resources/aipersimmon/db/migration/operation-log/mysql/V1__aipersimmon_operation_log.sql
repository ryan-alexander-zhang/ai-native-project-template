-- MySQL schema for the Operation Log component.
-- DATETIME(6) (not TIMESTAMP) avoids session-tz conversion and the 2038 cap; connections must run
-- with time_zone = UTC. Indexes are inline KEYs (MySQL has no CREATE INDEX IF NOT EXISTS).
-- ASCII-shaped columns declared CHARACTER SET ascii so the composite key stays well under the
-- InnoDB key-length limit; ROW_FORMAT=DYNAMIC for utf8mb4 text columns.

CREATE TABLE IF NOT EXISTS aipersimmon_operation_log (
    record_id         VARCHAR(64)  CHARACTER SET ascii NOT NULL,
    source            VARCHAR(128) NOT NULL,
    tenant_id         VARCHAR(64)  CHARACTER SET ascii NOT NULL,
    idempotency_key   CHAR(64)     CHARACTER SET ascii NOT NULL,
    operation_code    VARCHAR(128) NOT NULL,
    actor_type        VARCHAR(32)  CHARACTER SET ascii NOT NULL,
    actor_id          VARCHAR(128),
    actor_display     VARCHAR(256),
    target_type       VARCHAR(128) NOT NULL,
    target_id         VARCHAR(128) NOT NULL,
    target_display    VARCHAR(256),
    outcome           VARCHAR(16)  CHARACTER SET ascii NOT NULL,
    completion        VARCHAR(16)  CHARACTER SET ascii NOT NULL,
    summary           VARCHAR(1024),
    changes           TEXT,
    details           TEXT,
    failure_code      VARCHAR(128),
    failure_category  VARCHAR(64),
    failure_summary   VARCHAR(512),
    message_id        VARCHAR(96)  CHARACTER SET ascii,
    correlation_id    VARCHAR(96)  CHARACTER SET ascii,
    causation_id      VARCHAR(96)  CHARACTER SET ascii,
    template_key      VARCHAR(128),
    template_version  VARCHAR(32),
    schema_version    INT          NOT NULL,
    occurred_at       DATETIME(6)  NOT NULL,
    recorded_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (record_id),
    UNIQUE KEY uq_operation_log_idempotency (tenant_id, source, idempotency_key),
    KEY idx_operation_log_target (tenant_id, target_type, target_id, occurred_at, record_id),
    KEY idx_operation_log_actor (tenant_id, actor_type, actor_id, occurred_at),
    KEY idx_operation_log_code (tenant_id, operation_code, occurred_at),
    KEY idx_operation_log_correlation (correlation_id)
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC DEFAULT CHARSET = utf8mb4;
