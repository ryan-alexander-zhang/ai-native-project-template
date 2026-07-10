CREATE TABLE IF NOT EXISTS aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL PRIMARY KEY,
    status         VARCHAR(32)  NOT NULL,
    version        BIGINT       NOT NULL,
    data           CLOB
);

CREATE TABLE IF NOT EXISTS aipersimmon_deadline (
    correlation_id VARCHAR(128) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    fire_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_aipersimmon_deadline PRIMARY KEY (correlation_id, name)
);
