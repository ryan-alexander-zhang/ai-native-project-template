-- Seata AT's undo log, PostgreSQL flavour. Applied by the database container's init hook, and therefore
-- present before either service starts.
--
-- Not in db/migration, and not because of a preference. Seata's DataSourceProxy checks for this table while
-- the DataSource bean is being constructed (an unconditional call inside its own init), which happens before
-- Flyway, before spring.sql.init and before the framework's own flyway components — all of which depend on
-- the DataSource. There is no property that defers or disables the check, so an application cannot create
-- this table for itself. It belongs with the database: in the image, in the DBA's provisioning, in the
-- Terraform.
--
-- Two knock-on effects, both configured in the services' yaml rather than discovered later:
--   * the schema is no longer empty when Flyway first runs, so Flyway needs baseline-on-migrate
--   * and baseline-version must be 0, or Flyway baselines *at* 1 and skips V1 as already applied
CREATE TABLE undo_log (
    id            SERIAL       NOT NULL,
    branch_id     BIGINT       NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info BYTEA        NOT NULL,
    log_status    INT          NOT NULL,
    log_created   TIMESTAMP(0) NOT NULL,
    log_modified  TIMESTAMP(0) NOT NULL,
    CONSTRAINT pk_undo_log PRIMARY KEY (id),
    CONSTRAINT ux_undo_log UNIQUE (xid, branch_id)
);
