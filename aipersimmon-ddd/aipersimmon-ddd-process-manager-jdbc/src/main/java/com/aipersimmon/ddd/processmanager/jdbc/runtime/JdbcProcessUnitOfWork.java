package com.aipersimmon.ddd.processmanager.jdbc.runtime;

import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs an atomic advance in one {@code PROPAGATION_REQUIRED} transaction: it joins an
 * outer transaction when present (so an Inbox check and a command-handler transaction
 * compose with the advance) and opens a local one
 * otherwise. Effect delivery deliberately happens outside this transaction.
 */
public final class JdbcProcessUnitOfWork {

    private final TransactionTemplate transactionTemplate;

    public JdbcProcessUnitOfWork(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public <R> R execute(Supplier<R> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
