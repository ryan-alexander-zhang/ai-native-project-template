package com.example.samples.s24.coupons.domain;

import com.example.samples.s24.coupons.api.CouponCode;
import java.util.Optional;

/**
 * The coupon repository, and one of the things that must <strong>not</strong> be in {@code api}.
 *
 * <p>A port that hands back the aggregate is how a boundary becomes a shared model. Publishing it would let ordering
 * load a coupon, read its window, do its own arithmetic, and cache the result — all without a single dependency on
 * anything called an internal.
 */
public interface Coupons {

  Optional<Coupon> find(CouponCode code);

  void save(Coupon coupon);
}
