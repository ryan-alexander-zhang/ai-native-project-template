package com.example.samples.s03.rewards.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s03.rewards.domain.Coupon;
import com.example.samples.s03.rewards.domain.CouponId;
import com.example.samples.s03.rewards.domain.Coupons;
import org.springframework.stereotype.Repository;

/** The rewards adapter. */
@Repository
class MyBatisCoupons extends MybatisPlusAggregateRepository<Coupon, CouponRow> implements Coupons {

  MyBatisCoupons(CouponMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
  }

  @Override
  public void save(Coupon coupon) {
    saveAggregate(coupon);
  }

  @Override
  protected CouponRow toRow(Coupon coupon) {
    CouponRow row = new CouponRow();
    row.setId(coupon.id().value());
    row.setCustomerId(coupon.customerId());
    row.setValueCents(coupon.valueCents());
    return row;
  }
}
