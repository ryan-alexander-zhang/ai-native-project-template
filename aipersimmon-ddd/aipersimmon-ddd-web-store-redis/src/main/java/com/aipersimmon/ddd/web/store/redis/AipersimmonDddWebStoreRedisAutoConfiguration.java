package com.aipersimmon.ddd.web.store.redis;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * Wires the Redis-backed web stores once a {@code StringRedisTemplate} is present.
 * Each bean is {@code @ConditionalOnMissingBean} on its SPI type, replacing the
 * {@code -web-spring} in-memory default while yielding to a consumer's own bean.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
public class AipersimmonDddWebStoreRedisAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore redisIdempotencyStore(StringRedisTemplate redis,
                                                  ObjectProvider<ObjectMapper> objectMapper) {
        return new RedisIdempotencyStore(redis, objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(ReplayGuard.class)
    public ReplayGuard redisReplayGuard(StringRedisTemplate redis) {
        return new RedisReplayGuard(redis);
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter redisRateLimiter(StringRedisTemplate redis, ObjectProvider<Clock> clock) {
        return new RedisRateLimiter(redis, clock.getIfAvailable(Clock::systemUTC));
    }
}
