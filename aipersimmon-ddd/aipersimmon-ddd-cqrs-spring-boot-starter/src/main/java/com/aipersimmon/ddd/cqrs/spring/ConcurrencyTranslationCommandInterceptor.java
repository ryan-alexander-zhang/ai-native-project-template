package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Translates a Spring {@link OptimisticLockingFailureException} — raised when a version-checked
 * write loses a concurrent race — into an application-level {@link ConcurrencyConflictException},
 * so the interface layer sees a stable domain vocabulary (mapped to 409) rather than a framework
 * persistence exception. Ordered outside the transaction interceptor so it observes the failure
 * surfaced at commit, but <em>inside</em> {@link RetryOnConflictCommandInterceptor} ({@code 75}):
 * exceptions travel outward, so for the retry loop to catch the translated exception the
 * translation must sit between the transaction and the retry — not outside both. It once sat at
 * {@code 50}, which read naturally next to logging but meant a repository's {@link
 * OptimisticLockingFailureException} passed the retry loop untranslated and uncaught; the opt-in
 * retry was silently inert on the one path it existed for.
 */
public class ConcurrencyTranslationCommandInterceptor implements CommandInterceptor {

  /**
   * Outside the transaction boundary ({@code 200}), inside prechecks ({@code 150}) and therefore
   * inside retry ({@code 75}), which must catch what this interceptor throws.
   */
  public static final int ORDER = 175;

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    try {
      return invocation.proceed();
    } catch (OptimisticLockingFailureException e) {
      throw new ConcurrencyConflictException(
          "concurrent modification while handling " + command.getClass().getSimpleName(), e);
    }
  }

  @Override
  public int order() {
    return ORDER;
  }
}
