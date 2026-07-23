package com.aipersimmon.ddd.operationlog.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** The JDBC sink on H2: append, duplicate convergence, and a duplicate not poisoning the tx. */
class JdbcOperationLogSinkH2Test {

  private static final AtomicInteger DB = new AtomicInteger();

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcOperationLogSink sink;

  @BeforeEach
  void setUp() {
    dataSource =
        new SimpleDriverDataSource(
            new org.h2.Driver(),
            "jdbc:h2:mem:oplog" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/operation-log/h2/V1__aipersimmon_operation_log.sql")),
        dataSource);
    jdbc = new JdbcTemplate(dataSource);
    sink =
        new JdbcOperationLogSink(
            jdbc, OperationLogDialectFactory.create(dataSource), new ObjectMapper());
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_operation_log", Integer.class);
  }

  @Test
  void appends_a_row_and_serializes_changes() {
    AppendResult result = sink.append(OperationLogTestEntries.withChange("r1", "k1"));
    assertEquals(new AppendResult.Appended("r1"), result);
    assertEquals(1, count());
    assertNotNull(
        jdbc.queryForObject(
            "SELECT changes FROM aipersimmon_operation_log WHERE record_id = 'r1'", String.class));
  }

  @Test
  void converges_duplicate_to_existing_record_id() {
    sink.append(OperationLogTestEntries.entry("r1", "k1"));
    AppendResult result = sink.append(OperationLogTestEntries.entry("r2", "k1"));
    assertEquals(new AppendResult.Duplicate("r1"), result);
    assertEquals(1, count());
  }

  @Test
  void duplicate_inside_transaction_does_not_poison_subsequent_inserts() {
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.executeWithoutResult(
        status -> {
          sink.append(OperationLogTestEntries.entry("r1", "k1"));
          assertInstanceOf(
              AppendResult.Duplicate.class, sink.append(OperationLogTestEntries.entry("r2", "k1")));
          sink.append(OperationLogTestEntries.entry("r3", "k2"));
        });
    assertEquals(2, count());
  }
}
