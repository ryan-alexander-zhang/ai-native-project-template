package com.aipersimmon.ddd.operationlog.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.Completion;
import com.aipersimmon.ddd.operationlog.model.EntryTimes;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationResult;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import com.aipersimmon.ddd.operationlog.model.Target;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** MyBatis-Plus sink scenarios shared by the H2 and PostgreSQL tests (backend equivalence). */
final class MybatisPlusSinkScenarios {

  private MybatisPlusSinkScenarios() {}

  static void run(DataSource dataSource, String dialectDirectory, boolean postgres) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/operation-log/"
                    + dialectDirectory
                    + "/V1__aipersimmon_operation_log.sql")),
        dataSource);
    jdbc.update("DELETE FROM aipersimmon_operation_log");

    OperationLogSink sink =
        new MybatisPlusOperationLogSink(mapper(dataSource), postgres, new ObjectMapper());

    // append + duplicate convergence
    assertEquals(new AppendResult.Appended("r1"), sink.append(entry("r1", "k1")));
    assertEquals(new AppendResult.Duplicate("r1"), sink.append(entry("r2", "k1")));
    assertEquals(1, count(jdbc));

    // a duplicate inside a transaction must not abort it (ON CONFLICT on PG, caught on H2)
    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .executeWithoutResult(
            status -> {
              sink.append(entry("r3", "k3"));
              assertInstanceOf(AppendResult.Duplicate.class, sink.append(entry("r4", "k3")));
              sink.append(entry("r5", "k5"));
            });
    assertEquals(3, count(jdbc));
  }

  private static OperationLogMapper mapper(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(OperationLogMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new SqlSessionTemplate(factory).getMapper(OperationLogMapper.class);
  }

  private static int count(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_operation_log", Integer.class);
  }

  private static OperationLogEntry entry(String recordId, String idempotencyKey) {
    return new OperationLogEntry(
        recordId,
        "orders",
        "acme",
        idempotencyKey,
        "order.update",
        Actor.system("sys"),
        Target.of("Order", "o1"),
        OperationResult.of(Outcome.SUCCEEDED, Completion.COMMITTED),
        "summary",
        List.of(),
        List.of(),
        null,
        Causality.none(),
        new EntryTimes(
            Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-01T00:00:05Z")),
        null,
        1);
  }
}
