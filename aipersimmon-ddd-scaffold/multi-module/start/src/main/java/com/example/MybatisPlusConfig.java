package com.example;

import com.aipersimmon.ddd.tenancy.mybatisplus.TenancyMybatisPlusProperties;
import com.aipersimmon.ddd.tenancy.mybatisplus.TenantContextTenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's single {@link MybatisPlusInterceptor}, carrying both inner interceptors this
 * scaffold needs.
 *
 * <p><strong>Why the application owns it.</strong> MyBatis-Plus honours exactly one {@code
 * MybatisPlusInterceptor} bean, and {@code AipersimmonDddTenancyMybatisPlusAutoConfiguration}
 * registers one under {@code @ConditionalOnMissingBean(MybatisPlusInterceptor.class)}. So a second
 * auto-configuration contributing the optimistic locker would not compose with it — it would lose
 * the condition and back off silently, leaving {@code @Version} without its {@code WHERE version =
 * ?} predicate. Every {@code updateById} would then report one row updated and the oversell that
 * issue-00051 is about would still happen, while looking fixed. Declaring the interceptor here
 * takes the documented escape hatch: tenancy backs off as a whole and this bean supplies both
 * concerns.
 *
 * <p>Order follows the MyBatis-Plus guidance — multi-tenant first, optimistic lock last:
 *
 * <ol>
 *   <li>{@link TenantLineInnerInterceptor} appends {@code tenant_id} on insert and {@code WHERE
 *       tenant_id = ?} elsewhere, for the tables listed under {@code
 *       aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables}.
 *   <li>{@link OptimisticLockerInnerInterceptor} turns an {@code updateById} on a {@code @Version}
 *       entity into {@code SET version = version + 1 ... WHERE version = ?}, so a writer working
 *       from a stale snapshot updates no row and the repository raises {@code
 *       OptimisticLockingFailureException} (mapped to HTTP 409).
 * </ol>
 *
 * <p>design-00011 §3 folds this into the framework as an {@code InnerInterceptor} contribution
 * model, at which point this class goes away.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenancyMybatisPlusProperties.class)
public class MybatisPlusConfig {

  @Bean
  MybatisPlusInterceptor mybatisPlusInterceptor(TenancyMybatisPlusProperties tenancy) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(
        new TenantLineInnerInterceptor(
            new TenantContextTenantLineHandler(
                tenancy.getTenantColumn(), tenancy.getTenantTables())));
    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
    return interceptor;
  }
}
