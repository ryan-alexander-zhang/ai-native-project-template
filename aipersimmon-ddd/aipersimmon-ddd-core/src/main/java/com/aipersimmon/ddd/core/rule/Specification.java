package com.aipersimmon.ddd.core.rule;

/**
 * A composable business rule that <em>answers</em> rather than <em>throws</em>: given a candidate,
 * is it satisfied? This is the decision-style sibling of {@link Invariant}, which is
 * assertion-style. The two are not alternatives — a model needs both:
 *
 * <ul>
 *   <li>{@link Invariant} answers "this must hold": an aggregate checks it and the write is refused
 *       when it is broken. It carries an {@link com.aipersimmon.ddd.core.error.ErrorCode} because a
 *       violation travels to the edge.
 *   <li>{@code Specification} answers "does this match?": a caller branches on the result. There is
 *       no error code, because not matching is an ordinary outcome, not a fault — a query filter,
 *       an eligibility test, a selection rule.
 * </ul>
 *
 * <p>Reaching for an {@code Invariant} where a {@code Specification} belongs is what produces
 * exceptions used as control flow; reaching for a {@code Specification} where an {@code Invariant}
 * belongs is what lets an illegal state be written.
 *
 * <p>Composition is the reason this is an interface rather than a plain {@code Predicate}: rules
 * are named domain concepts that combine and stay readable at the call site.
 *
 * <pre>{@code
 * Specification<Customer> eligible = inGoodStanding.and(hasVerifiedEmail.or(isEmployee));
 * if (eligible.isSatisfiedBy(customer)) { ... }
 * }</pre>
 *
 * <p>Framework-free. Implementations must be side-effect free: a specification decides, it does not
 * act.
 *
 * @param <T> the candidate this rule judges
 */
public interface Specification<T> {

  /** Whether the candidate satisfies this rule. */
  boolean isSatisfiedBy(T candidate);

  /** Satisfied only when both this and {@code other} are. */
  default Specification<T> and(Specification<? super T> other) {
    return candidate -> isSatisfiedBy(candidate) && other.isSatisfiedBy(candidate);
  }

  /** Satisfied when either this or {@code other} is. */
  default Specification<T> or(Specification<? super T> other) {
    return candidate -> isSatisfiedBy(candidate) || other.isSatisfiedBy(candidate);
  }

  /** Satisfied exactly when this is not. */
  default Specification<T> not() {
    return candidate -> !isSatisfiedBy(candidate);
  }
}
