package com.aipersimmon.ddd.archunit.fixture.contexts.bad.alpha.application;

import com.aipersimmon.ddd.archunit.fixture.contexts.bad.beta.domain.BetaThing;

/**
 * Violates {@code dependOnEachOtherOnlyThroughApi}: alpha reaches into beta's internal domain type
 * instead of depending on beta's {@code ..api..} contract.
 */
public class AlphaService {

  public String describe(BetaThing thing) {
    return "alpha reaching into beta internals " + thing.value();
  }
}
