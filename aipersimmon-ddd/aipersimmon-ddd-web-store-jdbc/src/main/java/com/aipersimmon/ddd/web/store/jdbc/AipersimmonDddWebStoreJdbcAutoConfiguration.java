package com.aipersimmon.ddd.web.store.jdbc;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the JdbcTemplate-backed web stores once a {@code JdbcTemplate} is present. Each bean is
 * {@code @ConditionalOnMissingBean} on its SPI type, so it replaces the {@code -web-spring}
 * in-memory default while still yielding to a consumer's own implementation.
 */
// beforeName, not before: this module must not compile against -web-spring-boot-starter. Without
// the edge, both this configuration and the web starter's in-memory fallback declare the same three
// beans under @ConditionalOnMissingBean, and whichever Spring happens to evaluate first wins — so
// adding this module would only sometimes replace the per-JVM stores (issue-00062, the same shape
// as issue-00044 on the outbox side).
@AutoConfiguration(
    after = JdbcTemplateAutoConfiguration.class,
    beforeName = "com.aipersimmon.ddd.web.spring.AipersimmonDddWebAutoConfiguration")
@EnableConfigurationProperties(WebStoreCleanupProperties.class)
public class AipersimmonDddWebStoreJdbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "aipersimmonDddWebStoreClock")
  public Clock aipersimmonDddWebStoreClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(IdempotencyStore.class)
  public IdempotencyStore jdbcIdempotencyStore(
      JdbcTemplate jdbc,
      ObjectProvider<ObjectMapper> objectMapper,
      Clock aipersimmonDddWebStoreClock) {
    return new JdbcIdempotencyStore(
        jdbc, objectMapper.getIfAvailable(ObjectMapper::new), aipersimmonDddWebStoreClock);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(ReplayGuard.class)
  public ReplayGuard jdbcReplayGuard(JdbcTemplate jdbc, Clock aipersimmonDddWebStoreClock) {
    return new JdbcReplayGuard(jdbc, aipersimmonDddWebStoreClock);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(RateLimiter.class)
  public RateLimiter jdbcRateLimiter(JdbcTemplate jdbc, Clock aipersimmonDddWebStoreClock) {
    return new JdbcRateLimiter(jdbc, aipersimmonDddWebStoreClock);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(JdbcWebStoreCleanup.class)
  public JdbcWebStoreCleanup jdbcWebStoreCleanup(
      JdbcTemplate jdbc, Clock aipersimmonDddWebStoreClock, WebStoreCleanupProperties properties) {
    return new JdbcWebStoreCleanup(
        jdbc, aipersimmonDddWebStoreClock, properties.getRateLimitRetention());
  }

  /**
   * Registered unless switched off. The three tables only ever delete the key in front of them, so
   * without this nothing removes a row whose key is never presented again — which is nearly all of
   * them.
   */
  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(WebStoreCleanupScheduler.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.store.cleanup",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public WebStoreCleanupScheduler webStoreCleanupScheduler(
      JdbcWebStoreCleanup cleanup, WebStoreCleanupProperties properties) {
    return new WebStoreCleanupScheduler(cleanup, properties.getPollDelay());
  }
}
