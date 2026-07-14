package com.aipersimmon.ddd.cqrs;

/**
 * Around-advice applied by the {@link CommandBus} to every command before it
 * reaches its handler. Interceptors form an ordered chain (lower {@link #order()}
 * runs further out, wrapping higher ones), so a typical stack is
 * logging → validation → transaction, with the handler innermost. An interceptor
 * calls {@link Invocation#proceed()} to continue the chain, and may act before
 * and after that call or short-circuit by not calling it.
 */
public interface CommandInterceptor {

    /**
     * @param command    the command being dispatched
     * @param context    the command's dispatch context (message id, correlation,
     *                   causation, trace) — for correlating logs/traces; most
     *                   interceptors just pass it through
     * @param invocation the continuation of the chain (the next interceptor, or
     *                   finally the handler)
     * @param <R>        the command's result type
     * @return the result of proceeding, possibly adapted by this interceptor
     */
    <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation);

    /**
     * Position in the chain: lower runs further out (earlier before, later after).
     * Defaults to {@code 0}.
     */
    default int order() {
        return 0;
    }

    /** The continuation of the interceptor chain. */
    @FunctionalInterface
    interface Invocation<R> {
        R proceed();
    }
}
