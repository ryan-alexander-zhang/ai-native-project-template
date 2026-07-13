package com.aipersimmon.ddd.web.error;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * A single entry in a bounded context's error catalogue. Implement it as an enum
 * so the set of errors is closed, exhaustive, and machine-referable; prefix the
 * {@link #code()} with the context name to keep codes unique across contexts.
 *
 * <p>A {@code ProblemType} <em>is</em> an {@link ErrorCode} enriched with a transport
 * mapping. Because the code an exception carries from the domain is an
 * {@link ErrorCode}, a {@code ProblemType} registered for that code lets the starter
 * recover the full wire representation at the edge — without the domain ever
 * depending on this layer.
 *
 * <p>A starter maps an exception carrying (or resolving to) a {@code ProblemType} to
 * an {@link ApiError}: {@link #typeUri()} becomes {@code type}, {@link #status()} the
 * HTTP status, {@link #code()} the {@code code} extension member, and
 * {@link #titleKey()} is resolved through the application's message source to the
 * human-readable {@code title}.
 */
public interface ProblemType extends ErrorCode {

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
