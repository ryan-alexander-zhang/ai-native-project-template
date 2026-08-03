package com.example.samples.s03.rewards.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A second aggregate, so the sample can show a subscriber changing something other than the aggregate
 * that raised the event — and what transaction that change lands in.
 */
@AggregateRoot
public final class Coupon extends AbstractAggregateRoot<CouponId> {

  private final CouponId id;
  private final String customerId;
  private final long valueCents;

  private Coupon(CouponId id, String customerId, long valueCents) {
    this.id = id;
    this.customerId = customerId;
    this.valueCents = valueCents;
  }

  public static Coupon welcome(CouponId id, String customerId, long valueCents) {
    return new Coupon(id, customerId, valueCents);
  }

  public static Coupon reconstitute(CouponId id, String customerId, long valueCents, long version) {
    Coupon coupon = new Coupon(id, customerId, valueCents);
    coupon.restoreVersion(version);
    return coupon;
  }

  @Override
  public CouponId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public long valueCents() {
    return valueCents;
  }
}
