package com.aipersimmon.ddd.tenancy.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantEnforcement;
import com.aipersimmon.ddd.tenancy.TenantResolver;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

class AipersimmonDddTenancyAutoConfigurationTest {

  private static final TenantEnforcement ENFORCEMENT = new TenantEnforcement();

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddTenancyAutoConfiguration.class));

  @AfterEach
  void tearDown() {
    // The enforcement bean's destroy method already lowers the flag; make a leak visible instead of
    // letting it bleed into sibling tests.
    ENFORCEMENT.disable();
  }

  @Test
  void wiresResolverInterceptorAndFilterWhenEnabledAndTheHeaderIsTrusted() {
    runner
        .withPropertyValues(
            "aipersimmon.ddd.tenancy.enabled=true", "aipersimmon.ddd.tenancy.trust-header=true")
        .run(
            context -> {
              assertTrue(context.containsBean("aipersimmonDddTenantResolver"));
              assertTrue(context.containsBean("aipersimmonDddTenantContextCommandInterceptor"));
              assertTrue(context.containsBean("aipersimmonDddTenantResolutionFilter"));
              assertInstanceOf(HeaderTenantResolver.class, context.getBean(TenantResolver.class));
            });
  }

  @Test
  void refusesToStartOnTheUntrustedHeaderDefault() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  hasCause(context.getStartupFailure(), UntrustedTenantHeaderException.class),
                  "expected an UntrustedTenantHeaderException, was: "
                      + context.getStartupFailure());
            });
  }

  @Test
  void aCustomResolverNeedsNoHeaderOptIn() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .withUserConfiguration(PrincipalResolverConfiguration.class)
        .run(
            context -> {
              assertNotNull(context.getBean(TenantResolver.class));
              assertFalse(
                  context.getBean(TenantResolver.class) instanceof HeaderTenantResolver,
                  "the consumer's resolver must win");
            });
  }

  @Test
  void enforcesFailClosedResolutionWhileTheContextIsUp() {
    assertFalse(TenantContext.isRequired());
    runner
        .withPropertyValues(
            "aipersimmon.ddd.tenancy.enabled=true", "aipersimmon.ddd.tenancy.trust-header=true")
        .run(context -> assertTrue(TenantContext.isRequired()));
    assertFalse(TenantContext.isRequired(), "closing the context must lower the flag again");
  }

  @Test
  void contributesATaskDecoratorUnlessTheApplicationOwnsOne() {
    runner
        .withPropertyValues(
            "aipersimmon.ddd.tenancy.enabled=true", "aipersimmon.ddd.tenancy.trust-header=true")
        .run(
            context ->
                assertInstanceOf(
                    TenantContextTaskDecorator.class, context.getBean(TaskDecorator.class)));

    runner
        .withPropertyValues(
            "aipersimmon.ddd.tenancy.enabled=true", "aipersimmon.ddd.tenancy.trust-header=true")
        .withUserConfiguration(OwnDecoratorConfiguration.class)
        .run(
            context ->
                assertEquals(
                    1,
                    context.getBeansOfType(TaskDecorator.class).size(),
                    "Boot applies a decorator only when exactly one bean exists"));
  }

  @Test
  void wiresNothingWhenDisabled() {
    runner.run(
        context -> {
          assertFalse(context.containsBean("aipersimmonDddTenantResolver"));
          assertFalse(context.containsBean("aipersimmonDddTenantContextCommandInterceptor"));
          assertFalse(context.containsBean("aipersimmonDddTenantResolutionFilter"));
          assertFalse(TenantContext.isRequired());
        });
  }

  private static boolean hasCause(Throwable failure, Class<?> type) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  @Configuration(proxyBeanMethods = false)
  static class PrincipalResolverConfiguration {
    @Bean
    TenantResolver tenantResolver() {
      return context -> Optional.of(Tenants.of("acme"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnDecoratorConfiguration {
    @Bean
    TaskDecorator taskDecorator() {
      return runnable -> runnable;
    }
  }
}
