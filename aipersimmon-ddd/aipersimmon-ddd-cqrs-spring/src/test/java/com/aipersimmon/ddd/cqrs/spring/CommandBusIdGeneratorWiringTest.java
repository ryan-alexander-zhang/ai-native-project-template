package com.aipersimmon.ddd.cqrs.spring;

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
 * The command bus resolves its message-id supplier from the {@link IdGenerator} bean. This is the
 * two-state acceptance for the CQRS minting point: with {@code aipersimmon-ddd-id} on the classpath
 * the messageId is a time-ordered UUIDv7; without it, the bus keeps its {@code UUID.randomUUID()}
 * (v4) default. A messageId is opaque, so we assert only its UUID version — never any ordering the
 * framework must not expose.
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

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddCqrsAutoConfiguration.class))
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
                  "with aipersimmon-ddd-id the messageId is a UUIDv7");
              assertEquals(
                  seen.messageId(),
                  seen.correlationId(),
                  "a root send seeds correlation to its own (v7) id");
            });
  }

  @Test
  void fallsBackToUuidv4MessageId_whenIdModuleAbsent() {
    runner.run(
        context -> {
          context.getBean(CommandBus.class).send(new Ping());
          CommandContext seen = context.getBean(CapturingHandler.class).seen.get(0);
          assertEquals(
              4,
              UUID.fromString(seen.messageId()).version(),
              "without aipersimmon-ddd-id the bus keeps its random-UUID (v4) default");
        });
  }
}
