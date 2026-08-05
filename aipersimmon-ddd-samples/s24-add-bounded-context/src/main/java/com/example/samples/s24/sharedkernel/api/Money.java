package com.example.samples.s24.sharedkernel.api;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * An amount in minor units, plus a currency. The one type all three contexts share.
 *
 * <p>It earns that place by a test that is easy to state and easy to fail: <strong>could any single context change
 * it unilaterally?</strong> No — a change to what money means has to be agreed by ordering, by coupons, and by
 * anybody who joins later. That is what makes it shared kernel rather than something one context happens to own.
 *
 * <p>The corollary is the useful half. Things that look shared and are not:
 *
 * <ul>
 *   <li>{@code CouponCode} — only coupons decides what a code may be, so it belongs to coupons even though
 *       ordering holds one;
 *   <li>{@code OrderStatus} — ordering's own vocabulary. Sharing it would let coupons branch on it, and then
 *       ordering could not add a status without asking;
 *   <li>a {@code Clock}, an id generator, a Jackson module — infrastructure, not language. They belong to the
 *       composition root;
 *   <li>anything with a repository behind it. A shared kernel type that can be looked up is a shared <em>model</em>,
 *       which is the thing bounded contexts exist to avoid.
 * </ul>
 *
 * <p>And a mechanical property that makes the judgement checkable rather than a matter of taste: the shared kernel
 * <strong>depends on no context</strong>. It is a leaf. The moment it needs to know about one, it is that context's
 * type wearing a shared name. {@code ArchitectureTest.thesharedKernelIsALeaf} pins it.
 *
 * <p><strong>It is annotated {@code @ValueObject}, and for a while it could not be.</strong>
 * {@code BuildingBlockRules.domainBuildingBlocksShouldResideInDomain} — part of the parameterless {@code all()} —
 * required every {@code @ValueObject} to live in {@code ..domain..}, while {@code BoundedContextRules} requires anything
 * another context may touch to live in {@code ..api..}. A shared-kernel value type satisfied neither pair, so the only
 * way to a green build was to strip the annotation from exactly the types that are most exposed — which also stripped
 * {@code valueObjectsShouldBeImmutable} from them. Filed as issue-00170 and fixed in the library, which now allows a
 * {@code @ValueObject} in {@code ..domain..} <em>or</em> {@code ..api..} while still holding {@code @AggregateRoot} and
 * {@code @Entity} to the domain. The marker is back, and with it the immutability check.
 */
@ValueObject
public record Money(long minor, String currency) {

  public Money {
    Objects.requireNonNull(currency, "currency");
    if (currency.length() != 3) {
      throw new IllegalArgumentException("currency must be a three-letter code, was " + currency);
    }
  }

  public static Money of(long minor, String currency) {
    return new Money(minor, currency);
  }

  public static Money zero(String currency) {
    return new Money(0, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(minor + other.minor, currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(minor - other.minor, currency);
  }

  public Money times(int factor) {
    return new Money(minor * factor, currency);
  }

  /** Rounded down, which is the direction that never invents money. */
  public Money percent(int percent) {
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be 0..100, was " + percent);
    }
    return new Money(minor * percent / 100, currency);
  }

  public boolean isGreaterThan(Money other) {
    requireSameCurrency(other);
    return minor > other.minor;
  }

  public boolean isNegative() {
    return minor < 0;
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "cannot combine " + currency + " with " + other.currency);
    }
  }

  @Override
  public String toString() {
    return minor + " " + currency;
  }
}
