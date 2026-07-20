-- Flyway migration (MySQL 8+) for the aipersimmon-ddd saga store and durable deadline scheduler.
-- Single source of the saga schema. LONGTEXT for the serialized saga data, DATETIME(3) fire time.
CREATE TABLE IF NOT EXISTS aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    version        BIGINT       NOT NULL,
    data           LONGTEXT,
    CONSTRAINT pk_aipersimmon_saga PRIMARY KEY (correlation_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS aipersimmon_deadline (
    correlation_id VARCHAR(128) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    fire_at        DATETIME(3)  NOT NULL,
    CONSTRAINT pk_aipersimmon_deadline PRIMARY KEY (correlation_id, name)
) ENGINE = InnoDB;
