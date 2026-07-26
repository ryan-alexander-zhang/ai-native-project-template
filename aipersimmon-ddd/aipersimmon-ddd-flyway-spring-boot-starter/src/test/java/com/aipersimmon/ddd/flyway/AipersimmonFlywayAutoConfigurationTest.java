package com.aipersimmon.ddd.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the shared runner end-to-end on H2, WITH Spring Boot's own Flyway auto-configuration
 * active (as it is in any real app that has flyway-core on the classpath) and WITH a consumer-owned
 * business migration at the default {@code db/migration} location. The starter plugs into Boot's
 * single Flyway initializer via a {@link FlywayMigrationStrategy}: the consumer's own migrations
 * run first (default history table), then each aipersimmon component is applied into its own
 * history table — no conflict, no hijacking of the consumer's Flyway.
 */
class AipersimmonFlywayAutoConfigurationTest {

  // A distinct in-memory database per test method — a shared mem DB would leak tables between
  // methods.
  private static ApplicationContextRunner runnerFor(String db) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                AipersimmonDddFlywayAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "spring.datasource.username=sa",
            "spring.datasource.password=");
  }

  @Test
  void appliesComponentsAndCoexistsWithConsumerDefaultFlyway() {
    runnerFor("flyway_all")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));

              // The consumer's own default Flyway ran its business migration into the DEFAULT
              // history table.
              assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_widget", Integer.class))
                  .isZero();
              assertThat(historyTableCount(jdbc, "flyway_schema_history")).isEqualTo(1);

              // Every aipersimmon component was applied too — the real outbox set plus a synthetic
              // widget set.
              assertThat(
                      jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class))
                  .isZero();
              assertThat(
                      jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_widget", Integer.class))
                  .isZero();

              // ...each into its OWN dedicated history table, not the consumer's default one.
              assertThat(historyTableCount(jdbc, "flyway_schema_history_aipersimmon_outbox"))
                  .isEqualTo(1);
              assertThat(historyTableCount(jdbc, "flyway_schema_history_aipersimmon_widget"))
                  .isEqualTo(1);
            });
  }

  @Test
  void backsOffWhenDisabledButLeavesConsumerFlywayAlone() {
    runnerFor("flyway_disabled")
        .withPropertyValues("aipersimmon.ddd.flyway.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class);
              JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
              // The consumer's own default Flyway still ran.
              assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_widget", Integer.class))
                  .isZero();
              // But no aipersimmon component was applied.
              assertThat(historyTableCount(jdbc, "flyway_schema_history_aipersimmon_outbox"))
                  .isZero();
            });
  }

  @Test
  void respectsComponentAllowList() {
    runnerFor("flyway_allow")
        .withPropertyValues("aipersimmon.ddd.flyway.components=widget")
        .run(
            context -> {
              JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
              assertThat(
                      jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_widget", Integer.class))
                  .isZero();
              // outbox was not in the allow-list, so its table/history must not exist.
              assertThat(historyTableCount(jdbc, "flyway_schema_history_aipersimmon_outbox"))
                  .isZero();
            });
  }

  private static Integer historyTableCount(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)",
        Integer.class,
        table);
  }
}
