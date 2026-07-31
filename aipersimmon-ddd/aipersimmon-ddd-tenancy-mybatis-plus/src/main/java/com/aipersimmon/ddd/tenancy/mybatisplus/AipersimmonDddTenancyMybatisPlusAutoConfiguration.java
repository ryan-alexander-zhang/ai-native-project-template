package com.aipersimmon.ddd.tenancy.mybatisplus;

import com.aipersimmon.ddd.mybatisplus.AipersimmonDddMybatisPlusAutoConfiguration;
import com.aipersimmon.ddd.tenancy.TenantEnforcement;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * would back off silently and stop taking effect. {@code
 * aipersimmon-ddd-mybatis-plus-spring-boot-starter} owns the one interceptor and assembles every
 * contribution in {@code @Order} sequence.
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

  /**
   * Raises fail-closed tenant resolution, so the tenant-line handler below refuses to rewrite a
   * query when no tenant is bound instead of narrowing it to the shared sentinel bucket.
   *
   * <p>Registered here as well as in {@code aipersimmon-ddd-tenancy-spring-boot-starter} because
   * this module can be used without it — and this is the module that rewrites the SQL, so it must
   * not depend on a sibling being present to be safe. {@code @ConditionalOnMissingBean} keeps it to
   * one bean when both are.
   */
  @Bean(initMethod = "enable", destroyMethod = "disable")
  @ConditionalOnMissingBean(TenantEnforcement.class)
  TenantEnforcement aipersimmonDddTenantEnforcement() {
    return new TenantEnforcement();
  }

  @Bean
  @ConditionalOnMissingBean(TenantLineInnerInterceptor.class)
  @Order(ORDER)
  TenantLineInnerInterceptor aipersimmonTenantLineInnerInterceptor(
      TenancyMybatisPlusProperties properties) {
    return new TenantLineInnerInterceptor(
        new TenantContextTenantLineHandler(
            properties.getTenantColumn(), properties.getTenantTables()));
  }

  /**
   * Runs the {@link TenantTableRegistrationGuard} once every singleton is up — after migrations, so
   * the schema it inspects is the one the application will run against. A {@code
   * SmartInitializingSingleton} rather than eager bean init, because the guard needs the fully
   * migrated database, not the bean graph.
   */
  @Bean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.tenancy.mybatis-plus",
      name = "guard-tables",
      havingValue = "true",
      matchIfMissing = true)
  SmartInitializingSingleton aipersimmonTenantTableRegistrationGuard(
      ObjectProvider<DataSource> dataSource, TenancyMybatisPlusProperties properties) {
    return () ->
        new TenantTableRegistrationGuard(
                dataSource.getObject(),
                properties.getTenantColumn(),
                properties.getTenantTables(),
                properties.getExemptTables())
            .verify();
  }
}
