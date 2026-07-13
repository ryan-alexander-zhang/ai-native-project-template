package com.aipersimmon.ddd.web.error;

import com.aipersimmon.ddd.core.error.ErrorCode;
import java.util.Map;

/**
 * A bounded context's <em>overrides</em>: the few {@link ErrorCode}s that warrant their
 * own {@link ProblemDescriptor} instead of riding their category's
 * {@link DefaultProblemFamilies family default}. A starter merges every catalog over the
 * defaults to build the {@link ProblemRegistry}.
 *
 * <p>Override only errors with a genuinely distinct client contract — a different
 * recovery action, a different extension schema, or their own public documentation
 * (e.g. {@code /problems/insufficient-credit}). Everything else should stay on the family
 * type and be distinguished by its {@code code}, so the outward problem-type catalogue
 * does not grow one-for-one with the domain's error codes.
 */
@FunctionalInterface
public interface ProblemCatalog {

    /** The per-code descriptor overrides this context defines (may be empty). */
    Map<ErrorCode, ProblemDescriptor> overrides();
}
