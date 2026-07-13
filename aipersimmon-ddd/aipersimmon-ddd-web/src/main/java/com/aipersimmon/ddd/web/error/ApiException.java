package com.aipersimmon.ddd.web.error;

import com.aipersimmon.ddd.core.error.ErrorCode;
import java.util.List;

/**
 * A runtime exception carrying a domain {@link ErrorCode} and optional field-level
 * {@link FieldError}s, so application code can raise a catalogued error directly and let
 * the starter's advice resolve it — through the {@link ProblemRegistry} — to a
 * {@link ProblemDescriptor} and render an {@link ApiError}. This is the first-class path;
 * the advice also maps the {@code -core}/{@code -application} exception base types for
 * code that does not throw this. Both paths resolve the same way: code → descriptor.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient List<FieldError> errors;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of());
    }

    public ApiException(ErrorCode errorCode, String detail, List<FieldError> errors) {
        super(detail);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.errorCode = errorCode;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.errorCode = errorCode;
        this.errors = List.of();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Field-level problems, never null. */
    public List<FieldError> errors() {
        return errors == null ? List.of() : errors;
    }
}
