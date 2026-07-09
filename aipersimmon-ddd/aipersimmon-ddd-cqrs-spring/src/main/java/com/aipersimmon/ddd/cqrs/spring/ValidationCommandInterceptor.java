package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;

/**
 * Validates each command against its Bean Validation constraints before the
 * handler runs, throwing {@link ConstraintViolationException} if any fail — so a
 * malformed command is rejected before it starts a transaction. Ordered between
 * logging and transaction. Wired only when a Bean Validation provider is on the
 * classpath.
 */
public class ValidationCommandInterceptor implements CommandInterceptor {

    /** Ordered between logging (outer) and transaction (inner). */
    public static final int ORDER = 100;

    private final Validator validator;

    public ValidationCommandInterceptor(Validator validator) {
        this.validator = validator;
    }

    @Override
    public <R> R intercept(Command<R> command, Invocation<R> invocation) {
        Set<ConstraintViolation<Command<R>>> violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return invocation.proceed();
    }

    @Override
    public int order() {
        return ORDER;
    }
}
