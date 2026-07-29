package com.aipersimmon.ddd.operationlog.cqrs.capture;

import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the failure record in its own transaction via a {@code TransactionTemplate} with {@code
 * PROPAGATION_REQUIRES_NEW}.
 *
 * <p>Genuinely suspend-and-resume, not merely a new transaction: the failure interceptor calls this
 * whatever is active, because a failure record that only survives when nothing else is running is
 * not a record. That means one extra connection while the suspended transaction still holds its
 * own, so a deployment whose pool is sized to exactly one connection per request will need a little
 * headroom. The alternative — skipping the record when a transaction is open — is what left audit
 * gaps for every dispatch made from inside a caller's transaction.
 */
public final class SpringIndependentTransactionRunner implements IndependentTransactionRunner {

  private final TransactionTemplate template;

  public SpringIndependentTransactionRunner(PlatformTransactionManager transactionManager) {
    this.template =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void run(Runnable action) {
    template.executeWithoutResult(status -> action.run());
  }
}
