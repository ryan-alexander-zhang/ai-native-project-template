package com.aipersimmon.ddd.core.error;

/**
 * A stable, machine-readable identifier for a specific kind of error, carried by a
 * {@link com.aipersimmon.ddd.core.exception.DomainException} from the moment it is
 * thrown so the same code can travel unchanged to the edge of the system.
 *
 * <p>Framework-free by design: it names <em>what</em> went wrong (a dotted,
 * context-prefixed {@link #code()} such as {@code "ordering.credit-exceeded"}) and,
 * optionally, a coarse {@link #category()} — but it takes no stance on transport or
 * HTTP status. An interface layer maps the code to a wire representation; the domain
 * never depends on that mapping.
 *
 * <p>Implement it as an enum per bounded context so the catalogue of a context's
 * error codes lives in one place.
 */
public interface ErrorCode {

    /**
     * The stable, machine-readable code, e.g. {@code "ordering.credit-exceeded"}.
     * Prefix with the bounded context to avoid collisions. Once published it is part
     * of the outward contract and should change only under versioning.
     */
    String code();

    /**
     * A coarse semantic category, used as a fallback when no richer mapping is
     * registered for this code. Defaults to {@link ErrorCategory#DOMAIN_RULE}.
     */
    default ErrorCategory category() {
        return ErrorCategory.DOMAIN_RULE;
    }
}
