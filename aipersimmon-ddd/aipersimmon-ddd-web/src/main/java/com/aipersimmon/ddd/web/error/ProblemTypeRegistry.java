package com.aipersimmon.ddd.web.error;

import com.aipersimmon.ddd.core.error.ErrorCode;
import java.util.Optional;

/**
 * Looks up the {@link ProblemType} registered for a machine-readable
 * {@link ErrorCode#code()}. It is what closes the gap between an exception thrown deep
 * in the domain (carrying only a bare {@link ErrorCode}) and the full wire
 * representation an interface layer needs: given the code, the starter recovers the
 * type URI, HTTP status, and title key.
 *
 * <p>A consumer populates the registry from its per-context {@code ProblemType} enums.
 * A miss is normal — the starter then falls back to the code's
 * {@link ErrorCode#category()} or the exception type's default.
 */
public interface ProblemTypeRegistry {

    /** The problem type registered for {@code code}, or empty if none is registered. */
    Optional<ProblemType> byCode(String code);
}
