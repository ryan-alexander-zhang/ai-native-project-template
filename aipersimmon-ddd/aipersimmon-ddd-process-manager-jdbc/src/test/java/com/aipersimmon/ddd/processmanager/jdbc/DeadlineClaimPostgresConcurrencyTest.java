package com.aipersimmon.ddd.processmanager.jdbc;

import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** The deadline claim on a real PostgreSQL. See {@link AbstractDeadlineClaimConcurrencyTest}. */
class DeadlineClaimPostgresConcurrencyTest extends AbstractDeadlineClaimConcurrencyTest {

  private static final PostgreSQLContainer<?> POSTGRES = SharedContainers.postgres();

  @Override
  protected DataSource dataSource() {
    return TestDataSources.from(POSTGRES);
  }

  @Override
  protected String vendor() {
    return "postgresql";
  }
}
