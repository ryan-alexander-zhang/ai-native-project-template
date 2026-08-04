package com.example.samples.s22.ordering;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts the real application against a real, <em>empty</em> database, with settings a test chooses —
 * because the claims in {@link StartupSelfCheckTest} and {@link CapabilityAnnouncementTest} are about
 * <em>whether startup succeeds</em>, and a {@code @SpringBootTest} cannot express that: a context that
 * fails to refresh fails the test rather than being the result of it.
 *
 * <p>Command-line args, not {@code SpringApplicationBuilder.properties(...)}. S10 measured why:
 * {@code properties(...)} contributes <em>default</em> properties, which application.yaml then
 * overrides, so an override written that way is silently ignored. Args outrank the yaml.
 *
 * <p><strong>A fresh database per boot, and this was measured rather than foreseen.</strong> With one
 * database shared across the methods of a class, the boot that was supposed to fail on a missing
 * {@code aipersimmon_outbox} started cleanly — because a sibling test had already booted with the
 * component listed and left the tables behind. The failing assertion was the control test's, which is
 * the only reason it was noticed at all: a test that asserts "this configuration cannot start" is
 * worthless without a sibling asserting the same application can, and here that sibling was what
 * exposed the shared state.
 */
final class Boot {

  private Boot() {}

  private static final AtomicInteger COUNTER = new AtomicInteger();

  /**
   * Runs the application against a database created for this call, and returns the context — or throws
   * whatever refusing to start threw.
   *
   * @param extra additional {@code --key=value} args, applied on top of the database wiring
   */
  static ConfigurableApplicationContext run(PostgreSQLContainer<?> postgres, String... extra) {
    String database = "s22boot" + COUNTER.incrementAndGet();
    createDatabase(postgres, database);
    List<String> args =
        new ArrayList<>(
            List.of(
                "--spring.datasource.url="
                    + "jdbc:postgresql://"
                    + postgres.getHost()
                    + ":"
                    + postgres.getFirstMappedPort()
                    + "/"
                    + database,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                // A servlet context, because two of the guards under test are web-scoped and would
                // simply not be wired in a non-web application — an absence that would read as "the
                // framework said nothing".
                "--spring.main.web-application-type=servlet",
                "--server.port=0"));
    args.addAll(List.of(extra));
    return new SpringApplicationBuilder(OrderingServiceApplication.class)
        .run(args.toArray(String[]::new));
  }

  private static void createDatabase(PostgreSQLContainer<?> postgres, String name) {
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("could not create database " + name, e);
    }
  }
}
