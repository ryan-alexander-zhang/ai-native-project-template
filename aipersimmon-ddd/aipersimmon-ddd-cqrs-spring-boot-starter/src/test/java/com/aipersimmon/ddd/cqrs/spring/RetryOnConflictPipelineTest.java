package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Drives retry-on-conflict through the real bus with the full auto-configured interceptor chain —
 * not the interceptor in isolation. The failure the feature exists for is Spring's {@link
 * OptimisticLockingFailureException} thrown by a repository inside the transaction; for a retry to
 * happen, concurrency translation must run <em>inside</em> the retry interceptor (higher order), so
 * the exception is already {@link ConcurrencyConflictException} by the time it reaches the retry
 * loop on its way out. The isolated interceptor tests cannot see an ordering mistake between the
 * two; this test exists because one shipped.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.cqrs.retry-on-conflict.enabled=true",
      "aipersimmon.ddd.cqrs.retry-on-conflict.max-attempts=3",
      "aipersimmon.ddd.cqrs.retry-on-conflict.initial-backoff=1ms"
    })
class RetryOnConflictPipelineTest {

  @Autowired CommandBus commandBus;
  @Autowired FlakyHandler handler;

  @BeforeEach
  void reset() {
    handler.attempts.set(0);
    handler.failuresToThrow.set(0);
  }

  @Test
  void retriesTheRepositoryConflictAndSucceeds() {
    handler.failuresToThrow.set(1);

    String result = commandBus.send(new FlakyCommand());

    assertEquals("ok", result);
    assertEquals(2, handler.attempts.get());
  }

  @Test
  void exhaustionStillSurfacesAsTheTranslatedConflict() {
    handler.failuresToThrow.set(Integer.MAX_VALUE);

    assertThrows(ConcurrencyConflictException.class, () -> commandBus.send(new FlakyCommand()));

    // Default max-attempts is 3: the fallback is the pre-feature 409 path, after real retries.
    assertEquals(3, handler.attempts.get());
  }

  record FlakyCommand() implements Command<String> {}

  /** Fails the first N attempts the way a version-checked repository write does. */
  static final class FlakyHandler implements CommandHandler<FlakyCommand, String> {
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger failuresToThrow = new AtomicInteger();

    @Override
    public String handle(FlakyCommand command, CommandContext context) {
      attempts.incrementAndGet();
      if (failuresToThrow.getAndDecrement() > 0) {
        throw new OptimisticLockingFailureException("update matched zero rows");
      }
      return "ok";
    }
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestApp {
    @Bean
    FlakyHandler flakyHandler() {
      return new FlakyHandler();
    }
  }
}
