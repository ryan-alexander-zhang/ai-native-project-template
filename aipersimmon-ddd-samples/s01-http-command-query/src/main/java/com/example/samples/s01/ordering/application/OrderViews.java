package com.example.samples.s01.ordering.application;

import java.util.Optional;

/**
 * The read-side port.
 *
 * <p>It lives in the application layer rather than the domain on purpose: reading is not part of the
 * order's ubiquitous language, and keeping it out of {@code Orders} leaves the write port with only
 * the two methods the invariants need. The domain never calls this.
 */
public interface OrderViews {

  Optional<OrderView> findById(String orderId);
}
