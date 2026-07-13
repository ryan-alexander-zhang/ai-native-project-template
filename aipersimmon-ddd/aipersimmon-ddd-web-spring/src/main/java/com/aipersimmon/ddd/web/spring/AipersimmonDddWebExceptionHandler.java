package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Maps exceptions to RFC 9457 {@link ProblemDetail} responses.
 *
 * <ul>
 *   <li>An {@link ApiException} is the first-class path — its
 *       {@link com.aipersimmon.ddd.web.error.ProblemType} supplies type/status/code/title.</li>
 *   <li>A {@link DomainException} that carries an error code resolves through the
 *       registry; otherwise a business-rule violation defaults to <strong>422</strong>
 *       (well-formed but semantically unprocessable).</li>
 *   <li>An {@link IllegalStateTransitionException} is a conflict with the current
 *       state, so it defaults to <strong>409</strong>.</li>
 *   <li>Bean Validation failures map to 400 with field-level errors — both the
 *       request-body path ({@link MethodArgumentNotValidException}) and the
 *       method-parameter path ({@code @Validated} on {@code @RequestParam}/
 *       {@code @PathVariable}, raised as {@link HandlerMethodValidationException} since
 *       Spring 6.1); not-found to 404; anything else to a 500 that does not leak the
 *       exception message.</li>
 * </ul>
 *
 * <p>The application-layer exceptions are handled by {@link ApplicationExceptionAdvice},
 * and bean-validation {@code ConstraintViolationException} by
 * {@link ConstraintViolationAdvice}, each wired only when its types are on the classpath.
 */
@RestControllerAdvice
public class AipersimmonDddWebExceptionHandler {

    private final ProblemDetailFactory factory;

    public AipersimmonDddWebExceptionHandler(ProblemDetailFactory factory) {
        this.factory = factory;
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        return factory.fromApiException(ex);
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail handleIllegalStateTransition(IllegalStateTransitionException ex) {
        return factory.fromCoded(ex.errorCode(), ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException ex) {
        return factory.fromCoded(ex.errorCode(), ex.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ProblemDetail handleValidation(BindException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(
                        fe.getField(),
                        fe.getCode() == null ? "invalid" : fe.getCode(),
                        fe.getDefaultMessage()))
                .toList();
        return factory.simple(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException ex) {
        List<FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String name = result.getMethodParameter().getParameterName();
                    String field = name == null ? "?" : name;
                    return result.getResolvableErrors().stream()
                            .map(err -> new FieldError(
                                    field,
                                    err.getCodes() == null || err.getCodes().length == 0
                                            ? "invalid" : err.getCodes()[0],
                                    err.getDefaultMessage()));
                })
                .toList();
        return factory.simple(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return factory.simple(HttpStatus.NOT_FOUND, ex.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Deliberately does not echo the message: avoid leaking internals on 500.
        return factory.simple(HttpStatus.INTERNAL_SERVER_ERROR, null, List.of());
    }
}
