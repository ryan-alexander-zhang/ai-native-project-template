package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.error.FieldError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a Bean Validation {@link ConstraintViolationException} — raised by the command
 * bus's validation interceptor, or by method-level {@code @Validated} — to a 400 with
 * field-level {@link FieldError}s, using the same wire shape as the MVC
 * {@code MethodArgumentNotValidException} path. Without this the exception would fall
 * through to the 500 handler. Registered only when the Bean Validation API is on the
 * classpath.
 */
@RestControllerAdvice
public class ConstraintViolationAdvice {

    private final ProblemDetailFactory factory;

    public ConstraintViolationAdvice(ProblemDetailFactory factory) {
        this.factory = factory;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(ConstraintViolationAdvice::toFieldError)
                .toList();
        return factory.simple(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    }

    private static FieldError toFieldError(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        String code = violation.getConstraintDescriptor() == null
                ? "invalid"
                : violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return new FieldError(field.isBlank() ? "?" : field, code, violation.getMessage());
    }
}
