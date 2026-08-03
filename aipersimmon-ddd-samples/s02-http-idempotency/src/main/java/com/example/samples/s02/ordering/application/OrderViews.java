package com.example.samples.s02.ordering.application;

import java.util.Optional;

/** The read-side port. */
public interface OrderViews {

  Optional<OrderView> findById(String orderId);
}
