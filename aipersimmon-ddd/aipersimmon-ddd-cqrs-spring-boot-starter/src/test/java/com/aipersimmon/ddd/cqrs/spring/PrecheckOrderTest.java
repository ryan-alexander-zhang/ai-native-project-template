package com.aipersimmon.ddd.cqrs.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * {@code @Order} on a {@link CommandPrecheck} decides which of two true refusals the client is told
 * about, so it is part of the contract ({@code CommandPrecheck} javadoc, sample s19). Honouring it
 * is the auto-configuration's job: {@code ObjectProvider.stream()} yields beans in registration
 * order and ignores {@code @Order} entirely — only {@code orderedStream()} sorts (issue-00173).
 *
 * <p>The two prechecks below are named and declared so that registration order <em>contradicts</em>
 * {@code @Order}: {@code alphaLate} is {@code @Order(20)} and declared first, {@code zuluEarly} is
 * {@code @Order(10)} and declared second. A test whose names happen to agree with its order — as
 * s19's do — passes under either implementation and guards nothing.
 */
class PrecheckOrderTest {

  record Settle(String id) implements Command<String> {}

  static final class SettleHandler implements CommandHandler<Settle, String> {
    @Override
    public String handle(Settle command, CommandContext context) {
      return command.id();
    }
  }

  /** Records the order the prechecks actually ran in, and refuses on demand. */
  static final class Log {
    final List<String> ran = new ArrayList<>();
  }

  static class RecordingPrecheck implements CommandPrecheck<Settle> {
    private final Log log;
    private final String name;
    private final boolean refuses;

    RecordingPrecheck(Log log, String name, boolean refuses) {
      this.log = log;
      this.name = name;
      this.refuses = refuses;
    }

    @Override
    public void check(Settle command, CommandContext context) {
      log.ran.add(name);
      if (refuses) {
        throw new IllegalStateException(name + " refuses");
      }
    }
  }

  static final class AlphaLate extends RecordingPrecheck {
    AlphaLate(Log log, boolean refuses) {
      super(log, "alpha-order-20", refuses);
    }
  }

  static final class ZuluEarly extends RecordingPrecheck {
    ZuluEarly(Log log, boolean refuses) {
      super(log, "zulu-order-10", refuses);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PassingPrechecks {
    @Bean
    Log log() {
      return new Log();
    }

    @Bean
    SettleHandler settleHandler() {
      return new SettleHandler();
    }

    // Declared first on purpose: registration order is the order the defective implementation used.
    @Bean
    @Order(20)
    AlphaLate alphaLate(Log log) {
      return new AlphaLate(log, false);
    }

    @Bean
    @Order(10)
    ZuluEarly zuluEarly(Log log) {
      return new ZuluEarly(log, false);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RefusingPrechecks {
    @Bean
    Log log() {
      return new Log();
    }

    @Bean
    SettleHandler settleHandler() {
      return new SettleHandler();
    }

    @Bean
    @Order(20)
    AlphaLate alphaLate(Log log) {
      return new AlphaLate(log, true);
    }

    @Bean
    @Order(10)
    ZuluEarly zuluEarly(Log log) {
      return new ZuluEarly(log, true);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddCqrsAutoConfiguration.class))
          .withPropertyValues("aipersimmon.ddd.cqrs.transaction.required=false")
          .withBean(IdGenerator.class, () -> () -> "id-1");

  @Test
  void prechecksRunInAtOrderNotInRegistrationOrder() {
    runner
        .withUserConfiguration(PassingPrechecks.class)
        .run(
            context -> {
              context.getBean(CommandBus.class).send(new Settle("s-1"));

              assertThat(context.getBean(Log.class).ran)
                  .containsExactly("zulu-order-10", "alpha-order-20");
            });
  }

  @Test
  void theLowestAtOrderRefusalIsTheOneTheClientIsTold() {
    runner
        .withUserConfiguration(RefusingPrechecks.class)
        .run(
            context -> {
              CommandBus bus = context.getBean(CommandBus.class);

              assertThatThrownBy(() -> bus.send(new Settle("s-1")))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("zulu-order-10 refuses");
              // The later precheck was never asked — a refusal stops the chain.
              assertThat(context.getBean(Log.class).ran).containsExactly("zulu-order-10");
            });
  }
}
