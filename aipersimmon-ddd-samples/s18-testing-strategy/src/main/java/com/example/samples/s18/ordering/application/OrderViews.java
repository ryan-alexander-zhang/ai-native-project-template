package com.example.samples.s18.ordering.application;

import java.util.Optional;

/** The read-side port. */
public interface OrderViews {

  Optional<OrderView> findById(String orderId);
}
