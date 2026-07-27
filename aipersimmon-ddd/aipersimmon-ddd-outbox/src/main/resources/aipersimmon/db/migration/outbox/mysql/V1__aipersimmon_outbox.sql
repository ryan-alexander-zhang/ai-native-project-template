-- Flyway migration (MySQL 8+) for the aipersimmon-ddd outbox tables. Single source of the
-- outbox schema, shared by the -outbox-jdbc and -outbox-mybatis-plus adapters. MySQL specifics:
-- BIGINT AUTO_INCREMENT identity, LONGTEXT payloads, DATETIME(3) timestamps, inline KEY indexes.
CREATE TABLE IF NOT EXISTS aipersimmon_outbox (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    event_id    VARCHAR(64)  NOT NULL,
    source      VARCHAR(255) NOT NULL,
    type        VARCHAR(512) NOT NULL,
    version     INT          NOT NULL,
    payload     LONGTEXT     NOT NULL,
    occurred_at DATETIME(3)  NOT NULL,
    subject     VARCHAR(255),
    correlation_id VARCHAR(64) NOT NULL,
    causation_id   VARCHAR(64),
    trace_id    VARCHAR(128),
    traceparent VARCHAR(55),
    trace_state VARCHAR(512),
    sent        BOOLEAN      NOT NULL DEFAULT FALSE,
    sent_at     DATETIME(3),
    attempts    INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3),
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_aipersimmon_outbox_event UNIQUE (event_id),
    KEY idx_aipersimmon_outbox_unsent (sent, next_attempt_at, created_at),
    KEY idx_aipersimmon_outbox_subject_order (subject, sent, created_at, id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS aipersimmon_dead_letter (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    event_id    VARCHAR(64)  NOT NULL,
    source      VARCHAR(255) NOT NULL,
    type        VARCHAR(512) NOT NULL,
    version     INT          NOT NULL,
    payload     LONGTEXT     NOT NULL,
    occurred_at DATETIME(3)  NOT NULL,
    subject     VARCHAR(255),
    correlation_id VARCHAR(64) NOT NULL,
    causation_id   VARCHAR(64),
    trace_id    VARCHAR(128),
    traceparent VARCHAR(55),
    trace_state VARCHAR(512),
    attempts    INT          NOT NULL,
    reason      VARCHAR(32)  NOT NULL,
    last_error  LONGTEXT,
    failed_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_aipersimmon_dead_letter_event UNIQUE (event_id)
) ENGINE = InnoDB;

-- ShedLock JDBC contract table, used by the relay and the cleanup job to keep one instance
-- polling at a time. Deliberately NOT prefixed `aipersimmon_`: this is ShedLock's own default
-- table name, which is what a consumer's LockProvider already expects, so renaming it would
-- either need custom withTableName() config or leave an app that already uses ShedLock with two
-- lock tables. Column names/types are fixed by ShedLock. IF NOT EXISTS is what makes it a shared
-- table rather than a claimed one: an application (or another library) already managing `shedlock`
-- is unaffected, and one that is not gets it provisioned here. It rides in the outbox migration
-- because the outbox is currently the only component that takes a ShedLock lease — the
-- process-manager leases through its own columns, not ShedLock.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until DATETIME(3)  NOT NULL,
    locked_at  DATETIME(3)  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB;
