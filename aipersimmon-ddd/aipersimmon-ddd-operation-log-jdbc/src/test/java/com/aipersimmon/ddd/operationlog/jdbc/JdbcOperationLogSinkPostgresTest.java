package com.aipersimmon.ddd.operationlog.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The blocker regression on a real PostgreSQL: a duplicate idempotency key appended <em>inside</em>
 * the caller's transaction must converge via {@code ON CONFLICT DO NOTHING} without aborting the
 * transaction, so a later insert and the commit still succeed. A catch-based convergence would
 * abort the transaction here and lose the business changes (see design-00008 §7.3 / decision-00017
 * 命题五).
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class JdbcOperationLogSinkPostgresTest {

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcOperationLogSink sink;
  private DataSourceTransactionManager txManager;

  @BeforeEach
  void setUp() {
    dataSource = TestDataSources.from(SharedContainers.postgres());
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/operation-log/postgresql/V1__aipersimmon_operation_log.sql")),
        dataSource);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("TRUNCATE TABLE aipersimmon_operation_log");
    sink =
        new JdbcOperationLogSink(
            jdbc, OperationLogDialectFactory.create(dataSource), new ObjectMapper());
    txManager = new DataSourceTransactionManager(dataSource);
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_operation_log", Integer.class);
  }

  @Test
  void selects_the_postgres_dialect() {
    assertInstanceOf(
        PostgresOperationLogDialect.class, OperationLogDialectFactory.create(dataSource));
  }

  @Test
  void duplicate_inside_transaction_does_not_abort_the_postgres_transaction() {
    new TransactionTemplate(txManager)
        .executeWithoutResult(
            status -> {
              sink.append(OperationLogTestEntries.entry("r1", "k1"));
              AppendResult duplicate = sink.append(OperationLogTestEntries.entry("r2", "k1"));
              assertEquals(new AppendResult.Duplicate("r1"), duplicate);
              // Would throw "current transaction is aborted" if convergence were catch-based:
              sink.append(OperationLogTestEntries.entry("r3", "k2"));
            });
    assertEquals(2, count());
  }
}
