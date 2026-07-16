package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies at startup that the four process tables exist (design-00004 §5.6), so a
 * missing migration fails fast with a clear message instead of at the first advance. It
 * never creates tables — the DDL ships as a sample and is applied via Flyway/Liquibase.
 * Disabled when {@code schema-validation=none}.
 */
@DependsOnDatabaseInitialization
public final class ProcessSchemaValidator implements InitializingBean {

    private static final String[] TABLES = {
            "aipersimmon_process_instance",
            "aipersimmon_process_transition",
            "aipersimmon_process_effect",
            "aipersimmon_process_deadline",
    };

    private final JdbcTemplate jdbc;

    public ProcessSchemaValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        for (String table : TABLES) {
            try {
                jdbc.execute("SELECT 1 FROM " + table + " WHERE 1 = 0");
            } catch (RuntimeException missing) {
                throw new IllegalStateException(
                        "process-manager table '" + table + "' is missing or unreadable; apply the schema "
                                + "(see META-INF/aipersimmon-ddd/process-manager) via Flyway/Liquibase, or set "
                                + "aipersimmon.ddd.process-manager.jdbc.schema-validation=none", missing);
            }
        }
    }
}
