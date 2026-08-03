package com.example.samples.s06;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The degradation is loud, and this is the proof.
 *
 * <p>A service with no database gets no transaction manager, which means the CQRS starter's headline
 * guarantee — one command, one transaction — cannot hold. The library's answer is not to shrug: it fails
 * startup with a {@code FailureAnalyzer} naming the two ways out, and only a deployment that says
 * {@code transaction.required=false} out loud gets to run without one (with a WARN on every boot, so it
 * cannot become the unnoticed state of a service that later grows a database).
 *
 * <p>Worth a test rather than a paragraph, because "we picked the right bundle for a stateless service"
 * is exactly the kind of claim that turns out to have been silently wrong.
 */
class TransactionlessDeclarationTest {

  @Test
  void withoutTheDeclarationTheStarterRefusesToStart() {
    assertThatThrownBy(() -> boot("--aipersimmon.ddd.cqrs.transaction.required=true"))
        .hasStackTraceContaining("PlatformTransactionManager");
  }

  @Test
  void withTheDeclarationItStartsAndSaysSo() {
    try (ConfigurableApplicationContext context =
        boot("--aipersimmon.ddd.cqrs.transaction.required=false")) {
      assertThat(context.isActive()).isTrue();
      // And the guarantee really is gone: no interceptor is there to open a transaction. A service that
      // later adds a DataSource gets the interceptor back — and the WARN goes away — without touching
      // a line of application code.
      assertThat(context.getBeanNamesForType(org.springframework.transaction.PlatformTransactionManager.class))
          .isEmpty();
    }
  }

  /**
   * Command-line arguments, not {@code properties(...)}.
   *
   * <p>{@code SpringApplicationBuilder.properties} contributes <em>default</em> properties, which sit
   * below {@code application.yaml} in Spring's precedence order — so the first version of this test
   * "proved" the app starts happily without the declaration, when in fact the yaml's {@code false} had
   * simply won. A test that configures the thing it is testing has to use a source that outranks the file
   * it is overriding.
   */
  private ConfigurableApplicationContext boot(String... args) {
    return new SpringApplicationBuilder(RiskServiceApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }
}
