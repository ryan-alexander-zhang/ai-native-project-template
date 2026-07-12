package com.aipersimmon.ddd.web.error;

/**
 * One field-level validation problem, carried in {@link ApiError#errors()}. It
 * mirrors the {@code details}/{@code errors} element common to PayPal, GitHub and
 * JSON:API: which field failed, a machine code for the failure, and a
 * human-readable message.
 *
 * @param field   the offending field (a name or JSON pointer such as {@code "/lines/0/qty"})
 * @param code    machine-readable reason, e.g. {@code "missing"} or {@code "out-of-range"}
 * @param message human-readable explanation
 */
public record FieldError(String field, String code, String message) {

    public FieldError {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
