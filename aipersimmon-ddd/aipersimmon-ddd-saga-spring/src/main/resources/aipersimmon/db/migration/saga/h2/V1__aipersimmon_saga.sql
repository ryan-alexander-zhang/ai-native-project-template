-- Flyway migration (H2) for the aipersimmon-ddd saga store and durable deadline scheduler.
-- Single source of the saga schema. Applied automatically by the optional aipersimmon-ddd-flyway
-- starter (history table flyway_schema_history_aipersimmon_saga), or copy into your own
-- Flyway/Liquibase.

-- One row per saga instance, keyed by correlation id. `version` backs optimistic locking.
CREATE TABLE IF NOT EXISTS aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    version        BIGINT       NOT NULL,
    data           CLOB,
    CONSTRAINT pk_aipersimmon_saga PRIMARY KEY (correlation_id)
);

-- One row per pending deadline, keyed by correlation id and name; durable across restarts.
CREATE TABLE IF NOT EXISTS aipersimmon_deadline (
    correlation_id VARCHAR(128) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    fire_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_aipersimmon_deadline PRIMARY KEY (correlation_id, name)
);
