package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.ContainerImages;
import com.example.samples.s22.ordering.application.PlaceOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Whether the schema is right is decided at startup, and this is the argument for deciding it there.
 *
 * <p>Being on the classpath is not being applied: the framework's migrations ship inside the component
 * jars and run only for the components named in {@code aipersimmon.ddd.flyway.components}. Forgetting an
 * entry is therefore a normal mistake with an abnormal blast radius — the outbox insert happens inside
 * the business transaction, so a missing table does not break "publishing", it breaks <em>every command
 * that publishes</em>, far away from the list that caused it.
 *
 * <p>Three boots, and the third is the one that makes the case. A refusal to start is only obviously
 * better than the alternative once you have seen the alternative.
 */
@Testcontainers
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class StartupSelfCheckTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(ContainerImages.POSTGRES);

  /**
   * A forgotten component list fails the boot, and the message names the property, the alternative
   * (apply the schema yourself) and the escape hatch. All three matter: an error that only says a table
   * is missing sends someone looking for a migration file that is not in this repository.
   */
  @Test
  void amissingComponentListRefusesToStartAndNamesTheFix() {
    assertThatThrownBy(() -> Boot.run(POSTGRES, "--aipersimmon.ddd.flyway.components="))
        .hasStackTraceContaining("aipersimmon_outbox")
        .hasStackTraceContaining("aipersimmon.ddd.flyway.components")
        .hasStackTraceContaining("aipersimmon.ddd.outbox.schema-validation=none");
  }

  /**
   * The control, and it is not ceremony: without it the test above passes just as well for an
   * application that cannot start for any other reason, and this whole class would be measuring the
   * harness.
   */
  @Test
  void thesameApplicationStartsWhenTheComponentIsListed() {
    try (ConfigurableApplicationContext context =
        Boot.run(POSTGRES, "--aipersimmon.ddd.flyway.components=outbox")) {
      assertThat(context.isRunning()).isTrue();
      assertThat(
              new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
                  .queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Long.class))
          .isZero();
    }
  }

  /**
   * Switch the probe off with the schema still missing, and the failure moves to where it cannot be
   * fixed: every command rolls back, at runtime, with a SQL error naming a table the application's own
   * developers never wrote.
   *
   * <p>The order row is gone too — which is correct, and is the reason this is worse than a failed boot
   * rather than merely later. The service is up, healthy by every probe it has, answering 500 to a
   * business endpoint because of a line missing from a list. A deployment that fails to start is caught
   * by the rollout; this one is caught by customers.
   */
  @Test
  void turningTheProbeOffMovesTheFailureIntoEveryCommand() {
    try (ConfigurableApplicationContext context =
        Boot.run(
            POSTGRES,
            "--aipersimmon.ddd.flyway.components=",
            "--aipersimmon.ddd.outbox.schema-validation=none")) {

      assertThat(context.isRunning()).isTrue();

      CommandBus commands = context.getBean(CommandBus.class);
      assertThatThrownBy(() -> commands.send(new PlaceOrder("customer-1", "sku-keyboard", 2)))
          .isNotInstanceOf(DomainException.class)
          .hasStackTraceContaining("aipersimmon_outbox");

      JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s22_order", Long.class)).isZero();
    }
  }
}
