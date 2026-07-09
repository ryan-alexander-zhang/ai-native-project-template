package com.acme.samples.s2.ordering.application.order;

/** Driven port: external pricing lookup. */
public interface PricingPort {
    long unitPriceMinor(String sku);
}
