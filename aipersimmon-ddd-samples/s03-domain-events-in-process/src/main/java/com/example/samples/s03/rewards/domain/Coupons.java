package com.example.samples.s03.rewards.domain;

import com.aipersimmon.ddd.core.annotation.Repository;

/** The rewards port. */
@Repository
public interface Coupons {

  void save(Coupon coupon);
}
