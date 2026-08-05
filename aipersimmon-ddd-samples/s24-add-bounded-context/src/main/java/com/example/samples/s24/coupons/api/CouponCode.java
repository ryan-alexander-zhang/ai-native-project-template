package com.example.samples.s24.coupons.api;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A coupon's code, and the one identifier this context publishes.
 *
 * <p>In {@code api} because another context has to be able to hold one: ordering stores the code it applied, and
 * an order that recorded a bare {@code String} would have thrown away the only validation there is. Publishing the
 * identifier type is almost always right — it is the reference, and a reference with no type is how a coupon code
 * ends up in a customer-id column.
 *
 * <p>Note what does <em>not</em> come with it. There is no {@code Coupon} in {@code api}: ordering can name a coupon
 * and cannot read one. That asymmetry is the boundary — the difference between "I hold a reference to something of
 * yours" and "I know what yours looks like".
 *
 * <p>And note that this is <strong>not</strong> shared kernel, even though two contexts use it. Only coupons decides
 * what a valid code is; ordering has no say. Shared kernel is for what neither context may change alone.
 *
 * <p>It implements the library's {@code Identifier} marker, which is a small consequence worth noticing: a published
 * identifier is also the aggregate's identity, so the contract carries a framework marker. That is the one framework
 * dependency an {@code api} package legitimately has — and it is a marker interface with no behaviour, which is why it
 * does not make the contract a shared model.
 *
 * <p><strong>It is annotated {@code @ValueObject}, and for a while it could not be.</strong>
 * {@code BuildingBlockRules.domainBuildingBlocksShouldResideInDomain} — part of the parameterless {@code all()} —
 * required every {@code @ValueObject} to live in {@code ..domain..}, while {@code BoundedContextRules} requires anything
 * another context may touch to live in {@code ..api..}. A published value type satisfied neither pair, so the only way to
 * a green build was to strip the annotation from exactly the types that are most exposed — which also stripped
 * {@code valueObjectsShouldBeImmutable} from them. Filed as issue-00170 and fixed in the library, which now allows a
 * {@code @ValueObject} in {@code ..domain..} <em>or</em> {@code ..api..} while still holding {@code @AggregateRoot} and
 * {@code @Entity} to the domain. The marker is back, and with it the immutability check.
 */
@ValueObject
public record CouponCode(String value) implements Identifier {

  public CouponCode {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("coupon code must not be blank");
    }
    if (!value.matches("[A-Z0-9-]{3,32}")) {
      throw new IllegalArgumentException(
          "coupon code must be 3..32 characters of A-Z, 0-9 or '-', was " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
