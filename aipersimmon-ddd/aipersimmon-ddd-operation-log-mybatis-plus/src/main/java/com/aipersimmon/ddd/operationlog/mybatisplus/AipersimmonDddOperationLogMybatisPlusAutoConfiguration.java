package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.aipersimmon.ddd.operationlog.engine.autoconfigure.AipersimmonDddOperationLogAutoConfiguration;
import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the MyBatis-Plus {@link OperationLogSink} once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}. Registers only its own {@link OperationLogMapper} (a {@code
 * MapperFactoryBean}), so it never triggers or hijacks the consumer's {@code @MapperScan}. Ordered
 * before the engine so the sink exists when the engine's pipeline (conditional on a sink) is
 * evaluated. Every bean is conditional so an application can override it. Include exactly one
 * storage backend.
 */
@AutoConfiguration(
    after = {
      MybatisPlusAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    },
    before = AipersimmonDddOperationLogAutoConfiguration.class)
public class AipersimmonDddOperationLogMybatisPlusAutoConfiguration {

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<OperationLogMapper> operationLogMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<OperationLogMapper> factory =
        new MapperFactoryBean<>(OperationLogMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<OperationLogSchemaMapper> operationLogSchemaMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<OperationLogSchemaMapper> factory =
        new MapperFactoryBean<>(OperationLogSchemaMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(OperationLogSchemaMapper.class)
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.operation-log",
      name = "schema-validation",
      havingValue = "validate",
      matchIfMissing = true)
  public MybatisPlusOperationLogSchemaValidator operationLogSchemaValidator(
      OperationLogSchemaMapper mapper) {
    return new MybatisPlusOperationLogSchemaValidator(mapper);
  }

  @Bean
  @ConditionalOnBean(OperationLogMapper.class)
  @ConditionalOnMissingBean(OperationLogSink.class)
  public OperationLogSink operationLogSink(
      OperationLogMapper mapper, DataSource dataSource, ObjectProvider<ObjectMapper> objectMapper) {
    ObjectMapper mapperJson = objectMapper.getIfAvailable(ObjectMapper::new);
    return new MybatisPlusOperationLogSink(mapper, isPostgres(dataSource), mapperJson);
  }

  /**
   * Enables scheduling and wires the audit retention cleanup only when opted in; deleting audit
   * records is a statement, never a default.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      name = "aipersimmon.ddd.operation-log.cleanup.enabled",
      havingValue = "true")
  @EnableScheduling
  static class OperationLogCleanupConfiguration {

    // Gated on SqlSessionFactory, not on OperationLogMapper, even though the mapper is what it
    // needs. Spring processes a member class before the enclosing class's @Bean methods, so a
    // @ConditionalOnBean(OperationLogMapper.class) here is evaluated before the enclosing class has
    // registered operationLogMapper and never matches — this cleanup was unwireable. The
    // SqlSessionFactory comes from an earlier auto-configuration, so it is already a definition;
    // the mapper is resolved at instantiation time, by which point it exists.
    @Bean
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnMissingBean
    public MybatisPlusOperationLogCleanup operationLogCleanup(
        OperationLogMapper mapper,
        Clock operationLogClock,
        @Value("${aipersimmon.ddd.operation-log.cleanup.retention-seconds:31536000}")
            long retentionSeconds,
        @Value("${aipersimmon.ddd.operation-log.cleanup.batch-size:500}") int batchSize) {
      return new MybatisPlusOperationLogCleanup(
          mapper, operationLogClock, retentionSeconds, batchSize);
    }
  }

  private static boolean isPostgres(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      return connection
          .getMetaData()
          .getDatabaseProductName()
          .toLowerCase(Locale.ROOT)
          .contains("postgresql");
    } catch (SQLException e) {
      throw new OperationLogException("cannot probe the database product for dialect selection", e);
    }
  }
}
