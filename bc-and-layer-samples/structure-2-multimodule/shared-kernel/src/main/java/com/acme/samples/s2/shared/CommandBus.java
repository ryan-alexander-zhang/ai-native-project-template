package com.acme.samples.s2.shared;

/**
 * Dispatches a {@link Command} to its {@link CommandHandler}, decoupling the caller
 * (controller / message adapter) from the handler (analysis-00005 §5.1). The
 * infrastructure implementation stacks cross-cutting decorators —
 * Logging &rarr; Validation &rarr; Transaction (UnitOfWork) — around the dispatch.
 * Framework-free port.
 */
public interface CommandBus {
    <R> R dispatch(Command<R> command);
}
