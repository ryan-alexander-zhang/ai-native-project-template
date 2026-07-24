package com.aipersimmon.ddd.tenancy.mybatisplus;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers a {@link MybatisPlusInterceptor} carrying a {@link TenantLineInnerInterceptor} that
 * scopes the configured tables to the ambient {@link com.aipersimmon.ddd.tenancy.TenantContext}.
 *
 * <p>Active only when {@code aipersimmon.ddd.tenancy.enabled=true} and MyBatis-Plus is on the
 * classpath. Backs off if the application already defines its own {@link MybatisPlusInterceptor}
 * (so it can compose pagination, optimistic-lock, etc.): such an application should add {@code new
 * TenantLineInnerInterceptor(new TenantContextTenantLineHandler(column, tables))} to its own
 * interceptor, since MyBatis-Plus honours a single {@link MybatisPlusInterceptor} bean.
 *
 * <p>The interceptor is created eagerly (not gated on the {@code SqlSessionFactory} bean) so it
 * exists before MyBatis-Plus builds the session factory and is picked up; with an empty {@code
 * tenant-tables} set it rewrites nothing.
 */
@AutoConfiguration
@ConditionalOnClass({MybatisPlusInterceptor.class, SqlSessionFactory.class})
@ConditionalOnProperty(prefix = "aipersimmon.ddd.tenancy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenancyMybatisPlusProperties.class)
public class AipersimmonDddTenancyMybatisPlusAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
  MybatisPlusInterceptor aipersimmonTenantMybatisPlusInterceptor(
      TenancyMybatisPlusProperties properties) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(
        new TenantLineInnerInterceptor(
            new TenantContextTenantLineHandler(
                properties.getTenantColumn(), properties.getTenantTables())));
    return interceptor;
  }
}
