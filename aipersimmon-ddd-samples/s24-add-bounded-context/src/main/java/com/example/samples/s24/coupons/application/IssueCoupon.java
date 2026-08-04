package com.example.samples.s24.coupons.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Issue a coupon. This context's own use case, and one of the things that stays out of {@code api}.
 *
 * <p>Nobody outside coupons issues coupons, so publishing this record would publish four things nobody asked for:
 * its field names, its validation annotations, its return type, and the fact that this context uses a command bus at
 * all. When another context does need to trigger something here — as ordering needs a redemption — what it gets is a
 * one-method interface ({@code CouponRedemptions}), not the command.
 *
 * @param code the code customers will type
 * @param percentOff a percentage, or null for a fixed amount
 * @param amountOffMinor a fixed amount in minor units, or null for a percentage
 */
public record IssueCoupon(
    @NotBlank String code,
    Integer percentOff,
    Long amountOffMinor,
    @NotBlank String currency,
    @NotNull Instant validFrom,
    @NotNull Instant validUntil,
    @Min(1) int maxRedemptions)
    implements Command<Void> {

  public IssueCoupon {
    if ((percentOff == null) == (amountOffMinor == null)) {
      throw new IllegalArgumentException(
          "a coupon is either a percentage or a fixed amount, and this one is "
              + (percentOff == null ? "neither" : "both"));
    }
  }
}
