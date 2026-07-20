-- H2 test schema for the durable Process Manager four-table model (design-00004 §4.5).
-- Test-only; the production DDL (postgresql/mysql/h2) ships with the starter and is
-- applied via Flyway/Liquibase, never auto-executed.

CREATE TABLE IF NOT EXISTS aipersimmon_process_instance (
    instance_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
    process_type        VARCHAR(128) NOT NULL,
    business_key        VARCHAR(128) NOT NULL,
    definition_version  VARCHAR(64)  NOT NULL,
    state_schema_version INT         NOT NULL,
    lifecycle           VARCHAR(32)  NOT NULL,
    resume_lifecycle    VARCHAR(32),
    suspension_reason   VARCHAR(512),
    suspension_source   VARCHAR(32),
    suspending_work_id  VARCHAR(96),
    business_step       VARCHAR(128) NOT NULL,
    outcome             VARCHAR(128),
    revision            BIGINT       NOT NULL,
    state_payload_type  VARCHAR(255) NOT NULL,
    state_payload       CLOB         NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    ended_at            TIMESTAMP,
    CONSTRAINT uq_process_instance_business UNIQUE (process_type, business_key)
);

CREATE TABLE IF NOT EXISTS aipersimmon_process_transition (
    transition_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    instance_id       VARCHAR(64)  NOT NULL,
    input_message_id  VARCHAR(96)  NOT NULL,
    input_type        VARCHAR(255) NOT NULL,
    input_version     INT          NOT NULL,
    input_payload     CLOB         NOT NULL,
    from_lifecycle    VARCHAR(32),
    to_lifecycle      VARCHAR(32)  NOT NULL,
    from_step         VARCHAR(128),
    to_step           VARCHAR(128) NOT NULL,
    decision_code     VARCHAR(128) NOT NULL,
    transition_kind   VARCHAR(48)  NOT NULL,
    correlation_id    VARCHAR(64),
    trace_id          VARCHAR(128),
    operator_id       VARCHAR(64),
    operation_reason  VARCHAR(512),
    failure           CLOB,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uq_process_transition_input UNIQUE (instance_id, input_message_id)
);

CREATE TABLE IF NOT EXISTS aipersimmon_process_effect (
    effect_id       VARCHAR(96)  NOT NULL PRIMARY KEY,
    instance_id     VARCHAR(64)  NOT NULL,
    transition_id   VARCHAR(64)  NOT NULL,
    effect_index    INT          NOT NULL,
    seq             BIGINT       NOT NULL,
    effect_kind     VARCHAR(48)  NOT NULL,
    payload_type    VARCHAR(255) NOT NULL,
    payload_version INT          NOT NULL,
    payload         CLOB         NOT NULL,
    message_id      VARCHAR(96)  NOT NULL,
    correlation_id  VARCHAR(64)  NOT NULL,
    causation_id    VARCHAR(64),
    trace_id        VARCHAR(128),
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    lease_owner     VARCHAR(128),
    lease_token     VARCHAR(64),
    lease_until     TIMESTAMP,
    last_error      CLOB,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    delivered_at    TIMESTAMP,
    CONSTRAINT uq_process_effect_index UNIQUE (transition_id, effect_index)
);

CREATE INDEX IF NOT EXISTS idx_process_effect_due
    ON aipersimmon_process_effect (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_process_effect_instance
    ON aipersimmon_process_effect (instance_id, seq);
CREATE INDEX IF NOT EXISTS idx_process_effect_lease
    ON aipersimmon_process_effect (status, lease_until);

CREATE TABLE IF NOT EXISTS aipersimmon_process_deadline (
    deadline_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    instance_id     VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    generation      BIGINT       NOT NULL,
    due_at          TIMESTAMP    NOT NULL,
    input_type      VARCHAR(255) NOT NULL,
    input_version   INT          NOT NULL,
    input_payload   CLOB         NOT NULL,
    correlation_id  VARCHAR(64),
    causation_id    VARCHAR(64),
    trace_id        VARCHAR(128),
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    lease_owner     VARCHAR(128),
    lease_token     VARCHAR(64),
    lease_until     TIMESTAMP,
    last_error      CLOB,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP,
    CONSTRAINT uq_process_deadline_generation UNIQUE (instance_id, name, generation)
);

CREATE INDEX IF NOT EXISTS idx_process_deadline_due
    ON aipersimmon_process_deadline (status, next_attempt_at, due_at);
CREATE INDEX IF NOT EXISTS idx_process_deadline_lease
    ON aipersimmon_process_deadline (status, lease_until);
