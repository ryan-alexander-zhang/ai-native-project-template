package com.aipersimmon.ddd.openapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AipersimmonDddOpenApiAutoConfigurationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddOpenApiAutoConfiguration.class));

  @Test
  void registersTheProblemResponsesCustomizerByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(ProblemResponsesCustomizer.class));
  }

  @Test
  void backsOffEntirelyWhenDisabled() {
    runner
        .withPropertyValues("aipersimmon.ddd.openapi.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ProblemResponsesCustomizer.class));
  }

  @Test
  void suppressesTheProblemResponsesWhenDefaultsDisabled() {
    runner
        .withPropertyValues("aipersimmon.ddd.openapi.default-problem-responses=false")
        .run(context -> assertThat(context).doesNotHaveBean(ProblemResponsesCustomizer.class));
  }

  @Test
  void backsOffToAUserProvidedCustomizer() {
    runner
        .withUserConfiguration(CustomCustomizerConfig.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ProblemResponsesCustomizer.class);
              assertThat(context.getBean(ProblemResponsesCustomizer.class))
                  .isSameAs(context.getBean("myCustomizer"));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomCustomizerConfig {
    @Bean
    ProblemResponsesCustomizer myCustomizer() {
      return new ProblemResponsesCustomizer();
    }
  }
}
