package com.example.samples.s23;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.testsupport.ContainerImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The trap a second context walks into, and the floor underneath it.
 *
 * <p>The library installs its own {@link FlywayMigrationStrategy}, which runs the consumer's migrations and
 * then the framework component ones. That bean is {@code @ConditionalOnMissingBean}. A second bounded context
 * forces the application to define a strategy of its own — and the moment it does, the library's backs off and
 * takes the component migrations with it.
 *
 * <p>Nothing about that is announced. There is no warning, because from the library's point of view a consumer
 * who supplied a strategy has taken over the job, which is a reasonable reading. So the mistake is silent
 * <em>at the point it is made</em>, and is caught one bean later by the outbox's schema validator refusing to
 * start.
 */
@Testcontainers
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class StrategyTrapTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(ContainerImages.POSTGRES);

  private static final AtomicInteger COUNTER = new AtomicInteger();

  /**
   * A strategy that runs only Boot's own Flyway starts the application... nowhere. It fails, and the message it
   * fails with is the interesting part.
   *
   * <p>The refusal names {@code aipersimmon.ddd.flyway.components} and tells the reader to add {@code outbox}
   * to it. In this application that property <strong>already lists outbox</strong> — the cause is not the
   * property at all, it is that the strategy never called the migrator. So the guard catches the mistake (which
   * is the floor: a failed boot rather than a production database missing tables) while pointing at the wrong
   * fix, and someone will spend a while checking a configuration line that was right all along.
   *
   * <p>The remedy is a habit rather than a change: if you define a {@code FlywayMigrationStrategy}, the last
   * thing in it is a call to {@code AipersimmonFlywayMigrator.migrate}. {@code MigrationConfiguration} does
   * exactly that, and says why.
   */
  @Test
  void astrategyThatForgetsTheFrameworkFailsToStartAndBlamesTheWrongThing() {
    assertThatThrownBy(() -> boot(ForgetfulMigrations.class))
        .hasStackTraceContaining("aipersimmon_outbox")
        // Pointing at a property that is already set correctly.
        .hasStackTraceContaining("aipersimmon.ddd.flyway.components");
  }

  /**
   * The control, and it also demonstrates the second half of the same mistake: the forgetful strategy skipped
   * billing too, and nothing at all would have complained about that.
   *
   * <p>Only the framework components ship a validator. A context of your own that quietly failed to migrate
   * leaves an application that starts, serves ordering perfectly, and 500s the first time anyone touches
   * billing. Which is the argument for putting all three sets in one readable method rather than trusting
   * bean ordering — and for a smoke test that touches every context after a deploy.
   */
  @Test
  void theapplicationsOwnStrategyRunsAllThreeSets() {
    try (ConfigurableApplicationContext context = boot()) {
      assertThat(context.isRunning()).isTrue();
      List<String> tables = tables(context);
      assertThat(tables).contains("s23_order", "s23_invoice", "aipersimmon_outbox");
    }
  }

  private ConfigurableApplicationContext boot(Class<?>... extraSources) {
    String database = "s23trap" + COUNTER.incrementAndGet();
    createDatabase(database);
    Class<?>[] sources = new Class<?>[extraSources.length + 1];
    sources[0] = S23Application.class;
    System.arraycopy(extraSources, 0, sources, 1, extraSources.length);
    return new SpringApplicationBuilder(sources)
        .run(
            "--spring.datasource.url=jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getFirstMappedPort()
                + "/"
                + database,
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.main.web-application-type=servlet",
            "--server.port=0");
  }

  /**
   * What a team writes on the first day they add a second context: Boot's Flyway, and nothing else. Marked
   * {@code @Primary} so it wins over the application's real strategy for the duration of this test — the
   * mistake being reproduced is "there is only this one".
   */
  @Configuration(proxyBeanMethods = false)
  static class ForgetfulMigrations {

    @Bean
    @Primary
    FlywayMigrationStrategy forgetful() {
      return Flyway::migrate;
    }
  }

  private void createDatabase(String name) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("could not create database " + name, e);
    }
  }

  private List<String> tables(ConfigurableApplicationContext context) {
    List<String> tables = new ArrayList<>();
    try (Connection connection = context.getBean(javax.sql.DataSource.class).getConnection();
        Statement statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return tables;
  }
}
