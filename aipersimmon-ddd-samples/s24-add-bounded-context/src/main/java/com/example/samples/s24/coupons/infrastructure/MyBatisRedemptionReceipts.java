package com.example.samples.s24.coupons.infrastructure;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.application.RedemptionReceipts;
import com.example.samples.s24.sharedkernel.api.Money;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Receipts, straight over the redemption table.
 *
 * <p>The port it implements is declared in {@code application}, not {@code domain} — see {@code RedemptionReceipts}
 * for why.
 */
@Component
class MyBatisRedemptionReceipts implements RedemptionReceipts {

  private final RedemptionMapper mapper;

  MyBatisRedemptionReceipts(RedemptionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean record(CouponCode code, String orderId, Money discount, Instant at) {
    return mapper.recordIfAbsent(code.value(), orderId, discount.minor(), Timestamp.from(at)) == 1;
  }

  @Override
  public int countFor(CouponCode code) {
    return mapper.countFor(code.value());
  }
}
