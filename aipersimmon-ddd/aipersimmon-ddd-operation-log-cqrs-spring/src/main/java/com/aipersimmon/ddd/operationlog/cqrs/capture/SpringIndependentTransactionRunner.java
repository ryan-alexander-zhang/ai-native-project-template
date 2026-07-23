package com.aipersimmon.ddd.operationlog.cqrs.capture;

import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the failure record in its own transaction via a {@code TransactionTemplate} with {@code
 * PROPAGATION_REQUIRES_NEW}. The root failed interceptor invokes it only when no outer transaction
 * is active, so this is a plain new transaction rather than a suspend-and-resume.
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
