package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.web.error.ApiError;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Maps exceptions to RFC 9457 {@link ProblemDetail} responses. An
 * {@link ApiException} is the first-class path — its {@link com.aipersimmon.ddd.web.error.ProblemType}
 * supplies the type, status, code and title. Domain-rule violations, validation
 * failures and not-found are mapped to sensible statuses; anything else becomes a
 * 500 that does not leak the exception message. The (optional) application-layer
 * exception is handled by {@link ApplicationExceptionAdvice} when present.
 */
@RestControllerAdvice
public class AipersimmonDddWebExceptionHandler {

    private final ProblemTitleResolver titleResolver;

    public AipersimmonDddWebExceptionHandler(ProblemTitleResolver titleResolver) {
        this.titleResolver = titleResolver;
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        ApiError error = ApiError.from(ex.problemType(), titleResolver.resolve(ex.problemType()),
                ex.getMessage(), null, currentTraceId(), ex.errors());
        return ProblemDetailMapper.toProblemDetail(error);
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), List.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ProblemDetail handleValidation(BindException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(
                        fe.getField(),
                        fe.getCode() == null ? "invalid" : fe.getCode(),
                        fe.getDefaultMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Deliberately does not echo the message: avoid leaking internals on 500.
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, null, List.of());
    }

    private ProblemDetail problem(HttpStatus status, String detail, List<FieldError> errors) {
        ApiError error = new ApiError("about:blank", status.getReasonPhrase(), status.value(),
                detail, null, null, currentTraceId(), errors);
        return ProblemDetailMapper.toProblemDetail(error);
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
    }
}
