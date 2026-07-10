-- Saga store table required by aipersimmon-ddd-saga-spring (JdbcSagaStore).
CREATE TABLE IF NOT EXISTS aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL PRIMARY KEY,
    status         VARCHAR(32)  NOT NULL,
    version        BIGINT       NOT NULL,
    data           CLOB
);
