package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.autoconfigure.AipersimmonDddProcessManagerAutoConfiguration;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessManagerProperties;
import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The JDBC storage backend for the Process Manager: it contributes the four store beans over a
 * {@code JdbcTemplate}, selects the SQL dialect (SKIP LOCKED vs. atomic conditional UPDATE) from
 * the {@code DataSource}, exposes the {@link ProcessClaimStrategy}, and validates the schema at
 * startup. It is ordered <em>before</em> {@link AipersimmonDddProcessManagerAutoConfiguration}, so
 * the storage-agnostic engine then wires the runtime, relay, and deadline worker on top of these
 * beans. Every bean is {@link ConditionalOnMissingBean}; it never executes DDL.
 */
@AutoConfiguration(
    after = {
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    },
    before = AipersimmonDddProcessManagerAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(
    prefix = "aipersimmon.ddd.process-manager",
    name = "enabled",
    matchIfMissing = true)
@EnableConfigurationProperties(ProcessManagerProperties.class)
public class AipersimmonDddProcessManagerJdbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public WorkerId processWorkerId(ProcessManagerProperties properties) {
    String configured = properties.getWorkerId();
    return (configured == null || configured.isBlank())
        ? WorkerId.generate()
        : new WorkerId(configured);
  }

  @Bean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnMissingBean
  public JdbcProcessDialect processDialect(
      ProcessManagerProperties properties, DataSource dataSource) {
    return ProcessDialectFactory.create(properties.getDialect(), dataSource);
  }

  @Bean
  @ConditionalOnBean({JdbcTemplate.class, JdbcProcessDialect.class})
  @ConditionalOnMissingBean(ProcessClaimStrategy.class)
  public JdbcProcessClaimStrategy processClaimStrategy(
      JdbcTemplate jdbcTemplate, JdbcProcessDialect dialect, WorkerId processWorkerId) {
    return new JdbcProcessClaimStrategy(jdbcTemplate, dialect, processWorkerId);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  public JdbcProcessInstanceStore jdbcProcessInstanceStore(JdbcTemplate jdbcTemplate) {
    return new JdbcProcessInstanceStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  public JdbcProcessTransitionStore jdbcProcessTransitionStore(JdbcTemplate jdbcTemplate) {
    return new JdbcProcessTransitionStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  public JdbcProcessEffectStore jdbcProcessEffectStore(JdbcTemplate jdbcTemplate) {
    return new JdbcProcessEffectStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  public JdbcProcessDeadlineStore jdbcProcessDeadlineStore(JdbcTemplate jdbcTemplate) {
    return new JdbcProcessDeadlineStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.process-manager",
      name = "schema-validation",
      havingValue = "validate",
      matchIfMissing = true)
  public JdbcProcessSchemaValidator processSchemaValidator(JdbcTemplate jdbcTemplate) {
    return new JdbcProcessSchemaValidator(jdbcTemplate);
  }
}
