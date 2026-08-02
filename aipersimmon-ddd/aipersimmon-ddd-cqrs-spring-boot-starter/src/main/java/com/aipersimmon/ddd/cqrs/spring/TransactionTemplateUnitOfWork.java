package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UnitOfWork} backed by a Spring {@link TransactionTemplate}: it runs the work in a
 * transaction managed by the application's transaction manager, committing on normal return and
 * rolling back if the work throws.
 *
 * <p><strong>Nested dispatch joins the outer transaction.</strong> The template's default
 * propagation is {@code REQUIRED}, so a handler that sends a follow-up command through the bus —
 * the composition the architecture rules endorse — runs the inner command <em>inside</em> the outer
 * transaction, not in one of its own. "One command = one transaction" is therefore a statement
 * about root dispatches. Two consequences worth knowing before relying on nesting: an inner failure
 * marks the shared transaction rollback-only, so the outer commit dies with {@code
 * UnexpectedRollbackException} even if the outer handler caught the inner exception; and with
 * retry-on-conflict enabled, an inner conflict poisons the shared transaction the same way — the
 * inner retry then "succeeds" inside a doomed transaction. Retry is only fully sound for root
 * dispatches. If an inner command must commit independently, supply a {@code UnitOfWork} built on a
 * {@code REQUIRES_NEW} template — a deliberate, visible choice, because it also means the inner
 * commit survives an outer rollback.
 */
public class TransactionTemplateUnitOfWork implements UnitOfWork {

  private final TransactionTemplate transactionTemplate;

  public TransactionTemplateUnitOfWork(TransactionTemplate transactionTemplate) {
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public <R> R execute(Supplier<R> work) {
    return transactionTemplate.execute(status -> work.get());
  }
}
