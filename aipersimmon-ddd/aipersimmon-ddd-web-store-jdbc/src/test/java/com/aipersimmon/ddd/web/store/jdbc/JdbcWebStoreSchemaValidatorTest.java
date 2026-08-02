package com.aipersimmon.ddd.web.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The startup probe that turns "every covered request fails at the edge" into a boot failure naming
 * the fix. Missing table → a clear IllegalStateException pointing at {@code
 * aipersimmon.ddd.flyway.components}; migrated schema → silence.
 */
class JdbcWebStoreSchemaValidatorTest {

  private static final AtomicInteger DB = new AtomicInteger();

  private final SimpleDriverDataSource dataSource =
      new SimpleDriverDataSource(
          new org.h2.Driver(),
          "jdbc:h2:mem:web-store-schema" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
          "sa",
          "");
  private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

  @AfterEach
  void tearDown() {
    jdbc.execute("SHUTDOWN");
  }

  @Test
  void aMissingTableFailsStartupNamingTheConfigurationThatCreatesIt() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new JdbcWebStoreSchemaValidator(jdbc).afterPropertiesSet());

    assertTrue(
        failure.getMessage().contains("aipersimmon.ddd.flyway.components"),
        "the message must name the configuration lever, not just the symptom: "
            + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("schema-validation=none"),
        "the escape hatch must be discoverable from the failure itself");
  }

  @Test
  void aMigratedSchemaPassesSilently() {
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/web-store/h2/V1__aipersimmon_web_store.sql"),
            new ClassPathResource("aipersimmon/db/migration/web-store/h2/V2__add_tenant_id.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/web-store/h2/V3__idempotency_claim.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/web-store/h2/V4__rate_limit_window_index.sql")),
        dataSource);

    assertDoesNotThrow(() -> new JdbcWebStoreSchemaValidator(jdbc).afterPropertiesSet());
  }
}
