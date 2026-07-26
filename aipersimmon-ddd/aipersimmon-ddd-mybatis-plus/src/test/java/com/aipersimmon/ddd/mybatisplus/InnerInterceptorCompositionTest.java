package com.aipersimmon.ddd.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Every {@link InnerInterceptor} bean lands in the one {@link MybatisPlusInterceptor}, in
 * {@code @Order} sequence, and a consumer that declares its own interceptor takes over completely.
 *
 * <p>This is the guard for {@code design-00011} §3: the composition exists precisely so that two
 * components contributing SQL-rewriting concerns cannot silently cancel each other out.
 */
class InnerInterceptorCompositionTest {

  /** A no-op inner interceptor; only its identity and position matter here. */
  private static class Marker implements InnerInterceptor {
    private final String name;

    Marker(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class TwoContributions {
    @Bean
    @Order(300)
    Marker last() {
      return new Marker("last");
    }

    @Bean
    @Order(100)
    Marker first() {
      return new Marker("first");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ConsumerOwnedInterceptor {
    @Bean
    MybatisPlusInterceptor mine() {
      return new MybatisPlusInterceptor();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AipersimmonDddMybatisPlusAutoConfiguration.class));

  @Test
  void contributionsAreComposedInOrderIntoOneInterceptor() {
    runner
        .withUserConfiguration(TwoContributions.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
              assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
                  .as("both contributions installed, lowest @Order first")
                  .extracting(Object::toString)
                  .containsExactly("first", "last");
            });
  }

  @Test
  void withNoContributionsTheInterceptorIsEmptyRatherThanAbsent() {
    runner.run(
        context ->
            assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors()).isEmpty());
  }

  @Test
  void aConsumerOwnedInterceptorWinsWholesale() {
    runner
        .withUserConfiguration(ConsumerOwnedInterceptor.class, TwoContributions.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
              assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
                  .as(
                      "the escape hatch hands assembly over entirely — contributions are not merged")
                  .isEmpty();
            });
  }
}
