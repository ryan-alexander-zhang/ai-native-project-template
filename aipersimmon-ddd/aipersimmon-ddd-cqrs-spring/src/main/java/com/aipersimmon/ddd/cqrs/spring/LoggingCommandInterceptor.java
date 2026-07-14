package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logs the dispatch of each command and whether it succeeded or failed, and puts the
 * command's correlation id on the MDC for the duration of the handler so every log
 * line emitted while handling shares it. Ordered outermost ({@code order = 0}) so it
 * observes the whole chain, including the transaction boundary applied by inner
 * interceptors.
 */
public class LoggingCommandInterceptor implements CommandInterceptor {

    /** Ordered outermost. */
    public static final int ORDER = 0;

    /** MDC key holding the current flow's correlation id. */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(LoggingCommandInterceptor.class);

    @Override
    public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
        String name = command.getClass().getSimpleName();
        String previous = MDC.get(CORRELATION_ID_MDC_KEY);
        MDC.put(CORRELATION_ID_MDC_KEY, context.correlationId());
        try {
            log.debug("Handling command {} [correlationId={}, causationId={}]",
                    name, context.correlationId(), context.causationId());
            R result = invocation.proceed();
            log.debug("Handled command {}", name);
            return result;
        } catch (RuntimeException e) {
            log.debug("Command {} failed: {}", name, e.toString());
            throw e;
        } finally {
            if (previous == null) {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            } else {
                MDC.put(CORRELATION_ID_MDC_KEY, previous);
            }
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
