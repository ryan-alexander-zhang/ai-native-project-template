-- Sample schema for the durable JDBC deadline scheduler (JdbcDeadlineScheduler).
-- This file is NOT run automatically; copy it into your application's schema
-- management (Flyway, Liquibase, or a schema.sql) and adjust types to your database.
--
-- One row per pending deadline, keyed by correlation id and name. The scheduled
-- poll fires every row whose fire_at has passed and deletes it; a cancelled or
-- fired deadline leaves no row. Because rows are durable, pending deadlines survive
-- a restart and can be polled by any instance.
CREATE TABLE aipersimmon_deadline (
    correlation_id VARCHAR(128) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    fire_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_aipersimmon_deadline PRIMARY KEY (correlation_id, name)
);
