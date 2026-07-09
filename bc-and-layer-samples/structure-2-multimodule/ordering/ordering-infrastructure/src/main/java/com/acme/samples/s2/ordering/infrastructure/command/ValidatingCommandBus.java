package com.acme.samples.s2.ordering.infrastructure.command;

import com.acme.samples.s2.shared.Command;
import com.acme.samples.s2.shared.CommandBus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import java.util.Set;

/**
 * Validation decorator (analysis-00005 §5.1): runs Bean Validation on the command
 * before it reaches the Transaction decorator, so a malformed command never opens a
 * transaction. Rejects with {@link ConstraintViolationException} on any violation.
 */
public class ValidatingCommandBus implements CommandBus {

    private final CommandBus delegate;
    private final Validator validator;

    public ValidatingCommandBus(CommandBus delegate, Validator validator) {
        this.delegate = delegate;
        this.validator = validator;
    }

    @Override
    public <R> R dispatch(Command<R> command) {
        Set<ConstraintViolation<Object>> violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return delegate.dispatch(command);
    }
}
