package com.aipersimmon.ddd.id;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.core.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AipersimmonDddIdAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddIdAutoConfiguration.class));

  @Test
  void registersUuidv7GeneratorByDefault() {
    runner.run(
        context ->
            assertThat(context).getBean(IdGenerator.class).isInstanceOf(Uuidv7IdGenerator.class));
  }

  @Test
  void backsOffWhenApplicationSuppliesItsOwn() {
    runner
        .withUserConfiguration(CustomIdGeneratorConfig.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(IdGenerator.class);
              assertThat(context.getBean(IdGenerator.class).newId()).isEqualTo("fixed-id");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomIdGeneratorConfig {
    @Bean
    IdGenerator idGenerator() {
      return () -> "fixed-id";
    }
  }
}
