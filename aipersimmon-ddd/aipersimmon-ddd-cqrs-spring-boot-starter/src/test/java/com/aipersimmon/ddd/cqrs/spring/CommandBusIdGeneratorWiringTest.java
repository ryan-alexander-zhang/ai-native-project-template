package com.aipersimmon.ddd.cqrs.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.id.AipersimmonDddIdAutoConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The command bus takes its message-id supplier from the {@link IdGenerator} bean, which is
 * <em>required</em>: {@code aipersimmon-ddd-id-spring-boot-starter} is a compile dependency of this
 * module, so a time-ordered UUIDv7 messageId is the only outcome in a real application. A context
 * assembled without any {@code IdGenerator} fails to start rather than silently minting random (v4)
 * ids and reintroducing the write amplification the SPI exists to remove — see {@code issue-00053}.
 *
 * <p>A messageId is opaque, so we assert only its UUID version — never any ordering the framework
 * must not expose.
 */
class CommandBusIdGeneratorWiringTest {

  record Ping() implements Command<String> {}

  static final class CapturingHandler implements CommandHandler<Ping, String> {
    final List<CommandContext> seen = new ArrayList<>();

    @Override
    public String handle(Ping command, CommandContext context) {
      seen.add(context);
      return "pong";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class HandlerConfig {
    @Bean
    CapturingHandler capturingHandler() {
      return new CapturingHandler();
    }
  }

  // This context has no database, so no transaction manager either — the one shape that
  // aipersimmon.ddd.cqrs.transaction.required=false exists for. Declaring it keeps these assertions
  // about id minting, and keeps the no-IdGenerator case failing for the reason it names.
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddCqrsAutoConfiguration.class))
          .withPropertyValues("aipersimmon.ddd.cqrs.transaction.required=false")
          .withUserConfiguration(HandlerConfig.class);

  @Test
  void mintsUuidv7MessageId_whenIdModulePresent() {
    runner
        .withConfiguration(AutoConfigurations.of(AipersimmonDddIdAutoConfiguration.class))
        .run(
            context -> {
              context.getBean(CommandBus.class).send(new Ping());
              CommandContext seen = context.getBean(CapturingHandler.class).seen.get(0);
              assertEquals(
                  7,
                  UUID.fromString(seen.messageId()).version(),
                  "with aipersimmon-ddd-id-spring-boot-starter the messageId is a UUIDv7");
              assertEquals(
                  seen.messageId(),
                  seen.correlationId(),
                  "a root send seeds correlation to its own (v7) id");
            });
  }

  @Test
  void failsToStart_whenNoIdGeneratorIsAvailable() {
    runner.run(
        context ->
            assertThat(context)
                .as(
                    "a missing IdGenerator must fail startup, not degrade silently to random (v4) ids")
                .getFailure()
                .hasMessageContaining("IdGenerator"));
  }
}
