package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Translates a Spring {@link OptimisticLockingFailureException} — raised when a
 * version-checked write loses a concurrent race — into an application-level
 * {@link ConcurrencyConflictException}, so the interface layer sees a stable domain
 * vocabulary (mapped to 409) rather than a framework persistence exception. Ordered
 * outside the transaction interceptor so it observes the failure surfaced at commit,
 * but inside logging.
 */
public class ConcurrencyTranslationCommandInterceptor implements CommandInterceptor {

    /** Ordered outside the transaction boundary ({@code 200}), inside logging ({@code 0}). */
    public static final int ORDER = 50;

    @Override
    public <R> R intercept(Command<R> command, Invocation<R> invocation) {
        try {
            return invocation.proceed();
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrencyConflictException(
                    "concurrent modification while handling " + command.getClass().getSimpleName(), e);
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
