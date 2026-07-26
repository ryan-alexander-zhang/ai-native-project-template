package com.aipersimmon.ddd.tenancy.mybatisplus;

import com.aipersimmon.ddd.mybatisplus.AipersimmonDddMybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * Contributes a {@link TenantLineInnerInterceptor} that scopes the configured tables to the ambient
 * {@link com.aipersimmon.ddd.tenancy.TenantContext}.
 *
 * <p>Active only when {@code aipersimmon.ddd.tenancy.enabled=true} and MyBatis-Plus is on the
 * classpath.
 *
 * <p>It contributes an {@code InnerInterceptor} rather than a whole {@link MybatisPlusInterceptor}:
 * MyBatis-Plus honours a single interceptor bean, so registering one here would not compose with
 * the optimistic locker or any other concern — whichever lost {@code @ConditionalOnMissingBean}
 * would back off silently and stop taking effect. {@code aipersimmon-ddd-mybatis-plus} owns the one
 * interceptor and assembles every contribution in {@code @Order} sequence; see {@code design-00011}
 * §3.
 *
 * <p>{@code @Order(100)} puts tenant scoping first, as MyBatis-Plus recommends. With an empty
 * {@code tenant-tables} set it rewrites nothing.
 */
@AutoConfiguration(before = AipersimmonDddMybatisPlusAutoConfiguration.class)
@ConditionalOnClass({MybatisPlusInterceptor.class, SqlSessionFactory.class})
@ConditionalOnProperty(prefix = "aipersimmon.ddd.tenancy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenancyMybatisPlusProperties.class)
public class AipersimmonDddTenancyMybatisPlusAutoConfiguration {

  /**
   * Ordered first of the framework's inner interceptors: multi-tenant scoping precedes the rest.
   */
  public static final int ORDER = 100;

  @Bean
  @ConditionalOnMissingBean(TenantLineInnerInterceptor.class)
  @Order(ORDER)
  TenantLineInnerInterceptor aipersimmonTenantLineInnerInterceptor(
      TenancyMybatisPlusProperties properties) {
    return new TenantLineInnerInterceptor(
        new TenantContextTenantLineHandler(
            properties.getTenantColumn(), properties.getTenantTables()));
  }
}
