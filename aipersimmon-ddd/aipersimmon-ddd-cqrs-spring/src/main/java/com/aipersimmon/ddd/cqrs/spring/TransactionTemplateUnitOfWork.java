package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UnitOfWork} backed by a Spring {@link TransactionTemplate}: it runs the
 * work in a transaction managed by the application's transaction manager,
 * committing on normal return and rolling back if the work throws.
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
