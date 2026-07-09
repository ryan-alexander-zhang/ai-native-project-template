-- Sample schema for the JDBC saga store (JdbcSagaStore). This file is NOT run
-- automatically; copy it into your application's schema management (Flyway,
-- Liquibase, or a schema.sql) and adjust column types to your database.
--
-- One row per saga instance, keyed by correlation id. `version` backs optimistic
-- locking: each successful advance increments it, and a save whose expected
-- version no longer matches is rejected. `data` holds the saga's flow data,
-- serialized by the store subclass (for example as JSON).
CREATE TABLE aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    version        BIGINT       NOT NULL,
    data           CLOB,
    CONSTRAINT pk_aipersimmon_saga PRIMARY KEY (correlation_id)
);
