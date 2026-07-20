package com.aipersimmon.ddd.web.error;

import java.util.List;

/**
 * A framework-free value model of an error response, shaped after RFC 9457
 * "Problem Details for HTTP APIs". It carries the five standard members
 * ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance})
 * plus the extension members this library standardises on: a machine-readable
 * {@link #code()}, a {@link #requestId()} (the per-request edge correlation id) and a
 * {@link #traceId()} (the real distributed-trace id, present only when tracing is wired),
 * and a list of {@link FieldError}s for field-level validation failures.
 *
 * <p>Kept independent of Spring's {@code ProblemDetail}; a starter translates this
 * to the wire form and sets the {@code application/problem+json} media type. The
 * instance is immutable and validated on construction.
 *
 * @param type      URI reference identifying the problem type (an identifier, not a
 *                  required link); defaults to {@code "about:blank"} when null
 * @param title     short, human-readable summary, stable across occurrences
 * @param status    HTTP status code (100–599)
 * @param detail    occurrence-specific human-readable explanation (nullable)
 * @param instance  URI reference for this specific occurrence (nullable)
 * @param code      machine-readable domain error code (nullable)
 * @param requestId per-request correlation id a client can quote (nullable)
 * @param traceId   the real distributed-trace id for looking the request up in the
 *                  tracing backend; present only when OpenTelemetry is wired (nullable)
 * @param errors    field-level validation problems; never null (empty when none)
 */
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String requestId,
        String traceId,
        List<FieldError> errors) {

    public ApiError {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status (100-599), was " + status);
        }
        type = (type == null || type.isBlank()) ? "about:blank" : type;
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * Builds an error from a resolved {@link ProblemDescriptor}, the thrown code, and a
     * resolved title. The descriptor supplies {@code type}/{@code status}; the {@code code}
     * is the thrown {@link com.aipersimmon.ddd.core.error.ErrorCode}'s value, kept separate
     * from the descriptor so several codes may share one descriptor. The title is passed
     * in already resolved because message-source lookup lives in the starter, not in this
     * framework-free tier.
     *
     * @param descriptor the resolved transport definition (supplies type, status)
     * @param code       the machine-readable domain code carried by the exception (nullable)
     * @param title      the resolved, human-readable title
     * @param detail     occurrence-specific explanation (nullable)
     * @param instance   URI of this occurrence (nullable)
     * @param requestId  per-request edge correlation id (nullable)
     * @param traceId    the real distributed-trace id (nullable)
     * @param errors     field-level problems (nullable → empty)
     */
    public static ApiError from(ProblemDescriptor descriptor, String code, String title, String detail,
                                String instance, String requestId, String traceId, List<FieldError> errors) {
        return new ApiError(descriptor.typeUri(), title, descriptor.status(),
                detail, instance, code, requestId, traceId, errors);
    }
}
