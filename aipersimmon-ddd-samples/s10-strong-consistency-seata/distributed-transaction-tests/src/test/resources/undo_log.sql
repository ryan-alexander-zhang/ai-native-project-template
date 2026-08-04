-- Seata AT's undo log, PostgreSQL flavour, provisioned with the database rather than by the application.
--
-- It has to be. Seata's DataSourceProxy verifies this table exists while the DataSource bean is being
-- constructed (DataSourceProxy.checkUndoLogTableExist, called unconditionally from its init), and every
-- in-application migration mechanism — Flyway, spring.sql.init, the framework's own flyway components —
-- runs after the DataSource bean exists, because it needs the DataSource. There is no configuration switch
-- to defer or disable the check.
--
-- So undo_log belongs with the database: in the image's init scripts, in the DBA's provisioning, in the
-- Terraform. Not in db/migration. The sample's docker-compose does the same thing.
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
