package com.example.samples.s24.coupons.application;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Instant;

/**
 * Which orders have already been counted. Outside the aggregate; see {@code RecordRedemptions}.
 *
 * <p>Public because {@code infrastructure} implements it, and declared here rather than in {@code domain} because the
 * receipts bear no invariant — the limit lives on the aggregate. They are how an at-least-once delivery is made to
 * count once, which is an application concern; a port in the domain would have put idempotency into the model's
 * vocabulary.
 */
public interface RedemptionReceipts {

  /**
   * Claim a receipt for {@code orderId}.
   *
   * @return false when one already exists, which is how a redelivery becomes a no-op
   */
  boolean record(CouponCode code, String orderId, Money discount, Instant at);

  /** How many orders this coupon has been counted for. For the read side and for tests. */
  int countFor(CouponCode code);
}
