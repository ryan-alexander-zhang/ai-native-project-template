package com.aipersimmon.ddd.archunit.fixture.bad.ordering.adapter;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain.BadOrder;

/**
 * Violates the stricter hexagonal rule: an inbound adapter reaching directly into the domain
 * instead of going through the application layer.
 */
public class BadOrderEndpoint {

  public String idOf(BadOrder order) {
    return order.service().toString();
  }
}
