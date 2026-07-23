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
 * The catch-based convergence path on a real MySQL: unlike PostgreSQL, MySQL does not abort the
 * whole transaction when a statement hits the unique key, so the {@code DefaultOperationLogDialect}
 * (plain insert + caught {@code DuplicateKeyException}) is safe here — a duplicate appended
 * <em>inside</em> the caller's transaction converges and a later insert plus the commit still
 * succeed. This validates the dialect split in decision-00017 命题五 against the real engine (H2's
 * MySQL-compatibility mode is not real MySQL), and exercises the MySQL {@code V1} DDL ({@code
 * DATETIME(6)}, inline {@code KEY}, {@code CHARACTER SET ascii}, {@code ROW_FORMAT=DYNAMIC}).
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class JdbcOperationLogSinkMysqlTest {

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcOperationLogSink sink;
  private DataSourceTransactionManager txManager;

  @BeforeEach
  void setUp() {
    dataSource = TestDataSources.from(SharedContainers.mysql());
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/operation-log/mysql/V1__aipersimmon_operation_log.sql")),
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
  void selects_the_catch_based_default_dialect_for_mysql() {
    assertInstanceOf(
        DefaultOperationLogDialect.class, OperationLogDialectFactory.create(dataSource));
  }

  @Test
  void appends_and_converges_duplicate_to_the_existing_record_id() {
    assertEquals(
        new AppendResult.Appended("r1"),
        sink.append(OperationLogTestEntries.withChange("r1", "k1")));
    assertEquals(
        new AppendResult.Duplicate("r1"), sink.append(OperationLogTestEntries.entry("r2", "k1")));
    assertEquals(1, count());
  }

  @Test
  void duplicate_inside_transaction_does_not_abort_the_mysql_transaction() {
    new TransactionTemplate(txManager)
        .executeWithoutResult(
            status -> {
              sink.append(OperationLogTestEntries.entry("r1", "k1"));
              AppendResult duplicate = sink.append(OperationLogTestEntries.entry("r2", "k1"));
              assertEquals(new AppendResult.Duplicate("r1"), duplicate);
              // A catch-based convergence is safe on MySQL: the statement error does not abort the
              // transaction, so this later insert and the commit still succeed.
              sink.append(OperationLogTestEntries.entry("r3", "k2"));
            });
    assertEquals(2, count());
  }
}
