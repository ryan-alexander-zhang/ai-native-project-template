package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodSku;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItem;

/**
 * Violates the version-witness rule: an application-layer class calling {@code versionAdvanced()},
 * which advances the optimistic-lock witness without a version-checked write having happened.
 */
public class BadVersionWitnessAdvance {

  public void pretendTheWriteHappened() {
    new GoodStockItem(new GoodSku("sku-1")).versionAdvanced();
  }
}
