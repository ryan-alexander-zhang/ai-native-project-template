package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor.Invocation;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryOnConflictCommandInterceptorTest {

  private record Rename(String name) implements Command<String> {}

  private static final Rename COMMAND = new Rename("x");
  private static final CommandContext CONTEXT = CommandContext.root(Tenants.of("demo"), "cmd-1");

  private final List<Long> backoffs = new ArrayList<>();
  private final RetryOnConflictCommandInterceptor interceptor =
      new RetryOnConflictCommandInterceptor(3, Duration.ofMillis(50), backoffs::add);

  private static ConcurrencyConflictException conflict() {
    return new ConcurrencyConflictException("lost the race", null);
  }

  /** Attempts that keep losing: each retry is a fresh proceed(), the backoff doubles. */
  @Test
  void aLostRaceIsRetriedWithDoublingBackoffUntilItSucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    Invocation<String> succeedsThird =
        () -> {
          if (attempts.incrementAndGet() < 3) {
            throw conflict();
          }
          return "done";
        };

    assertEquals("done", interceptor.intercept(COMMAND, CONTEXT, succeedsThird));
    assertEquals(3, attempts.get());
    assertEquals(List.of(50L, 100L), backoffs, "initial backoff, then doubled");
  }

  @Test
  void aFirstAttemptSuccessNeverSleeps() {
    assertEquals("done", interceptor.intercept(COMMAND, CONTEXT, () -> "done"));
    assertTrue(backoffs.isEmpty());
  }

  /** The cap is a promise: after maxAttempts the conflict stands and reaches the 409 path. */
  @Test
  void exhaustionRethrowsTheConflictAfterExactlyMaxAttempts() {
    AtomicInteger attempts = new AtomicInteger();
    ConcurrencyConflictException last = conflict();
    Invocation<String> alwaysLoses =
        () -> {
          attempts.incrementAndGet();
          throw last;
        };

    ConcurrencyConflictException thrown =
        assertThrows(
            ConcurrencyConflictException.class,
            () -> interceptor.intercept(COMMAND, CONTEXT, alwaysLoses));

    assertSame(last, thrown, "the final conflict, not a wrapper");
    assertEquals(3, attempts.get(), "maxAttempts is total attempts, not total retries");
    assertEquals(List.of(50L, 100L), backoffs, "no backoff after the final attempt");
  }

  /** Only a lost race is retriable; a business refusal would refuse again, identically. */
  @Test
  void anythingOtherThanAConflictPassesThroughUntouched() {
    AtomicInteger attempts = new AtomicInteger();
    Invocation<String> refuses =
        () -> {
          attempts.incrementAndGet();
          throw new DomainException("no");
        };

    assertThrows(DomainException.class, () -> interceptor.intercept(COMMAND, CONTEXT, refuses));
    assertEquals(1, attempts.get(), "a domain refusal is deterministic; retrying it is waste");
    assertTrue(backoffs.isEmpty());
  }

  /** A thread asked to stop does not start another attempt. */
  @Test
  void anInterruptDuringBackoffStopsTheRetriesAndLetsTheConflictStand() {
    AtomicInteger attempts = new AtomicInteger();
    RetryOnConflictCommandInterceptor interrupted =
        new RetryOnConflictCommandInterceptor(
            3, Duration.ofMillis(50), millis -> Thread.currentThread().interrupt());
    Invocation<String> alwaysLoses =
        () -> {
          attempts.incrementAndGet();
          throw conflict();
        };

    try {
      assertThrows(
          ConcurrencyConflictException.class,
          () -> interrupted.intercept(COMMAND, CONTEXT, alwaysLoses));
      assertEquals(1, attempts.get(), "no further attempt after the interrupt");
      assertTrue(Thread.currentThread().isInterrupted(), "the flag stays set for the caller");
    } finally {
      // Clear the flag so it cannot leak into the next test on this worker thread.
      Thread.interrupted();
    }
  }

  @Test
  void refusesASenselessConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryOnConflictCommandInterceptor(0, Duration.ofMillis(50)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryOnConflictCommandInterceptor(3, Duration.ofMillis(-1)));
  }

  /**
   * Lower order runs further out, and exceptions travel outward: for the retry loop to catch the
   * translated conflict, translation must sit inside retry, and both outside the transaction. The
   * previous version of this test asserted the opposite relation between retry and translation —
   * pinning the exact ordering bug that made the opt-in retry silently inert. The chain-level proof
   * lives in {@link RetryOnConflictPipelineTest}; this pins the constants it relies on.
   */
  @Test
  void sitsOutsideTranslationWhichSitsOutsideTheTransaction() {
    assertTrue(interceptor.order() < ConcurrencyTranslationCommandInterceptor.ORDER);
    assertTrue(interceptor.order() < ValidationCommandInterceptor.ORDER);
    assertTrue(
        ConcurrencyTranslationCommandInterceptor.ORDER < TransactionCommandInterceptor.ORDER);
  }
}
