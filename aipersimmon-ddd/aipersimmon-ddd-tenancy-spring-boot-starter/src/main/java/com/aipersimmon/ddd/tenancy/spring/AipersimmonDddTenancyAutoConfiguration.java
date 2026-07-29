package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.tenancy.TenantEnforcement;
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
import org.springframework.core.task.TaskDecorator;
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

  /**
   * Raises fail-closed tenant resolution for as long as this context is up, so infrastructure that
   * stamps or filters a {@code tenant_id} refuses to run without a binding instead of narrowing the
   * operation to the shared sentinel bucket.
   *
   * <p>{@code @ConditionalOnMissingBean} keeps this to one bean when a sibling tenancy module (the
   * MyBatis-Plus tenant-line interceptor) registers its own on the same property.
   */
  @Bean(initMethod = "enable", destroyMethod = "disable")
  @ConditionalOnMissingBean(TenantEnforcement.class)
  public TenantEnforcement aipersimmonDddTenantEnforcement() {
    return new TenantEnforcement();
  }

  /**
   * The default resolver reads the tenant from a request header, which is only trustworthy behind
   * an edge that rewrites it — so it must be affirmed with {@code
   * aipersimmon.ddd.tenancy.trust-header=true}. Define your own {@link TenantResolver} bean to
   * resolve from the authenticated principal instead.
   */
  @Bean
  @ConditionalOnMissingBean(TenantResolver.class)
  public TenantResolver aipersimmonDddTenantResolver(TenancyProperties properties) {
    if (!properties.isTrustHeader()) {
      throw new UntrustedTenantHeaderException(
          "refusing to resolve the tenant from the client-supplied '"
              + properties.getHeader()
              + "' header: define a TenantResolver bean that reads the authenticated principal, or"
              + " set aipersimmon.ddd.tenancy.trust-header=true if a trusted edge rewrites that"
              + " header.");
    }
    return new HeaderTenantResolver(properties.getHeader());
  }

  /**
   * Carries the tenant across thread hops for the executor Spring Boot auto-configures, so
   * {@code @Async} work keeps the submitting request's binding.
   *
   * <p>Backs off when the application defines its own decorator, because Boot applies a decorator
   * only when exactly one bean exists and contributing a second would silently disable theirs. A
   * deployment in that position composes tenant propagation into its own decorator; until it does,
   * async work that touches tenant-scoped data fails loudly rather than reading the wrong bucket.
   */
  @Bean
  @ConditionalOnClass(TaskDecorator.class)
  @ConditionalOnMissingBean(TaskDecorator.class)
  public TenantContextTaskDecorator aipersimmonDddTenantContextTaskDecorator() {
    return new TenantContextTaskDecorator();
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
            new TenantResolutionFilter(
                resolver, properties.getMissingPolicy(), properties.getExcludePaths()));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
    return registration;
  }
}
