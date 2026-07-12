package com.aipersimmon.ddd.web.error;

/**
 * A single entry in a bounded context's error catalogue. Implement it as an enum
 * so the set of errors is closed, exhaustive, and machine-referable; prefix the
 * {@link #code()} with the context name to keep codes unique across contexts.
 *
 * <p>A starter maps an exception carrying a {@code ProblemType} to an
 * {@link ApiError}: {@link #typeUri()} becomes {@code type}, {@link #status()} the
 * HTTP status, {@link #code()} the {@code code} extension member, and
 * {@link #titleKey()} is resolved through the application's message source to the
 * human-readable {@code title}.
 */
public interface ProblemType {

    /**
     * Stable, machine-readable error code, e.g. {@code "ordering.credit-exceeded"}.
     * Prefix with the bounded context to avoid collisions across contexts.
     */
    String code();

    /**
     * Relative URI reference that identifies the problem type, e.g.
     * {@code "/problems/credit-exceeded"}. It is an identifier, not a link, and is
     * not required to be resolvable.
     */
    String typeUri();

    /** Default HTTP status for this problem type (100–599). */
    int status();

    /**
     * Message-source key for the {@code title} — a short, human-readable summary
     * that is stable across occurrences (the occurrence-specific text is the
     * {@code detail}, supplied per throw).
     */
    String titleKey();
}
