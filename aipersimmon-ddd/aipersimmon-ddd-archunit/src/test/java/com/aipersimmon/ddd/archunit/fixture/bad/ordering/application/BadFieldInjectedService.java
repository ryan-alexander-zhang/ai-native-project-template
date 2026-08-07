package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.application.GoodPlaceOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Violates {@code dependenciesShouldBeConstructorInjected}, in both spellings: an
 * {@code @Autowired} collaborator field and an {@code @Value} configuration field. Neither can be
 * {@code final} and neither can be supplied by a caller, so this class has no constructor that
 * yields a usable instance.
 */
public class BadFieldInjectedService {

  @Autowired private GoodPlaceOrderService placeOrder;

  @Value("${orders.limit:10}")
  private int limit;

  public int limit() {
    return limit;
  }

  public String place(String customerId) {
    return placeOrder.place(customerId);
  }
}
