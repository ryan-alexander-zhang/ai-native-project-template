package com.example.samples.s08;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Two commands whose handlers fail on their first attempt and succeed afterwards — one with the
 * exception the retry interceptor replays, one with the exception it must never replay.
 *
 * <p>A real race would demonstrate the same thing non-deterministically; these make the retry policy
 * itself observable, which is what the assertions are about.
 */
@TestConfiguration(proxyBeanMethods = false)
class FlakyOnce {

  record FailsWithConflictOnce() implements Command<Integer> {}

  record FailsWithDuplicateOnce() implements Command<Integer> {}

  static final class ConflictHandler
      implements CommandHandler<FailsWithConflictOnce, Integer> {
    private final AtomicInteger attempts = new AtomicInteger();

    @Override
    public Integer handle(FailsWithConflictOnce command, CommandContext context) {
      int attempt = attempts.incrementAndGet();
      if (attempt == 1) {
        throw new ConcurrencyConflictException("lost the race on the first attempt");
      }
      return attempt;
    }

    void reset() {
      attempts.set(0);
    }
  }

  static final class DuplicateHandler
      implements CommandHandler<FailsWithDuplicateOnce, Integer> {
    private final AtomicInteger attempts = new AtomicInteger();

    @Override
    public Integer handle(FailsWithDuplicateOnce command, CommandContext context) {
      int attempt = attempts.incrementAndGet();
      throw new DuplicateEntityException("this identity already exists (attempt " + attempt + ")");
    }

    int attempts() {
      return attempts.get();
    }

    void reset() {
      attempts.set(0);
    }
  }

  @Bean
  ConflictHandler conflictHandler() {
    return new ConflictHandler();
  }

  @Bean
  DuplicateHandler duplicateHandler() {
    return new DuplicateHandler();
  }
}
