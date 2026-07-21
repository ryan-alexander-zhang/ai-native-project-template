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
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the JdbcTemplate-backed web stores once a {@code JdbcTemplate} is present. Each bean is
 * {@code @ConditionalOnMissingBean} on its SPI type, so it replaces the {@code -web-spring}
 * in-memory default while still yielding to a consumer's own implementation.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
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
}
