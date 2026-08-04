package com.example.samples.s23;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Why nobody has to remember to disable {@code clean} in production: it is already off, in all three places.
 *
 * <p>{@code clean} drops every object in the schema. It exists for a legitimate purpose — resetting a
 * developer's database — and the reason it must be off by default is not that anyone would type it deliberately
 * in production. It is that during an incident, at 3am, "the migration is stuck, let me just reset it" is a
 * thought people have, and a command that is available will eventually be reached for.
 *
 * <p>What makes it worth a test rather than a sentence: this application has three Flyway configurations and
 * only one of them is configured by {@code spring.flyway.*}. A default that held for Boot's instance and not
 * for the two built in code would be a default that holds where it is documented and not where it matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class CleanIsRefusedTest {

  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;

  /** Boot's own instance, configured by spring.flyway.* — refused, and the message says which switch. */
  @Test
  void bootsFlywayRefusesToClean() {
    assertThatThrownBy(flyway::clean).hasMessageContaining("cleanDisabled");
  }

  /**
   * And an instance built in code, which is what the other two sets are: refused as well, without anybody
   * saying so.
   *
   * <p>Flyway's own default since 9.x, which is why neither {@code MigrationConfiguration} nor the library's
   * component migrator sets it. Worth asserting rather than trusting, because "we did not configure it" and
   * "it is safe" are the same sentence only for as long as the default holds — and this is the assertion that
   * would notice a Flyway upgrade changing its mind.
   */
  @Test
  void aflywayBuiltInCodeRefusesToo() {
    Flyway inCode = Flyway.configure().dataSource(dataSource).load();

    assertThatThrownBy(inCode::clean).hasMessageContaining("cleanDisabled");
    assertThat(inCode.getConfiguration().isCleanDisabled()).isTrue();
  }
}
