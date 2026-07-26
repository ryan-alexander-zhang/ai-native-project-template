package com.aipersimmon.ddd.tenancy.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.mybatisplus.AipersimmonDddMybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tenancy contributes a {@link TenantLineInnerInterceptor} rather than owning a whole {@link
 * MybatisPlusInterceptor}, so it composes with the other SQL-rewriting concerns instead of racing
 * them for the single interceptor bean (design-00011 §3).
 */
class AipersimmonDddTenancyMybatisPlusAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AipersimmonDddTenancyMybatisPlusAutoConfiguration.class,
                  AipersimmonDddMybatisPlusAutoConfiguration.class));

  @Test
  void contributesATenantLineInterceptorWhenTenancyIsEnabled() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TenantLineInnerInterceptor.class);
              assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
                  .as("the shared seam composes the contribution into the one interceptor")
                  .anyMatch(TenantLineInnerInterceptor.class::isInstance);
            });
  }

  @Test
  void contributesNothingWhenTenancyIsNotEnabled() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(TenantLineInnerInterceptor.class);
          assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
              .as("the interceptor still exists, carrying nothing")
              .isEmpty();
        });
  }

  @Test
  void anApplicationOwnedInterceptorTakesOverAssembly() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .withUserConfiguration(CustomInterceptorConfig.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
              assertThat(context.getBean(MybatisPlusInterceptor.class))
                  .isSameAs(context.getBean("appInterceptor"));
              assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
                  .as("taking over means taking over: the app must install contributions itself")
                  .isEmpty();
            });
  }

  @Configuration
  static class CustomInterceptorConfig {
    @Bean
    MybatisPlusInterceptor appInterceptor() {
      return new MybatisPlusInterceptor();
    }
  }
}
