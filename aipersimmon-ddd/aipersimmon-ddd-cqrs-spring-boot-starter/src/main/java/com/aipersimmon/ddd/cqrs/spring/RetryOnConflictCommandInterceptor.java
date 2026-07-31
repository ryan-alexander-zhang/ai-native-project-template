package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import java.time.Duration;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a command that lost an optimistic-lock race, with exponential backoff and a hard attempt
 * cap. Opt-in via {@code aipersimmon.ddd.cqrs.retry-on-conflict.enabled}.
 *
 * <p>A pure lost race — two writers loaded the same aggregate version and the second write matched
 * zero rows — is the one failure whose retry is <em>expected to succeed</em>: rerunning the command
 * reloads the aggregate at its new version and re-decides against current state. Without this
 * interceptor every such race surfaces as a 409 to a client who did nothing wrong and whose only
 * remedy is to resubmit the identical request — the retry loop still exists, it just runs over HTTP
 * with a human in it.
 *
 * <p>Ordered at {@code 75}: inside {@link ConcurrencyTranslationCommandInterceptor} ({@code 50}),
 * so it sees the translated {@link ConcurrencyConflictException} whatever the persistence
 * technology; outside validation ({@code 100}), prechecks ({@code 150}) and above all the
 * transaction ({@code 200}), so each attempt is a complete fresh dispatch — new transaction, fresh
 * aggregate load, prechecks re-run.
 *
 * <p><strong>Why opt-in.</strong> Rerunning the transaction is safe by construction — everything
 * inside it rolled back — but a handler that performed non-transactional side effects (an HTTP call
 * to a third party, a file write) would repeat them. That is a property of the application's
 * handlers, which the framework cannot verify; the deployment asserts it by enabling the property.
 * Exhaustion rethrows the conflict, so the existing 409 path is the fallback, not a casualty.
 */
public class RetryOnConflictCommandInterceptor implements CommandInterceptor {

  /** Inside concurrency translation ({@code 50}), outside validation ({@code 100}). */
  public static final int ORDER = 75;

  private static final Logger log =
      LoggerFactory.getLogger(RetryOnConflictCommandInterceptor.class);

  private final int maxAttempts;
  private final Duration initialBackoff;
  private final LongConsumer sleeper;

  public RetryOnConflictCommandInterceptor(int maxAttempts, Duration initialBackoff) {
    this(maxAttempts, initialBackoff, RetryOnConflictCommandInterceptor::sleep);
  }

  /** Test seam: the sleeper receives each backoff in milliseconds instead of really waiting. */
  RetryOnConflictCommandInterceptor(
      int maxAttempts, Duration initialBackoff, LongConsumer sleeper) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
    }
    if (initialBackoff.isNegative()) {
      throw new IllegalArgumentException("initialBackoff must be >= 0, was " + initialBackoff);
    }
    this.maxAttempts = maxAttempts;
    this.initialBackoff = initialBackoff;
    this.sleeper = sleeper;
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    for (int attempt = 1; ; attempt++) {
      try {
        return invocation.proceed();
      } catch (ConcurrencyConflictException conflict) {
        if (attempt >= maxAttempts) {
          throw conflict;
        }
        // Doubling from the initial backoff: enough jitterless separation for the row's lock to
        // clear in the common two-writer case, without inventing a scheduling policy here.
        long backoffMillis = initialBackoff.toMillis() << (attempt - 1);
        log.debug(
            "retrying {} after a concurrency conflict (attempt {}/{}, backing off {}ms)",
            command.getClass().getSimpleName(),
            attempt,
            maxAttempts,
            backoffMillis);
        sleeper.accept(backoffMillis);
        if (Thread.currentThread().isInterrupted()) {
          // The backoff was cut short by an interrupt (shutdown, cancellation): a thread that has
          // been asked to stop does not start another attempt. The conflict stands.
          throw conflict;
        }
      }
    }
  }

  @Override
  public int order() {
    return ORDER;
  }

  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      // Restore the flag; the attempt loop checks it after every backoff and stops retrying.
      Thread.currentThread().interrupt();
    }
  }
}
