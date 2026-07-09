package com.acme.samples.s2.ordering.infrastructure.command;

import com.acme.samples.s2.shared.Command;
import com.acme.samples.s2.shared.CommandBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outermost decorator: logs each command (analysis-00005 §5.1, "Logging" in the
 * Logging → Validation → Transaction chain). Same "cross-cutting = decorator"
 * approach as the DomainEvents chain (analysis-00001).
 */
public class LoggingCommandBus implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(LoggingCommandBus.class);

    private final CommandBus delegate;

    public LoggingCommandBus(CommandBus delegate) {
        this.delegate = delegate;
    }

    @Override
    public <R> R dispatch(Command<R> command) {
        String name = command.getClass().getSimpleName();
        log.debug("handling command {}", name);
        try {
            R result = delegate.dispatch(command);
            log.debug("handled command {}", name);
            return result;
        } catch (RuntimeException e) {
            log.warn("command {} failed: {}", name, e.toString());
            throw e;
        }
    }
}
