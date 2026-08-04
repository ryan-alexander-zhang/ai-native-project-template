package com.example.samples.s24.coupons.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.domain.Coupon;
import com.example.samples.s24.coupons.domain.CouponErrorCode;
import com.example.samples.s24.coupons.domain.Coupons;
import com.example.samples.s24.sharedkernel.api.Money;
import org.springframework.stereotype.Component;

/** Mint one. */
@Component
class IssueCouponHandler implements CommandHandler<IssueCoupon, Void> {

  private final Coupons coupons;

  IssueCouponHandler(Coupons coupons) {
    this.coupons = coupons;
  }

  @Override
  public Void handle(IssueCoupon command, CommandContext context) {
    CouponCode code = new CouponCode(command.code());
    if (coupons.find(code).isPresent()) {
      throw new DomainException(
          CouponErrorCode.COUPON_ALREADY_ISSUED, "coupon " + code + " has already been issued");
    }
    Coupon coupon =
        command.percentOff() != null
            ? Coupon.percentOff(
                code,
                command.percentOff(),
                command.currency(),
                command.validFrom(),
                command.validUntil(),
                command.maxRedemptions())
            : Coupon.fixedAmount(
                code,
                Money.of(command.amountOffMinor(), command.currency()),
                command.validFrom(),
                command.validUntil(),
                command.maxRedemptions());
    coupons.save(coupon);
    return null;
  }
}
