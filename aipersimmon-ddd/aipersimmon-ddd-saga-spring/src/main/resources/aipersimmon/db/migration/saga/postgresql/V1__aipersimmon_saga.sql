-- Flyway migration (PostgreSQL) for the aipersimmon-ddd saga store and durable deadline scheduler.
-- Single source of the saga schema. TEXT for the serialized saga data.
CREATE TABLE IF NOT EXISTS aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    version        BIGINT       NOT NULL,
    data           TEXT,
    CONSTRAINT pk_aipersimmon_saga PRIMARY KEY (correlation_id)
);

CREATE TABLE IF NOT EXISTS aipersimmon_deadline (
    correlation_id VARCHAR(128) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    fire_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_aipersimmon_deadline PRIMARY KEY (correlation_id, name)
);
