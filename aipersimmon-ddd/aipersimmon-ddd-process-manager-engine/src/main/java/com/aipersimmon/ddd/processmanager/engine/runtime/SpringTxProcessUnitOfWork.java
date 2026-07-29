package com.aipersimmon.ddd.processmanager.engine.runtime;

import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spring-transaction adapter of {@link ProcessUnitOfWork}, shared by every storage backend
 * (both back the store on a {@code DataSource} with a {@code PlatformTransactionManager}). It wraps
 * a {@link TransactionTemplate} with {@code PROPAGATION_REQUIRED}, so a unit of work joins an
 * existing transaction (an Inbox / command-handler boundary) when one is active, else opens a local
 * one.
 */
public final class SpringTxProcessUnitOfWork implements ProcessUnitOfWork {

  private final TransactionTemplate transactionTemplate;

  public SpringTxProcessUnitOfWork(PlatformTransactionManager transactionManager) {
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  @Override
  public <R> R execute(Supplier<R> work) {
    return transactionTemplate.execute(status -> work.get());
  }

  @Override
  public boolean inExistingTransaction() {
    // isActualTransactionActive, not isSynchronizationActive: only a real, resource-bound
    // transaction makes the next execute() a participating one whose rollback dooms the caller.
    return TransactionSynchronizationManager.isActualTransactionActive();
  }
}
