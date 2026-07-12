package com.aipersimmon.ddd.web.error;

import java.util.List;

/**
 * A runtime exception carrying a {@link ProblemType} and optional field-level
 * {@link FieldError}s, so application code can raise a catalogued error directly
 * and let the starter's advice render it to an {@link ApiError}. This is the
 * first-class path; the advice also maps the {@code -core}/{@code -application}
 * exception base types for code that does not throw this.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemType problemType;
    private final transient List<FieldError> errors;

    public ApiException(ProblemType problemType, String detail) {
        this(problemType, detail, List.of());
    }

    public ApiException(ProblemType problemType, String detail, List<FieldError> errors) {
        super(detail);
        if (problemType == null) {
            throw new IllegalArgumentException("problemType must not be null");
        }
        this.problemType = problemType;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public ApiException(ProblemType problemType, String detail, Throwable cause) {
        super(detail, cause);
        if (problemType == null) {
            throw new IllegalArgumentException("problemType must not be null");
        }
        this.problemType = problemType;
        this.errors = List.of();
    }

    public ProblemType problemType() {
        return problemType;
    }

    /** Field-level problems, never null. */
    public List<FieldError> errors() {
        return errors == null ? List.of() : errors;
    }
}
