package com.example.samples.s24.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Stock was set aside. Published, and consumed by nobody in this sample.
 *
 * <p>Kept exactly because of that, as the counterexample to a tempting habit: publishing a full set of events for every
 * aggregate in advance, on the grounds that somebody will want them. Each one is a promise, and this sample is carrying
 * one it did not need — the honest rule is that a fact becomes a published event when the second party exists, not
 * before. It stays here as the visible cost of the other choice.
 */
@EventType(name = "com.example.samples.inventory.StockReserved", version = 1, source = "/stock")
public record StockReserved(String sku, int quantity) implements IntegrationEvent {

  @Override
  public String subject() {
    return sku;
  }
}
