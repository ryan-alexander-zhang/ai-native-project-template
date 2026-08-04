package com.example.samples.s24.coupons.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.domain.Coupon;
import com.example.samples.s24.coupons.domain.Coupons;
import com.example.samples.s24.sharedkernel.api.Money;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The coupon's write path. */
@Repository
class MyBatisCoupons extends MybatisPlusAggregateRepository<Coupon, CouponRow> implements Coupons {

  private final CouponMapper mapper;

  MyBatisCoupons(CouponMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Coupon coupon) {
    saveAggregate(coupon);
  }

  @Override
  public Optional<Coupon> find(CouponCode code) {
    CouponRow row = mapper.selectById(code.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Coupon.reconstitute(
            code,
            row.getKind(),
            Money.of(row.getValueMinor() == null ? 0 : row.getValueMinor(), row.getCurrency()),
            row.getPercentOff() == null ? 0 : row.getPercentOff(),
            row.getValidFrom(),
            row.getValidUntil(),
            row.getMaxRedemptions(),
            row.getRedemptions(),
            row.getVersion()));
  }

  @Override
  protected CouponRow toRow(Coupon coupon) {
    CouponRow row = new CouponRow();
    row.setCode(coupon.id().value());
    row.setKind(coupon.kindName());
    row.setValueMinor(coupon.value().minor());
    row.setPercentOff(coupon.percentOff());
    row.setCurrency(coupon.currency());
    row.setValidFrom(coupon.validFrom());
    row.setValidUntil(coupon.validUntil());
    row.setMaxRedemptions(coupon.maxRedemptions());
    row.setRedemptions(coupon.redemptions());
    return row;
  }
}
