package com.acme.samples.s2.shared;

/**
 * Handles exactly one {@link Command} type — a thin application service that loads
 * an aggregate, invokes its behaviour, persists, and registers it for domain-event
 * drain (analysis-00005 §5.1). One command, one handler. Framework-free.
 *
 * @param <C> the command type
 * @param <R> the command's result type
 */
public interface CommandHandler<C extends Command<R>, R> {
    R handle(C command);
}
