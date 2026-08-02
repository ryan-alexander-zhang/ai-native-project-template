package com.aipersimmon.ddd.inbox.mybatisplus;

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
 * The MyBatis-Plus sibling of {@code JdbcInboxSchemaValidatorTest}: a missing inbox table fails
 * startup naming the configuration lever; a migrated schema passes silently.
 */
class MybatisPlusInboxSchemaValidatorTest {

  private static final AtomicInteger DB = new AtomicInteger();

  private final SimpleDriverDataSource dataSource =
      new SimpleDriverDataSource(
          new org.h2.Driver(),
          "jdbc:h2:mem:inbox-mp-schema" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
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
                new MybatisPlusInboxSchemaValidator(schemaMapper(dataSource)).afterPropertiesSet());

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
            new ClassPathResource("aipersimmon/db/migration/inbox/h2/V1__aipersimmon_inbox.sql"),
            new ClassPathResource("aipersimmon/db/migration/inbox/h2/V2__add_tenant_id.sql")),
        dataSource);

    assertDoesNotThrow(
        () -> new MybatisPlusInboxSchemaValidator(schemaMapper(dataSource)).afterPropertiesSet());
  }

  private static InboxSchemaMapper schemaMapper(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setEnvironment(
        new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(InboxSchemaMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new SqlSessionTemplate(factory).getMapper(InboxSchemaMapper.class);
  }
}
