package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Translates Spring's persistence conflicts into a stable application vocabulary, so the interface
 * layer sees domain-shaped exceptions (both mapped to 409) rather than framework persistence types:
 * an {@link OptimisticLockingFailureException} — a version-checked write lost a concurrent race —
 * becomes {@link ConcurrencyConflictException}, and a {@link DuplicateKeyException} — a create hit
 * an identity or unique key that already exists — becomes {@link DuplicateEntityException}.
 *
 * <p>The two translations are deliberately <em>different</em> types: {@link
 * RetryOnConflictCommandInterceptor} (order {@code 75}, outside this one) catches only {@link
 * ConcurrencyConflictException}, because a lost optimistic race is transient — rerunning the
 * command re-reads fresh state and is expected to succeed — while a duplicate create is stable:
 * rerunning it deterministically conflicts again. Collapsing both into one type would put creates
 * into the retry loop; the type split is what keeps them out.
 *
 * <p>Ordered outside the transaction interceptor so it observes the failure surfaced at commit, but
 * <em>inside</em> {@link RetryOnConflictCommandInterceptor} ({@code 75}): exceptions travel
 * outward, so for the retry loop to catch the translated exception the translation must sit between
 * the transaction and the retry — not outside both. It once sat at {@code 50}, which read naturally
 * next to logging but meant a repository's {@link OptimisticLockingFailureException} passed the
 * retry loop untranslated and uncaught; the opt-in retry was silently inert on the one path it
 * existed for.
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
    } catch (DuplicateKeyException e) {
      // A stable conflict: deliberately NOT ConcurrencyConflictException, or the retry loop
      // outside this interceptor would replay a create that can only conflict again.
      throw new DuplicateEntityException(
          "duplicate key while handling " + command.getClass().getSimpleName(), e);
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
