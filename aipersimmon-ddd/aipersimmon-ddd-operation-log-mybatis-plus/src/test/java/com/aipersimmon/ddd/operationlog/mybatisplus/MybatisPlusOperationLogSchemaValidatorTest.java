package com.aipersimmon.ddd.operationlog.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The startup probe on the audit table: a missing audit table fails startup naming the
 * configuration lever; a migrated schema passes silently.
 */
class MybatisPlusOperationLogSchemaValidatorTest {

  private static final AtomicInteger DB = new AtomicInteger();

  private final SimpleDriverDataSource dataSource =
      new SimpleDriverDataSource(
          new org.h2.Driver(),
          "jdbc:h2:mem:oplog-mp-schema" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
          "sa",
          "");

  @AfterEach
  void tearDown() {
    new JdbcTemplate(dataSource).execute("SHUTDOWN");
  }

  @Test
  void aMissingTableFailsStartupNamingTheConfigurationThatCreatesIt() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new MybatisPlusOperationLogSchemaValidator(schemaMapper(dataSource))
                    .afterPropertiesSet());

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
                "aipersimmon/db/migration/operation-log/h2/V1__aipersimmon_operation_log.sql")),
        dataSource);

    assertDoesNotThrow(
        () ->
            new MybatisPlusOperationLogSchemaValidator(schemaMapper(dataSource))
                .afterPropertiesSet());
  }

  private static OperationLogSchemaMapper schemaMapper(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setEnvironment(
        new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(OperationLogSchemaMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new SqlSessionTemplate(factory).getMapper(OperationLogSchemaMapper.class);
  }
}
