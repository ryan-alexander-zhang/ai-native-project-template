package com.aipersimmon.ddd.tenancy.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.tenancy.TenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AipersimmonDddTenancyAutoConfigurationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddTenancyAutoConfiguration.class));

  @Test
  void wiresResolverInterceptorAndFilterWhenEnabled() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .run(
            context -> {
              assertTrue(context.containsBean("aipersimmonDddTenantResolver"));
              assertTrue(context.containsBean("aipersimmonDddTenantContextCommandInterceptor"));
              assertTrue(context.containsBean("aipersimmonDddTenantResolutionFilter"));
              assertInstanceOf(HeaderTenantResolver.class, context.getBean(TenantResolver.class));
            });
  }

  @Test
  void wiresNothingWhenDisabled() {
    runner.run(
        context -> {
          assertFalse(context.containsBean("aipersimmonDddTenantResolver"));
          assertFalse(context.containsBean("aipersimmonDddTenantContextCommandInterceptor"));
          assertFalse(context.containsBean("aipersimmonDddTenantResolutionFilter"));
        });
  }
}
