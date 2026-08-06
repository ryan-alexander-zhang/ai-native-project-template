package com.aipersimmon.ddd.web.store.mybatisplus;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the MyBatis-Plus-backed web stores once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}. Each store bean is {@code @ConditionalOnMissingBean} on its SPI type, so it
 * replaces the {@code -web-spring} in-memory default while still yielding to a consumer's own
 * implementation. Only this module's own mappers are registered (each a {@code MapperFactoryBean}),
 * so it never triggers or hijacks the consumer's {@code @MapperScan}.
 */
// beforeName, not before: this module must not compile against -web-spring-boot-starter. Without
// the edge, both this configuration and the web starter's in-memory fallback declare the same three
// beans under @ConditionalOnMissingBean, and whichever Spring happens to evaluate first wins — so
// adding this module would only sometimes replace the per-JVM stores — the same silent-race shape
// the outbox transport selection once had.
@AutoConfiguration(
    after = MybatisPlusAutoConfiguration.class,
    beforeName = "com.aipersimmon.ddd.web.spring.AipersimmonDddWebAutoConfiguration")
@EnableConfigurationProperties(WebStoreCleanupProperties.class)
public class AipersimmonDddWebStoreMybatisPlusAutoConfiguration {

  // Name-scoped so this component always contributes its own named clock and injects it by name,
  // rather than backing off when another component already registered a Clock of the same type.
  @Bean
  @ConditionalOnMissingBean(name = "aipersimmonDddWebStoreClock")
  public Clock aipersimmonDddWebStoreClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<IdempotencyMapper> aipersimmonWebIdempotencyMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<IdempotencyMapper> factory = new MapperFactoryBean<>(IdempotencyMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<NonceMapper> aipersimmonWebNonceMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<NonceMapper> factory = new MapperFactoryBean<>(NonceMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<RateLimitMapper> aipersimmonWebRateLimitMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<RateLimitMapper> factory = new MapperFactoryBean<>(RateLimitMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<WebStoreSchemaMapper> aipersimmonWebStoreSchemaMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<WebStoreSchemaMapper> factory =
        new MapperFactoryBean<>(WebStoreSchemaMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(IdempotencyMapper.class)
  @ConditionalOnMissingBean(IdempotencyStore.class)
  public IdempotencyStore mybatisPlusIdempotencyStore(
      IdempotencyMapper mapper,
      ObjectProvider<ObjectMapper> objectMapper,
      Clock aipersimmonDddWebStoreClock) {
    return new MybatisPlusIdempotencyStore(
        mapper, objectMapper.getIfAvailable(ObjectMapper::new), aipersimmonDddWebStoreClock);
  }

  @Bean
  @ConditionalOnBean(NonceMapper.class)
  @ConditionalOnMissingBean(ReplayGuard.class)
  public ReplayGuard mybatisPlusReplayGuard(NonceMapper mapper, Clock aipersimmonDddWebStoreClock) {
    return new MybatisPlusReplayGuard(mapper, aipersimmonDddWebStoreClock);
  }

  @Bean
  @ConditionalOnBean(RateLimitMapper.class)
  @ConditionalOnMissingBean(RateLimiter.class)
  public RateLimiter mybatisPlusRateLimiter(
      RateLimitMapper mapper, Clock aipersimmonDddWebStoreClock) {
    return new MybatisPlusRateLimiter(mapper, aipersimmonDddWebStoreClock);
  }

  @Bean
  @ConditionalOnBean(WebStoreSchemaMapper.class)
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.store",
      name = "schema-validation",
      havingValue = "validate",
      matchIfMissing = true)
  public MybatisPlusWebStoreSchemaValidator mybatisPlusWebStoreSchemaValidator(
      WebStoreSchemaMapper mapper) {
    return new MybatisPlusWebStoreSchemaValidator(mapper);
  }

  @Bean
  @ConditionalOnBean(IdempotencyMapper.class)
  @ConditionalOnMissingBean(MybatisPlusWebStoreCleanup.class)
  public MybatisPlusWebStoreCleanup mybatisPlusWebStoreCleanup(
      IdempotencyMapper idempotencyMapper,
      NonceMapper nonceMapper,
      RateLimitMapper rateLimitMapper,
      Clock aipersimmonDddWebStoreClock,
      WebStoreCleanupProperties properties) {
    return new MybatisPlusWebStoreCleanup(
        idempotencyMapper,
        nonceMapper,
        rateLimitMapper,
        aipersimmonDddWebStoreClock,
        properties.getRateLimitRetention());
  }

  /**
   * Registered unless switched off. The three tables only ever delete the key in front of them, so
   * without this nothing removes a row whose key is never presented again — which is nearly all of
   * them.
   */
  @Bean
  @ConditionalOnBean(MybatisPlusWebStoreCleanup.class)
  @ConditionalOnMissingBean(WebStoreCleanupScheduler.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.store.cleanup",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public WebStoreCleanupScheduler webStoreCleanupScheduler(
      MybatisPlusWebStoreCleanup cleanup, WebStoreCleanupProperties properties) {
    return new WebStoreCleanupScheduler(cleanup, properties.getPollDelay());
  }
}
