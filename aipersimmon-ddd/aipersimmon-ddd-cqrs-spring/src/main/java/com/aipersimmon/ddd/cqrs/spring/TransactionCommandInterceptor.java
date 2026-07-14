package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.cqrs.UnitOfWork;

/**
 * Runs the handler inside one {@link UnitOfWork}, so the state changes and the
 * domain events published during the command commit or roll back together.
 *
 * <p>This interceptor owns only the transaction boundary. Draining an aggregate's
 * recorded events is done where the aggregate is saved: the repository (or handler)
 * calls {@link DomainEvents#publishAndClear} after persisting the root, within this
 * transaction — so no thread-scoped collector is needed to tell the interceptor
 * which aggregates changed. Ordered innermost of the built-in chain so the
 * transaction wraps the handler but sits inside logging and validation.
 */
public class TransactionCommandInterceptor implements CommandInterceptor {

    /** Ordered innermost of the built-in interceptors. */
    public static final int ORDER = 200;

    private final UnitOfWork unitOfWork;

    public TransactionCommandInterceptor(UnitOfWork unitOfWork) {
        this.unitOfWork = unitOfWork;
    }

    @Override
    public <R> R intercept(Command<R> command, Invocation<R> invocation) {
        return unitOfWork.execute(invocation::proceed);
    }

    @Override
    public int order() {
        return ORDER;
    }
}
