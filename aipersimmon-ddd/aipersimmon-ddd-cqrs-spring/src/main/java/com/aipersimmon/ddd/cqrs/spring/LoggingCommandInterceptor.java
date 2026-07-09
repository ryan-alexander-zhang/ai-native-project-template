package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the dispatch of each command and whether it succeeded or failed. Ordered
 * outermost ({@code order = 0}) so it observes the whole chain, including the
 * transaction boundary applied by inner interceptors.
 */
public class LoggingCommandInterceptor implements CommandInterceptor {

    /** Ordered outermost. */
    public static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(LoggingCommandInterceptor.class);

    @Override
    public <R> R intercept(Command<R> command, Invocation<R> invocation) {
        String name = command.getClass().getSimpleName();
        log.debug("Handling command {}", name);
        try {
            R result = invocation.proceed();
            log.debug("Handled command {}", name);
            return result;
        } catch (RuntimeException e) {
            log.debug("Command {} failed: {}", name, e.toString());
            throw e;
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
