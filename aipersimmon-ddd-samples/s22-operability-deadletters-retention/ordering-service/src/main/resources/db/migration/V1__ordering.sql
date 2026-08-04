-- This service's own table. Everything with an `aipersimmon_` prefix comes from the framework's own
-- migration runner, and only because application.yaml lists `outbox` under
-- aipersimmon.ddd.flyway.components: being on the classpath is not being applied.
--
-- The two runners have separate history tables on purpose, so this file's numbering and the
-- framework's cannot collide. S23 is where the sequencing between them is the subject; here the
-- relevant fact is only that forgetting the components list is caught at startup rather than by the
-- first command that publishes something (see StartupSelfCheckTest).
CREATE TABLE s22_order (
    id          VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    sku         VARCHAR(64) NOT NULL,
    quantity    INTEGER     NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1
);
