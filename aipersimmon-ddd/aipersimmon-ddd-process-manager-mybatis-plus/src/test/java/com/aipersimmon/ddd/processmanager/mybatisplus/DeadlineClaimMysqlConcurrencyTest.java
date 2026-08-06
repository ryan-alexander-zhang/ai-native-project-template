package com.aipersimmon.ddd.processmanager.mybatisplus;

import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import javax.sql.DataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * The deadline claim on a real MySQL 8. Worth having separately from the PostgreSQL one rather than
 * trusting symmetry: {@code FOR UPDATE OF d SKIP LOCKED} names a table inside a join, and that is
 * exactly the sort of clause the two databases spell differently.
 */
class DeadlineClaimMysqlConcurrencyTest extends AbstractDeadlineClaimConcurrencyTest {

  private static final MySQLContainer<?> MYSQL = SharedContainers.mysql();

  @Override
  protected DataSource dataSource() {
    return TestDataSources.from(MYSQL);
  }

  @Override
  protected String vendor() {
    return "mysql";
  }
}
