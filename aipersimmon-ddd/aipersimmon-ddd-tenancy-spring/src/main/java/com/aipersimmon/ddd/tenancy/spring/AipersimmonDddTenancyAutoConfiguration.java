package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.tenancy.TenantResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wires multi-tenancy resolution when {@code aipersimmon.ddd.tenancy.enabled=true}. When disabled
 * (the default) nothing here is contributed, so commands run under the {@code __root__} sentinel
 * and behaviour is identical to before tenancy existed.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "aipersimmon.ddd.tenancy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenancyProperties.class)
public class AipersimmonDddTenancyAutoConfiguration {

  /** The default resolver reads the tenant from a request header; override with your own bean. */
  @Bean
  @ConditionalOnMissingBean(TenantResolver.class)
  public TenantResolver aipersimmonDddTenantResolver(TenancyProperties properties) {
    return new HeaderTenantResolver(properties.getHeader());
  }

  /** Binds the ambient TenantContext from each command's tenant for the whole handling. */
  @Bean
  @ConditionalOnClass(CommandInterceptor.class)
  @ConditionalOnMissingBean(name = "aipersimmonDddTenantContextCommandInterceptor")
  public TenantContextCommandInterceptor aipersimmonDddTenantContextCommandInterceptor() {
    return new TenantContextCommandInterceptor();
  }

  /** The edge filter, registered just after the request-id filter. Web applications only. */
  @Bean
  @ConditionalOnWebApplication
  @ConditionalOnClass(OncePerRequestFilter.class)
  @ConditionalOnMissingBean(name = "aipersimmonDddTenantResolutionFilter")
  public FilterRegistrationBean<TenantResolutionFilter> aipersimmonDddTenantResolutionFilter(
      TenantResolver resolver, TenancyProperties properties) {
    FilterRegistrationBean<TenantResolutionFilter> registration =
        new FilterRegistrationBean<>(
            new TenantResolutionFilter(resolver, properties.getMissingPolicy()));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
    return registration;
  }
}
