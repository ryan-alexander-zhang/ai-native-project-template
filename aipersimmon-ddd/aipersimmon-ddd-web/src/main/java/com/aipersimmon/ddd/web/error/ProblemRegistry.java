package com.aipersimmon.ddd.web.error;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * Resolves the {@link ProblemDescriptor} for a domain {@link ErrorCode}. It closes the
 * gap between an exception thrown deep in the domain (carrying only a bare
 * {@link ErrorCode}) and the wire representation an interface layer needs.
 *
 * <p>Resolution is two-tier and <strong>total</strong> for any code: a per-code
 * {@link ProblemCatalog} override wins; otherwise the code's
 * {@link ErrorCode#category()} {@link DefaultProblemFamilies family default} applies. A
 * coded error therefore never renders as {@code about:blank} (except the deliberately
 * about-blank {@code UNEXPECTED} family) — the client can always branch on a meaningful
 * {@code type}, then on the {@code code} extension.
 */
@FunctionalInterface
public interface ProblemRegistry {

    /** The descriptor for {@code code}: its override if any, else its category's family. */
    ProblemDescriptor resolve(ErrorCode code);
}
