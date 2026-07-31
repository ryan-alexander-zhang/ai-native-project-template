package com.aipersimmon.ddd.persistence.mybatisplus;

import com.aipersimmon.ddd.mybatisplus.AipersimmonDddMybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * Contributes the {@link OptimisticLockerInnerInterceptor} that turns an {@code updateById} on a
 * {@code @Version} row into {@code SET version = version + 1 ... WHERE version = ?}.
 *
 * <p>That predicate is what makes {@link MybatisPlusAggregateRepository}'s affected-rows check mean
 * anything: without it every update matches its row, reports one row changed, and a writer working
 * from a stale snapshot silently discards a concurrent change.
 *
 * <p>It contributes an {@code InnerInterceptor} rather than a whole {@code MybatisPlusInterceptor}
 * because MyBatis-Plus honours one interceptor bean, so competing registrations back off silently
 * instead of composing. {@code aipersimmon-ddd-mybatis-plus-spring-boot-starter} owns the single
 * interceptor and assembles the contributions.
 *
 * <p>{@code @Order(300)} places it after tenant scoping (100) and a consumer's pagination (200),
 * following the order MyBatis-Plus recommends.
 */
@AutoConfiguration(before = AipersimmonDddMybatisPlusAutoConfiguration.class)
@ConditionalOnClass({OptimisticLockerInnerInterceptor.class, SqlSessionFactory.class})
public class AipersimmonDddPersistenceMybatisPlusAutoConfiguration {

  /** Ordered after tenant scoping (100) and a consumer's pagination (200). */
  public static final int ORDER = 300;

  @Bean
  @ConditionalOnMissingBean(OptimisticLockerInnerInterceptor.class)
  @Order(ORDER)
  public OptimisticLockerInnerInterceptor aipersimmonOptimisticLockerInnerInterceptor() {
    return new OptimisticLockerInnerInterceptor();
  }
}
