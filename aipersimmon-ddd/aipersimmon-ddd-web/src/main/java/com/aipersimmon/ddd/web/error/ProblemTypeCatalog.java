package com.aipersimmon.ddd.web.error;

import java.util.Collection;

/**
 * A bounded context's set of {@link ProblemType}s, so a starter can index them by
 * {@link ProblemType#code()} into a {@link ProblemTypeRegistry}. A context typically
 * exposes one catalog backed by its {@code ProblemType} enum, e.g.
 * {@code () -> List.of(OrderingProblemType.values())}.
 */
@FunctionalInterface
public interface ProblemTypeCatalog {

    /** The problem types this context defines. */
    Collection<ProblemType> problemTypes();
}
