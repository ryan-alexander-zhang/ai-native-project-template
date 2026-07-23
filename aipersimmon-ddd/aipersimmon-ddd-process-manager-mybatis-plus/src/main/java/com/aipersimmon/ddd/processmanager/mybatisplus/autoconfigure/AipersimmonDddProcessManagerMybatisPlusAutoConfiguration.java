package com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.autoconfigure.AipersimmonDddProcessManagerAutoConfiguration;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessManagerProperties;
import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.mybatisplus.lease.MybatisProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.mybatisplus.lease.ProcessClaimMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessEffectStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessDeadlineMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessEffectMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessInstanceMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessTransitionMapper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The MyBatis-Plus storage backend for the Process Manager: it registers its own mappers (via
 * {@link MapperFactoryBean}, never a {@code @MapperScan}), contributes the four store beans,
 * selects the SKIP LOCKED vs. atomic claim from the {@code DataSource}, exposes the {@link
 * ProcessClaimStrategy}, and validates the schema at startup. Ordered <em>before</em> {@link
 * AipersimmonDddProcessManagerAutoConfiguration}, so the storage-agnostic engine then wires the
 * runtime, relay, and deadline worker on top. Every bean is {@link ConditionalOnMissingBean}; it
 * defines no JPA {@code @Entity} and executes no DDL.
 */
@AutoConfiguration(
    after = {
      MybatisPlusAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    },
    before = AipersimmonDddProcessManagerAutoConfiguration.class)
@ConditionalOnProperty(
    prefix = "aipersimmon.ddd.process-manager",
    name = "enabled",
    matchIfMissing = true)
@ConditionalOnBean(SqlSessionFactory.class)
@EnableConfigurationProperties(ProcessManagerProperties.class)
public class AipersimmonDddProcessManagerMybatisPlusAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public MapperFactoryBean<ProcessInstanceMapper> aipersimmonProcessInstanceMapper(
      SqlSessionFactory sqlSessionFactory) {
    return mapper(ProcessInstanceMapper.class, sqlSessionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public MapperFactoryBean<ProcessTransitionMapper> aipersimmonProcessTransitionMapper(
      SqlSessionFactory sqlSessionFactory) {
    return mapper(ProcessTransitionMapper.class, sqlSessionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public MapperFactoryBean<ProcessEffectMapper> aipersimmonProcessEffectMapper(
      SqlSessionFactory sqlSessionFactory) {
    return mapper(ProcessEffectMapper.class, sqlSessionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public MapperFactoryBean<ProcessDeadlineMapper> aipersimmonProcessDeadlineMapper(
      SqlSessionFactory sqlSessionFactory) {
    return mapper(ProcessDeadlineMapper.class, sqlSessionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public MapperFactoryBean<ProcessClaimMapper> aipersimmonProcessClaimMapper(
      SqlSessionFactory sqlSessionFactory) {
    return mapper(ProcessClaimMapper.class, sqlSessionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public MapperFactoryBean<ProcessSchemaMapper> aipersimmonProcessSchemaMapper(
      SqlSessionFactory sqlSessionFactory) {
    return mapper(ProcessSchemaMapper.class, sqlSessionFactory);
  }

  private static <T> MapperFactoryBean<T> mapper(
      Class<T> type, SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<T> factory = new MapperFactoryBean<>(type);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkerId processWorkerId(ProcessManagerProperties properties) {
    String configured = properties.getWorkerId();
    return (configured == null || configured.isBlank())
        ? WorkerId.generate()
        : new WorkerId(configured);
  }

  @Bean
  @ConditionalOnMissingBean
  public MybatisProcessInstanceStore mybatisProcessInstanceStore(ProcessInstanceMapper mapper) {
    return new MybatisProcessInstanceStore(mapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public MybatisProcessTransitionStore mybatisProcessTransitionStore(
      ProcessTransitionMapper mapper) {
    return new MybatisProcessTransitionStore(mapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public MybatisProcessEffectStore mybatisProcessEffectStore(ProcessEffectMapper mapper) {
    return new MybatisProcessEffectStore(mapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public MybatisProcessDeadlineStore mybatisProcessDeadlineStore(ProcessDeadlineMapper mapper) {
    return new MybatisProcessDeadlineStore(mapper);
  }

  @Bean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnMissingBean(ProcessClaimStrategy.class)
  public MybatisProcessClaimStrategy processClaimStrategy(
      ProcessClaimMapper mapper,
      WorkerId processWorkerId,
      ProcessManagerProperties properties,
      DataSource dataSource) {
    String id =
        "auto".equalsIgnoreCase(properties.getDialect())
            ? probe(dataSource)
            : properties.getDialect().toLowerCase(Locale.ROOT);
    boolean skipLocked =
        switch (id) {
          case "postgresql", "mysql" -> true;
          case "h2" -> false;
          default ->
              throw new IllegalStateException(
                  "unsupported process-manager dialect '"
                      + id
                      + "'; set aipersimmon.ddd.process-manager.dialect");
        };
    return new MybatisProcessClaimStrategy(mapper, id, skipLocked, processWorkerId);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.process-manager",
      name = "schema-validation",
      havingValue = "validate",
      matchIfMissing = true)
  public MybatisProcessSchemaValidator processSchemaValidator(ProcessSchemaMapper mapper) {
    return new MybatisProcessSchemaValidator(mapper);
  }

  private static String probe(DataSource dataSource) {
    String product;
    try (Connection connection = dataSource.getConnection()) {
      product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    } catch (SQLException e) {
      throw new IllegalStateException(
          "cannot probe the database product for dialect auto-selection", e);
    }
    if (product.contains("postgresql")) {
      return "postgresql";
    }
    if (product.contains("mysql") || product.contains("maria")) {
      return "mysql";
    }
    if (product.contains("h2")) {
      return "h2";
    }
    throw new IllegalStateException(
        "cannot auto-select a process-manager dialect for database '"
            + product
            + "'; set aipersimmon.ddd.process-manager.dialect explicitly");
  }
}
