package com.aipersimmon.ddd.core.state;

import com.aipersimmon.ddd.core.error.ErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A minimal, dependency-free guard over the legal state transitions of an aggregate or entity.
 * Declare the allowed transitions once — typically in a {@code private static final} field — then
 * call {@link #check} inside intention-revealing methods such as {@code confirm()} or {@code
 * cancel()}:
 *
 * <pre>{@code
 * private static final Transitions<Status> RULES = Transitions.<Status>of()
 *         .allow(Status.PENDING, Status.CONFIRMED, Codes.NOT_PENDING)
 *         .allow(Status.PENDING, Status.CANCELLED);
 *
 * public void confirm() {
 *     RULES.check(status, Status.CONFIRMED);
 *     this.status = Status.CONFIRMED;
 * }
 * }</pre>
 *
 * <p>The three-argument {@code allow} also names the refusal: an illegal attempt to reach that
 * destination throws an {@link IllegalStateTransitionException} carrying the given {@link
 * ErrorCode}, so the refusal reaches the API edge as a stable identity a client can branch on
 * rather than a bare message. Naming it here keeps the whole lifecycle contract — what is legal and
 * what a refusal is called — in the one table, and spares the aggregate from writing a hand-rolled
 * guard next to {@code check} just to attach a code. The code belongs to the <em>destination</em>:
 * "not in a state to be confirmed" is about where the caller tried to go, not where the object
 * happened to be, so every edge into one destination must agree on it and disagreement fails at
 * declaration time.
 *
 * <p>This is not a base class and not a state-machine engine: a domain object uses it, it does not
 * extend it. The ubiquitous-language methods stay on the surface while the transition table lives
 * in one place.
 *
 * <p>Build the table completely at class-initialisation time (the {@code static final} idiom above)
 * and treat it as frozen from then on: it is not synchronized, so declaring transitions after the
 * table is visible to other threads is a data race, not a feature.
 *
 * @param <S> the state type, usually an enum
 */
public final class Transitions<S> {

  private final Map<S, Set<S>> allowed = new HashMap<>();
  private final Map<S, ErrorCode> refusalCodes = new HashMap<>();

  private Transitions() {}

  /** Start declaring a transition table. */
  public static <S> Transitions<S> of() {
    return new Transitions<>();
  }

  /**
   * Declare {@code from -> to} as a legal transition. A refused attempt to reach {@code to} carries
   * no {@link ErrorCode}; prefer {@link #allow(Object, Object, ErrorCode)} where the refusal is
   * worth a stable identity at the edge.
   *
   * @return this table, for chaining
   */
  public Transitions<S> allow(S from, S to) {
    allowed.computeIfAbsent(from, key -> new HashSet<>()).add(to);
    return this;
  }

  /**
   * Declare {@code from -> to} as a legal transition and name the refusal: an illegal attempt to
   * reach {@code to} throws with {@code refusalCode}. Every edge into one destination must carry
   * the same code — the refusal is about the destination, and which exception a caller sees must
   * not depend on which illegal source happened to try first.
   *
   * @return this table, for chaining
   * @throws IllegalArgumentException if {@code to} was already declared with a different code
   */
  public Transitions<S> allow(S from, S to, ErrorCode refusalCode) {
    Objects.requireNonNull(refusalCode, "refusalCode must not be null; use allow(from, to)");
    ErrorCode existing = refusalCodes.putIfAbsent(to, refusalCode);
    if (existing != null && !existing.equals(refusalCode)) {
      throw new IllegalArgumentException(
          "conflicting refusal codes for destination "
              + to
              + ": "
              + existing.code()
              + " and "
              + refusalCode.code());
    }
    return allow(from, to);
  }

  /** Whether {@code from -> to} was declared legal. */
  public boolean permits(S from, S to) {
    return allowed.getOrDefault(from, Set.of()).contains(to);
  }

  /**
   * Assert that {@code from -> to} is legal.
   *
   * @throws IllegalStateTransitionException if the transition was not declared, carrying the {@link
   *     ErrorCode} declared for {@code to} (none, if {@code to} was only ever declared without one)
   */
  public void check(S from, S to) {
    if (!permits(from, to)) {
      throw new IllegalStateTransitionException(refusalCodes.get(to), from, to);
    }
  }
}
