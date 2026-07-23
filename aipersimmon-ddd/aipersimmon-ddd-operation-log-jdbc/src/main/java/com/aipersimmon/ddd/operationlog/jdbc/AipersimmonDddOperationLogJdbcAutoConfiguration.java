package com.aipersimmon.ddd.operationlog.jdbc;

import com.aipersimmon.ddd.operationlog.engine.autoconfigure.AipersimmonDddOperationLogAutoConfiguration;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the JDBC {@link OperationLogSink} once a {@code JdbcTemplate} is available, selecting the
 * dialect from the DataSource. Ordered before the engine's auto-configuration so the sink exists
 * when the engine's {@code OperationLogs} pipeline (conditional on a sink) is evaluated. Every bean
 * is conditional so an application can override it.
 */
@AutoConfiguration(
    after = {
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    },
    before = AipersimmonDddOperationLogAutoConfiguration.class)
public class AipersimmonDddOperationLogJdbcAutoConfiguration {

  /** The JDBC sink; binds only when a JdbcTemplate is present and no sink was already defined. */
  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(OperationLogSink.class)
  public OperationLogSink operationLogSink(
      JdbcTemplate jdbcTemplate, DataSource dataSource, ObjectProvider<ObjectMapper> objectMapper) {
    ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
    JdbcOperationLogDialect dialect = OperationLogDialectFactory.create(dataSource);
    return new JdbcOperationLogSink(jdbcTemplate, dialect, mapper);
  }
}
