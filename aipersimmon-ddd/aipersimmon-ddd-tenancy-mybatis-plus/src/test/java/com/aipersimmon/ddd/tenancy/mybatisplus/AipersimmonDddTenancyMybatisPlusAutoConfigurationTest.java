package com.aipersimmon.ddd.tenancy.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AipersimmonDddTenancyMybatisPlusAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AipersimmonDddTenancyMybatisPlusAutoConfiguration.class));

  @Test
  void registersATenantInterceptorWhenTenancyIsEnabled() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
              assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
                  .anyMatch(i -> i instanceof TenantLineInnerInterceptor);
            });
  }

  @Test
  void backsOffWhenTenancyIsNotEnabled() {
    runner.run(context -> assertThat(context).doesNotHaveBean(MybatisPlusInterceptor.class));
  }

  @Test
  void backsOffWhenTheApplicationDefinesItsOwnInterceptor() {
    runner
        .withPropertyValues("aipersimmon.ddd.tenancy.enabled=true")
        .withUserConfiguration(CustomInterceptorConfig.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
              assertThat(context.getBean(MybatisPlusInterceptor.class))
                  .isSameAs(context.getBean("appInterceptor"));
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
